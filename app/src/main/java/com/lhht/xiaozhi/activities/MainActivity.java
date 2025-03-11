package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.button.MaterialButton;
import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import vip.inode.demo.opusaudiodemo.utils.OpusUtils;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    private static final int PLAY_BUFFER_SIZE = 65536;  // 增大缓冲区到64KB
    private static final int OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
    private static final int MAX_QUEUE_SIZE = 5; // 最大消息队列长度
    private static final int MESSAGE_TIMEOUT = 500; // 消息处理超时时间（毫秒）

    private WebSocketManager webSocketManager;
    private SettingsManager settingsManager;
    private TextView connectionStatus;
    private Button connectButton;
    private MaterialButton recordButton;
    private EditText messageInput;
    private Button sendButton;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private ExecutorService executorService;
    private boolean isPlaying = false;
    private byte[] audioBuffer;
    private OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private short[] decodedBuffer;
    private short[] recordBuffer;
    private TextView callStatusText;
    private WaveformView waveformView;
    private View voiceContainer;
    private ExecutorService audioExecutor;  // 音频处理线程池
    private TextView emojiText;
    private TextView messageText;
    private String lastEmoji = "";
    private String lastMessage = "";
    private String currentEmoji = "";
    private String currentMessage = "";
    private String nextEmoji = "";
    private String nextMessage = "";
    private boolean isFirstMessage = true;
    private boolean isAudioTrackPlaying = false;
    private boolean isAudioTrackPaused = false;
    private Handler mainHandler;
    private final Object messageLock = new Object();
    private volatile String currentText = "";
    private volatile boolean isProcessingMessage = false;
    private long lastMessageTime = 0;
    private String pendingText = null;
    private volatile String pendingAudioText = null;
    private ShapeableImageView avatarImage;

    // 添加一个消息队列类来处理消息顺序
    private static class TTSMessage {
        final String text;
        final long timestamp;
        final String sessionId;

        TTSMessage(String text, String sessionId) {
            this.text = text;
            this.timestamp = System.nanoTime(); // 使用纳秒级时间戳
            this.sessionId = sessionId;
        }
    }

    // 在类成员变量中添加
    private volatile TTSMessage currentTTSMessage = null;
    private volatile String currentSessionId = null;

    // MessageHandler 内部类
    private class MessageHandler {
        private static final int MAX_TEXT_LENGTH = 100; // 长文本阈值
        
        public synchronized void reset() {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        public synchronized void processMessage(String text) {
            if (text == null || text.isEmpty()) return;
            
            String[] parts = extractEmojiAndText(text);
            String emoji = parts[0];
            String cleanText = parts[1];
            
            mainHandler.postAtFrontOfQueue(() -> {
                try {
                    updateEmojiView(emoji);
                    updateTextView(cleanText);
                    Log.d("XiaoZhi", "UI更新完成: " + text + " 时间: " + System.nanoTime());
                } catch (Exception e) {
                    Log.e("XiaoZhi", "更新显示失败", e);
                }
            });
        }
        
        private String[] extractEmojiAndText(String text) {
            String emoji = "";
            String cleanText = text;
            
            if (text.length() > 0) {
                int firstCodePoint = text.codePointAt(0);
                if (Character.getType(firstCodePoint) == Character.SURROGATE || 
                    Character.getType(firstCodePoint) == Character.OTHER_SYMBOL) {
                    emoji = new String(Character.toChars(firstCodePoint));
                    cleanText = text.substring(Character.charCount(firstCodePoint)).trim();
                }
            }
            
            return new String[]{emoji, cleanText};
        }
    }

    // 创建消息处理器实例
    private final MessageHandler messageHandler = new MessageHandler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("MainActivity", "应用启动");
        
        // 设置沉浸式状态栏和导航栏
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        
        // 设置沉浸式标志
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decorView.setSystemUiVisibility(flags);
        
        setContentView(R.layout.activity_main);

        // 初始化视图
        avatarImage = findViewById(R.id.avatarImage);
        connectionStatus = findViewById(R.id.connectionStatus);
        connectButton = findViewById(R.id.connectButton);
        recordButton = findViewById(R.id.recordButton);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        emojiText = findViewById(R.id.emojiText);
        messageText = findViewById(R.id.messageText);
        
        // 设置输入框的回车键监听
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        
        Log.i("MainActivity", "应用启动");

        // 初始化
        settingsManager = new SettingsManager(this);
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.i("MainActivity", "设备ID: " + deviceId);
        webSocketManager = new WebSocketManager(deviceId);
        webSocketManager.setListener(this);
        executorService = Executors.newSingleThreadExecutor();
        audioExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(getMainLooper());

        // 设置按钮点击事件
        if (connectButton != null) connectButton.setOnClickListener(v -> toggleConnection());
        if (recordButton != null) recordButton.setOnClickListener(v -> startVoiceCall());
        if (sendButton != null) sendButton.setOnClickListener(v -> sendMessage());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());

        // 检查并请求权限
        checkPermissions();

        // 初始化音频播放器
        int minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        );
        Log.i("MainActivity", "AudioTrack最小缓冲区: " + minBufferSize + " 字节");
        
        try {
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(PLAY_BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
            
            int state = audioTrack.getState();
            if (state == AudioTrack.STATE_INITIALIZED) {
                Log.i("MainActivity", "AudioTrack初始化成功");
            } else {
                Log.e("MainActivity", "AudioTrack初始化失败: " + state);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "创建AudioTrack失败", e);
        }

        // 初始化 Opus 编解码器
        opusUtils = OpusUtils.getInstance();
        encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
        decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
        decodedBuffer = new short[OPUS_FRAME_SIZE];
        recordBuffer = new short[OPUS_FRAME_SIZE];
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void toggleConnection() {
        if (!webSocketManager.isConnected()) {
            String wsUrl = settingsManager.getWsUrl();
            String token = settingsManager.getToken();
            boolean enableToken = settingsManager.isTokenEnabled();
            
            // 添加日志和空值检查
            Log.d("WebSocket", "正在连接: " + wsUrl + ", token启用: " + enableToken);
            if (wsUrl == null || wsUrl.isEmpty()) {
                Toast.makeText(this, "WebSocket地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                webSocketManager.connect(wsUrl, token, enableToken);
            } catch (Exception e) {
                Log.e("WebSocket", "连接失败: " + e.getMessage());
                Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            webSocketManager.disconnect();
        }
    }

    private void startVoiceCall() {
        Intent intent = new Intent(this, VoiceCallActivity.class);
        startActivity(intent);
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (!message.isEmpty() && webSocketManager.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("type", "listen");
                jsonMessage.put("state", "detect");
                jsonMessage.put("text", message);
                jsonMessage.put("source", "text");
                webSocketManager.sendMessage(jsonMessage.toString());
                messageInput.setText("");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public void onConnected() {
        Log.d("WebSocket", "连接成功");
        addLog("WebSocket", "已连接");
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_connected)));
            connectButton.setText(R.string.disconnect);
            Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDisconnected() {
        Log.d("WebSocket", "连接断开");
        addLog("WebSocket", "已断开");
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_disconnected)));
            connectButton.setText(R.string.connect);
            stopAudioAndReset();
            Toast.makeText(this, "连接已断开", Toast.LENGTH_SHORT).show();
        });
    }

    private void stopAudioAndReset() {
        isRecording = false;
        isPlaying = false;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e("MainActivity", "停止录音失败", e);
            }
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e("MainActivity", "停止播放失败", e);
            }
            audioTrack = null;
        }
        
        // 重置UI状态
        messageHandler.reset();
    }

    @Override
    public void onError(String error) {
        Log.e("WebSocket", "错误: " + error);
        addLog("Error", error);
        runOnUiThread(() -> {
            connectionStatus.setText(getString(R.string.connection_status, getString(R.string.status_error)));
            connectButton.setText(R.string.connect);
            Toast.makeText(this, "错误: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            String state = jsonMessage.optString("state");
            
            Log.d("XiaoZhi", "收到消息: " + message + " 时间: " + System.nanoTime());
            
            if ("tts".equals(type)) {
                String sessionId = jsonMessage.optString("session_id");
                switch (state) {
                    case "start":
                        messageHandler.reset();
                        audioExecutor.execute(this::initAudioTrack);
                        break;
                        
                    case "sentence_start":
                        if (jsonMessage.has("text")) {
                            String text = jsonMessage.getString("text");
                            runOnUiThread(() -> {
                                try {
                                    String[] parts = messageHandler.extractEmojiAndText(text);
                                    updateEmojiView(parts[0]);
                                    updateTextView(parts[1]);
                                    Log.d("XiaoZhi", "更新UI: " + text + " 时间: " + System.nanoTime());
                                } catch (Exception e) {
                                    Log.e("XiaoZhi", "更新显示失败", e);
                                }
                            });
                        }
                        break;
                        
                    case "stop":
                        handleTTSStop();
                        break;
                }
            }
        } catch (Exception e) {
            Log.e("XiaoZhi", "处理消息失败", e);
        }
    }

    private void handleTTSStart() {
        messageHandler.reset();
        audioExecutor.execute(this::initAudioTrack);
    }

    private void handleTTSSentence(JSONObject jsonMessage) {
        try {
            if (jsonMessage.has("text")) {
                String text = jsonMessage.getString("text");
                // 立即处理文本，不等待 sentence_start
                messageHandler.processMessage(text);
            }
        } catch (Exception e) {
            Log.e("XiaoZhi", "处理句子失败", e);
        }
    }

    private void handleTTSStop() {
        audioExecutor.execute(() -> {
            try {
                if (audioTrack != null && isAudioTrackPlaying) {
                    audioTrack.flush();
                    audioTrack.stop();
                    isAudioTrackPlaying = false;
                    isPlaying = false;
                }
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "停止音频播放失败", e);
            }
        });
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        final byte[] audioData = data.clone();
        audioExecutor.execute(() -> {
            try {
                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    initAudioTrack();
                }

                if (!isAudioTrackPlaying || isAudioTrackPaused) {
                    audioTrack.play();
                    isAudioTrackPlaying = true;
                    isAudioTrackPaused = false;
                }

                // 解码和播放音频...
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodedBuffer);
                if (decodedSamples <= 0) {
                    return;
                }

                byte[] pcmData = new byte[decodedSamples * 2];
                for (int i = 0; i < decodedSamples; i++) {
                    short sample = decodedBuffer[i];
                    pcmData[i * 2] = (byte) (sample & 0xff);
                    pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                }

                audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_BLOCKING);
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "处理音频数据失败", e);
            }
        });
    }

    private void initAudioTrack() {
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
            
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(PLAY_BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play();
                isAudioTrackPlaying = true;
                isAudioTrackPaused = false;
                isPlaying = true;
            } else {
                throw new IllegalStateException("AudioTrack初始化失败");
            }
        } catch (Exception e) {
            Log.e("XiaoZhi-Audio", "初始化AudioTrack失败: " + e.getMessage());
            isAudioTrackPlaying = false;
            isPlaying = false;
        }
    }

    private void addLog(String tag, String message) {
        Log.i("XiaoZhi-" + tag, message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webSocketManager.disconnect();
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }
        executorService.shutdown();
        audioExecutor.shutdown();
    }

    // 移到MainActivity类中的方法
    private void updateEmojiView(String emoji) {
        if (emoji.isEmpty()) {
            emojiText.setVisibility(View.GONE);
        } else {
            emojiText.setText(emoji);
            emojiText.setVisibility(View.VISIBLE);
        }
    }
    
    private void updateTextView(String text) {
        View messageCard = findViewById(R.id.messageCard);
        if (text.isEmpty()) {
            messageCard.setVisibility(View.GONE);
            messageText.setVisibility(View.GONE);
        } else {
            messageCard.setVisibility(View.VISIBLE);
            messageText.setVisibility(View.VISIBLE);
            messageText.setText(text);
        }
    }
} 
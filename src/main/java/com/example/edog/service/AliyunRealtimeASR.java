package com.example.edog.service;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Aliyun realtime ASR helper (compatible with NLS SDK 2.2.x).
 */
public class AliyunRealtimeASR {
    private static final Logger log = LoggerFactory.getLogger(AliyunRealtimeASR.class);

    // Shared NlsClient to avoid repeated handshakes; guarded by clientLock.
    private static NlsClient nlsClient;
    private static String currentClientToken = "";
    private static final Object clientLock = new Object();

    private SpeechTranscriber transcriber;
    private OpusDecoder opusDecoder;
    private Consumer<String> textCallback;
    private volatile boolean isRunning = false;
    private final short[] pcmBuffer = new short[5760];
    private final String appKey;

    public AliyunRealtimeASR(String appKey) {
        this.appKey = appKey;
        try {
            this.opusDecoder = new OpusDecoder(16000, 1);
        } catch (OpusException e) {
            log.error("Opus decoder 初始化失败", e);
        }
    }

    public void setOnResultCallback(Consumer<String> callback) {
        this.textCallback = callback;
    }

    /**
     * Start ASR with the latest valid token.
     */
    public void start(String token) {
        if (appKey == null || appKey.isEmpty()) {
            log.error("ASR 启动失败：AppKey 为空，请检查 aliyun.appKey 配置");
            throw new IllegalArgumentException("AppKey is null or empty");
        }
        if (token == null || token.isEmpty()) {
            log.error("ASR 启动失败：传入的 Token 为空，请检查 AccessKey/Token 服务");
            throw new IllegalArgumentException("Token is null or empty");
        }

        // 1. Initialize or refresh shared NlsClient when token changes.
        synchronized (clientLock) {
            if (nlsClient == null || !token.equals(currentClientToken)) {
                log.info("检测到 Token 变更或 Client 未初始化，正在重置 NlsClient...");

                if (nlsClient != null) {
                    try {
                        nlsClient.shutdown();
                    } catch (Exception e) {
                        log.warn("关闭旧 NlsClient 失败", e);
                    }
                }

                try {
                    nlsClient = new NlsClient(token);
                    currentClientToken = token;
                    log.info("NlsClient 初始化/刷新成功");
                } catch (Exception e) {
                    log.error("NlsClient 创建失败", e);
                    throw e;
                }
            }
        }

        try {
            // 2. Create transcriber with current client.
            transcriber = new SpeechTranscriber(nlsClient, getListener());
            transcriber.setAppKey(appKey);
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            transcriber.setEnablePunctuation(true);
            transcriber.addCustomedParam("enable_inverse_text_normalization", true);
            transcriber.setEnableIntermediateResult(false);

            transcriber.start();
            isRunning = true;
            log.info("ASR 会话启动成功");

        } catch (Exception e) {
            log.error("ASR 启动异常", e);
            isRunning = false;
            throw new RuntimeException("ASR 启动异常", e);
        }
    }

    public void sendOpusStream(byte[] opusBytes) {
        if (!isRunning || transcriber == null) return;
        if (opusBytes == null || opusBytes.length == 0) return;
        try {
            int samples = opusDecoder.decode(opusBytes, 0, opusBytes.length, pcmBuffer, 0, pcmBuffer.length, false);
            if (samples > 0) {
                byte[] pcmData = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    short s = pcmBuffer[i];
                    pcmData[i * 2] = (byte) (s & 0xff);
                    pcmData[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                }
                transcriber.send(pcmData);
            }
        } catch (Exception e) {
            log.warn("音频发送失败 {}，尝试重置 Opus 解码器", e.getMessage());
            resetDecoder();
        }
    }

    public void stop() {
        isRunning = false;
        if (transcriber != null) {
            try {
                transcriber.stop();
            } catch (Exception e) {
                log.debug("忽略 transcriber.stop 异常", e);
            } finally {
                transcriber.close();
                transcriber = null;
            }
        }
        log.info("ASR 会话已停止");
    }

    private SpeechTranscriberListener getListener() {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.info("任务开始 {}", response.getTaskId());
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.info("任务结束: {}", response.getStatus());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                log.info("识别结果: {}", text);
                if (textCallback != null && text != null && !text.isEmpty()) {
                    textCallback.accept(text);
                }
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("ASR Error: {}", response.getStatusText());
            }
        };
    }

    /**
     * 重置 Opus 解码器，防止“corrupted stream”持续影响后续帧
     */
    private void resetDecoder() {
        try {
            this.opusDecoder = new OpusDecoder(16000, 1);
            log.info("Opus 解码器已重置");
        } catch (Exception ex) {
            log.error("Opus 解码器重置失败", ex);
        }
    }
}

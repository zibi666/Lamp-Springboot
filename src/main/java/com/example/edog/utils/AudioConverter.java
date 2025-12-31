package com.example.edog.utils;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频格式转换工具
 * 将音频转换为设备可接受的裸 Opus 帧或 OGG (Opus 编码) 文件
 * 
 * 设备端要求：
 * - 格式：裸 Opus 帧（无 OGG 容器）
 * - 采样率：24000 Hz
 * - 声道：单声道 (mono)
 * - 帧时长：60ms
 * - 传输方式：每个 WebSocket BinaryMessage 就是一帧 Opus 数据
 */
public class AudioConverter {

    // FFmpeg 可执行文件路径（可通过 JVM -Dffmpeg.path 或环境变量 FFMPEG_PATH 覆盖，默认使用 PATH 中的 ffmpeg）
    private static final String FFMPEG_PATH = resolveFfmpegPath();
    
    // Opus 编码参数
    private static final int SAMPLE_RATE = 24000;       // 采样率 24kHz (匹配设备端)
    private static final int CHANNELS = 1;              // 单声道
    private static final int FRAME_DURATION_MS = 60;    // 帧时长 60ms
    private static final int FRAME_SIZE = SAMPLE_RATE * FRAME_DURATION_MS / 1000; // 每帧采样数 = 1440
    private static final int BITRATE = 32000;           // 比特率 32kbps

    /**
     * 将音频文件转换为裸 Opus 帧列表（支持 mp3/ogg/wav 等 ffmpeg 可解码格式）
     * @param inputPath 输入音频路径
     * @return Opus 帧列表
     */
    public static List<byte[]> convertToOpusFrames(String inputPath) {
        if (inputPath == null || inputPath.isEmpty()) {
            return null;
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("输入文件不存在: " + inputPath);
            return null;
        }

        try {
            byte[] pcmData = convertAudioToPcm(inputPath);
            if (pcmData == null || pcmData.length == 0) {
                System.err.println("音频转 PCM 失败");
                return null;
            }

            System.out.println("PCM 数据大小: " + pcmData.length + " bytes, 时长约: " +
                    (pcmData.length / 2.0 / SAMPLE_RATE) + " 秒");

            return encodePcmToOpusFrames(pcmData);

        } catch (Exception e) {
            System.err.println("音频转换异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 兼容旧方法名
     */
    public static List<byte[]> convertMp3ToOpusFrames(String inputMp3Path) {
        return convertToOpusFrames(inputMp3Path);
    }

    /**
     * 使用 FFmpeg 将音频转换为原始 PCM 数据
     * @param inputPath 音频文件路径
     * @return PCM 字节数据 (16-bit signed little-endian, 24kHz, mono)
     */
    private static byte[] convertAudioToPcm(String inputPath) {
        try {
            // FFmpeg 命令：将音频转为 24kHz 单声道 16-bit PCM，输出到 stdout
            ProcessBuilder pb = new ProcessBuilder(
                FFMPEG_PATH,
                "-y",
                "-i", inputPath,
                "-ar", String.valueOf(SAMPLE_RATE),  // 采样率 24000
                "-ac", String.valueOf(CHANNELS),     // 单声道
                "-f", "s16le",                       // 16-bit signed little-endian
                "-acodec", "pcm_s16le",
                "pipe:1"                             // 输出到 stdout
            );

            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 读取 PCM 数据
            ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    pcmOutput.write(buffer, 0, bytesRead);
                }
            }

            // 读取错误输出（可选）
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    // 可以打印 FFmpeg 错误输出用于调试
                    // System.err.println("[FFmpeg] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return pcmOutput.toByteArray();
            } else {
                System.err.println("FFmpeg 转换失败，退出码: " + exitCode);
                return null;
            }

        } catch (Exception e) {
            System.err.println("音频转 PCM 异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 PCM 数据编码为 Opus 帧列表
     * @param pcmData PCM 字节数据 (16-bit signed little-endian)
     * @return Opus 帧列表
     */
    private static List<byte[]> encodePcmToOpusFrames(byte[] pcmData) {
        List<byte[]> opusFrames = new ArrayList<>();

        try {
            // 创建 Opus 编码器
            OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(BITRATE);
            encoder.setSignalType(io.github.jaredmdobson.concentus.OpusSignal.OPUS_SIGNAL_VOICE);

            // 输出缓冲区
            byte[] outputBuffer = new byte[4000]; // Opus 帧最大大小

            // 将 PCM 转换为 short 数组
            int totalSamples = pcmData.length / 2;
            int frameCount = totalSamples / FRAME_SIZE;

            System.out.println("总采样数: " + totalSamples + ", 帧数: " + frameCount);

            ByteBuffer bb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
            short[] pcmSamples = new short[totalSamples];
            for (int i = 0; i < totalSamples; i++) {
                pcmSamples[i] = bb.getShort();
            }

            // 逐帧编码
            for (int frame = 0; frame < frameCount; frame++) {
                int offset = frame * FRAME_SIZE;
                
                // 获取当前帧的 PCM 数据
                short[] frameData = new short[FRAME_SIZE];
                System.arraycopy(pcmSamples, offset, frameData, 0, FRAME_SIZE);

                // 编码为 Opus
                int encodedBytes = encoder.encode(frameData, 0, FRAME_SIZE, outputBuffer, 0, outputBuffer.length);

                if (encodedBytes > 0) {
                    byte[] opusFrame = new byte[encodedBytes];
                    System.arraycopy(outputBuffer, 0, opusFrame, 0, encodedBytes);
                    opusFrames.add(opusFrame);
                }
            }

            // 处理剩余数据（如果不足一帧，补零）
            int remainingSamples = totalSamples % FRAME_SIZE;
            if (remainingSamples > 0) {
                short[] lastFrame = new short[FRAME_SIZE];
                System.arraycopy(pcmSamples, frameCount * FRAME_SIZE, lastFrame, 0, remainingSamples);
                // 剩余部分补零（静音）

                int encodedBytes = encoder.encode(lastFrame, 0, FRAME_SIZE, outputBuffer, 0, outputBuffer.length);
                if (encodedBytes > 0) {
                    byte[] opusFrame = new byte[encodedBytes];
                    System.arraycopy(outputBuffer, 0, opusFrame, 0, encodedBytes);
                    opusFrames.add(opusFrame);
                }
            }

            System.out.println("Opus 编码完成，总帧数: " + opusFrames.size());

        } catch (Exception e) {
            System.err.println("Opus 编码异常: " + e.getMessage());
            e.printStackTrace();
        }

        return opusFrames;
    }

    /**
     * 将 MP3 字节数据转换为 Opus 帧列表
     * @param mp3Data MP3 音频数据
     * @param tempDir 临时文件目录
     * @return Opus 帧列表
     */
    public static List<byte[]> convertMp3BytesToOpusFrames(byte[] mp3Data, String tempDir) {
        if (mp3Data == null || mp3Data.length == 0) {
            return null;
        }

        try {
            // 创建临时目录
            File dir = new File(tempDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 写入临时 MP3 文件
            String timestamp = String.valueOf(System.currentTimeMillis());
            File tempMp3 = new File(dir, "temp_" + timestamp + ".mp3");
            Files.write(tempMp3.toPath(), mp3Data);

            // 转换
            List<byte[]> opusFrames = convertToOpusFrames(tempMp3.getAbsolutePath());

            // 删除临时 MP3 文件
            Files.deleteIfExists(tempMp3.toPath());

            return opusFrames;

        } catch (Exception e) {
            System.err.println("转换字节数据失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将输入音频转码为 OGG (Opus 编码) 文件
     * @param inputPath 输入音频路径
     * @param saveDir 输出目录
     * @return 生成的 OGG 文件路径，失败返回 null
     */
    public static String convertToOggOpus(String inputPath, String saveDir) {
        try {
            File dir = new File(saveDir);
            if (!dir.exists()) dir.mkdirs();

            String timestamp = String.valueOf(System.currentTimeMillis());
            String outputPath = Paths.get(saveDir, "opus_" + timestamp + ".ogg").toString();

            ProcessBuilder pb = new ProcessBuilder(
                    FFMPEG_PATH,
                    "-y",
                    "-i", inputPath,
                    "-c:a", "libopus",
                    "-b:a", String.format("%dk", BITRATE / 1000),
                    "-ar", String.valueOf(SAMPLE_RATE),
                    "-ac", String.valueOf(CHANNELS),
                    outputPath
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // consume ffmpeg output
                }
            }
            int code = process.waitFor();
            if (code == 0) {
                return outputPath;
            }
            System.err.println("转码 OGG 失败，退出码: " + code);
        } catch (Exception e) {
            System.err.println("转码 OGG 异常: " + e.getMessage());
        }
        return null;
    }

    /**
     * 检查 FFmpeg 是否可用
     */
    public static boolean isFFmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(FFMPEG_PATH, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取编码参数信息
     */
    public static String getEncodingInfo() {
        return String.format("Opus编码参数: 采样率=%dHz, 声道=%d, 帧时长=%dms, 帧大小=%d采样, 比特率=%dbps",
                SAMPLE_RATE, CHANNELS, FRAME_DURATION_MS, FRAME_SIZE, BITRATE);
    }

    private static String resolveFfmpegPath() {
        String prop = System.getProperty("ffmpeg.path");
        if (prop != null && !prop.isEmpty()) return prop;
        String env = System.getenv("FFMPEG_PATH");
        if (env != null && !env.isEmpty()) return env;
        return "ffmpeg";
    }
}

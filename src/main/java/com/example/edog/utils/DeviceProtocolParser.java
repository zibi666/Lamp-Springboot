package com.example.edog.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备协议解析工具类
 * 负责解析设备上报的状态数据，如音量、灯光状态等
 */
public class DeviceProtocolParser {
    
    private static final Pattern LT_PATTERN = Pattern.compile("^\\((\\d+)\\s*[，,]\\s*(\\d+)\\)$");
    private static final Pattern VOLUME_PATTERN = Pattern.compile("^\\((?i:volume|vol)\\s*[，,]\\s*(\\d{1,3})\\)$");
    private static final Pattern VOLUME_JSON_PATTERN = Pattern.compile("\"volume\"\\s*:\\s*(\\d{1,3})");

    /**
     * 解析音量上报数据
     * @param text 收到的文本消息
     * @return 解析出的音量值(0-100)，如果未匹配则返回null
     */
    public static Integer parseVolume(String text) {
        if (text == null) return null;
        
        Matcher vMatcher = VOLUME_PATTERN.matcher(text);
        if (vMatcher.matches()) {
            return parseIntSafe(vMatcher.group(1));
        }
        
        Matcher vjMatcher = VOLUME_JSON_PATTERN.matcher(text);
        if (vjMatcher.find()) {
            return parseIntSafe(vjMatcher.group(1));
        }
        
        return null;
    }

    /**
     * 解析灯光状态上报数据
     * @param text 收到的文本消息
     * @return 格式化后的状态字符串 "(brightness,temperature)"，如果未匹配则返回null
     */
    public static String parseLightStatus(String text) {
        if (text == null) return null;
        
        Matcher matcher = LT_PATTERN.matcher(text);
        if (matcher.matches()) {
            String brightness = matcher.group(1);
            String temperature = matcher.group(2);
            return formatLightStatus(brightness, temperature);
        }
        
        return null;
    }

    private static int parseIntSafe(String val) {
        try {
            int v = Integer.parseInt(val);
            return Math.max(0, Math.min(100, v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatLightStatus(String brightness, String temperature) {
         try {
            int b = Integer.parseInt(brightness);
            int t = Integer.parseInt(temperature);
            b = Math.max(0, Math.min(100, b));
            t = Math.max(0, Math.min(100, t));
            return "(" + b + "," + t + ")";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

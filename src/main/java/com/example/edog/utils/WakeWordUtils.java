package com.example.edog.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WakeWordUtils {

    private static final Logger log = LoggerFactory.getLogger(WakeWordUtils.class);

    private static final String WAKE_WORD_PINYIN = "xiaoling";
    private static final double WAKE_WORD_SIMILARITY_THRESHOLD = 0.75;

    /**
     * 检测是否为唤醒词
     * 1. 支持标准包含匹配
     * 2. 支持前后鼻音模糊匹配 (如 xiaolin 匹配 xiaoling)
     */
    public static boolean isWakeWord(String cleanPinyin) {
        if (cleanPinyin == null || cleanPinyin.isEmpty()) return false;

        // 1. 标准包含匹配
        if (cleanPinyin.contains(WAKE_WORD_PINYIN)) {
            return true;
        }

        // 2. 前后鼻音模糊匹配
        // 将输入和唤醒词都统一转换为前鼻音进行比较
        String inputNormalized = normalizeNasalSounds(cleanPinyin);
        String wakeWordNormalized = normalizeNasalSounds(WAKE_WORD_PINYIN);

        return inputNormalized.contains(wakeWordNormalized);
    }

    /**
     * 归一化前后鼻音
     * 将后鼻音 (ang, eng, ing) 统一转换为前鼻音 (an, en, in)
     */
    private static String normalizeNasalSounds(String pinyin) {
        if (pinyin == null) return "";
        return pinyin.replaceAll("ang", "an")
                     .replaceAll("eng", "en")
                     .replaceAll("ing", "in");
    }

    /**
     * 将文本转换为拼音
     */
    public static String convertToPinyin(String text) {
        StringBuilder pinyin = new StringBuilder();
        for (char c : text.toCharArray()) {
            try {
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c);
                if (pinyinArray != null && pinyinArray.length > 0) {
                    String raw = pinyinArray[0];
                    pinyin.append(raw.replaceAll("[1-5]", ""));
                } else {
                    pinyin.append(c);
                }
            } catch (Exception e) {
                pinyin.append(c);
            }
        }
        return pinyin.toString().toLowerCase();
    }

    /**
     * 计算两个拼音字符串的相似度
     */
    public static double calculatePinyinSimilarity(String p1, String p2) {
        if (p1 == null || p2 == null) return 0;
        if (p1.equals(p2)) return 1.0;

        // 使用编辑距离计算相似度
        int maxLen = Math.max(p1.length(), p2.length());
        if (maxLen == 0) return 1.0;

        int editDistance = levenshteinDistance(p1, p2);
        return 1.0 - (double) editDistance / maxLen;
    }

    /**
     * 计算编辑距离（Levenshtein距离）
     */
    public static int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                                dp[i - 1][j] + 1,      // 删除
                                dp[i][j - 1] + 1),     // 插入
                        dp[i - 1][j - 1] + cost // 替换
                );
            }
        }
        return dp[m][n];
    }
}

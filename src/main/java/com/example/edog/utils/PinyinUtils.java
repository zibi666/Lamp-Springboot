package com.example.edog.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import java.util.*;

public class PinyinUtils {

    /** 中文转拼音（不带声调） */
    public static String toPinyin(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c);
                if (pinyin != null) sb.append(pinyin[0].replaceAll("[^a-zA-Z]", ""));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase();
    }

    /** 余弦相似度 */
    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;

        Map<String, int[]> vector = new HashMap<>();
        for (String s : s1.split("")) {
            vector.computeIfAbsent(s, k -> new int[2])[0]++;
        }
        for (String s : s2.split("")) {
            vector.computeIfAbsent(s, k -> new int[2])[1]++;
        }

        double dot = 0, norm1 = 0, norm2 = 0;
        for (int[] v : vector.values()) {
            dot += v[0] * v[1];
            norm1 += v[0] * v[0];
            norm2 += v[1] * v[1];
        }
        return (norm1 == 0 || norm2 == 0) ? 0 : (dot / Math.sqrt(norm1) / Math.sqrt(norm2));
    }
}
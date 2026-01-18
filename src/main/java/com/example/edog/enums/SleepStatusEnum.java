package com.example.edog.enums;

/**
 * 睡眠状态枚举
 * 定义设备上报的睡眠状态类型
 */
public enum SleepStatusEnum {
    
    /**
     * 清醒状态
     */
    WAKE("WAKE"),
    
    /**
     * 快速眼动睡眠（有梦境）
     */
    REM("REM"),
    
    /**
     * 深度睡眠
     */
    DEEP("DEEP"),
    
    /**
     * 浅度睡眠
     */
    LIGHT("LIGHT");
    
    private final String value;
    
    SleepStatusEnum(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    /**
     * 根据字符串值获取枚举
     * @param value 字符串值
     * @return 对应的枚举，未找到返回null
     */
    public static SleepStatusEnum fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SleepStatusEnum status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
    
    /**
     * 判断是否为睡眠状态（REM、DEEP 或 LIGHT）
     * @param value 状态字符串
     * @return 是否为睡眠状态
     */
    public static boolean isSleeping(String value) {
        SleepStatusEnum status = fromValue(value);
        return status == REM || status == DEEP || status == LIGHT;
    }
    
    /**
     * 判断是否为深度睡眠状态
     * @param value 状态字符串
     * @return 是否为深度睡眠状态
     */
    public static boolean isDeepSleep(String value) {
        SleepStatusEnum status = fromValue(value);
        return status == DEEP;
    }
    
    /**
     * 判断是否为浅度睡眠状态
     * @param value 状态字符串
     * @return 是否为浅度睡眠状态
     */
    public static boolean isLightSleep(String value) {
        SleepStatusEnum status = fromValue(value);
        return status == LIGHT;
    }
    
    /**
     * 判断是否为清醒状态
     * @param value 状态字符串
     * @return 是否为清醒状态
     */
    public static boolean isWake(String value) {
        SleepStatusEnum status = fromValue(value);
        return status == WAKE;
    }
}

package com.example.edog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 睡眠数据汇总实体类
 * 对应表：sleep_summary
 */
@Data
@TableName("sleep_summary")
public class SleepSummary {
    
    /**
     * 主键ID，自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 查询日期（YYYY-MM-DD）
     */
    private LocalDate queryDate;
    
    /**
     * 入睡时间（首次进入REM/NREM的时刻）
     */
    private LocalDateTime sleepTime;
    
    /**
     * 清醒时间（最后一次从睡眠状态转为WAKE的时刻）
     */
    private LocalDateTime wakeTime;
    
    /**
     * 窗口内记录数
     */
    private Integer sampleCount;
    
    /**
     * 浅度睡眠时长（分钟）
     */
    private Double lightSleepDurationMin;
    
    /**
     * 深度睡眠时长（分钟）
     */
    private Double deepSleepDurationMin;
    
    /**
     * REM睡眠时长（分钟）
     */
    private Double remDurationMin;
    
    /**
     * 总睡眠时长（分钟）= REM + 浅度睡眠 + 深度睡眠
     */
    private Double totalSleepMin;
    
    /**
     * 平均心率（次/半分钟）
     */
    private Double avgHeartRate;
    
    /**
     * 平均呼吸频率（次/半分钟）
     */
    private Double avgBreathingRate;
    
    /**
     * 起床次数
     */
    private Integer wakeCount;
    
    /**
     * 清醒段起始时间列表（JSON数组格式）
     */
    private String wakeStartTimes;
    
    /**
     * 提示信息（无数据时的说明）
     */
    private String message;
    
    /**
     * 记录创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 记录更新时间
     */
    private LocalDateTime updatedAt;
}

package com.example.edog.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 睡眠汇总响应DTO
 * 用于返回指定日期的睡眠汇总数据，供扣子AI调用
 * 字段与 sleep_summary 表对应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SleepSummaryResponse {
    
    /**
     * 查询日期（YYYY-MM-DD）
     */
    @JsonProperty("query_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate queryDate;
    
    /**
     * 入睡时间（首次进入REM/NREM的时刻）
     */
    @JsonProperty("sleep_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sleepTime;
    
    /**
     * 清醒时间（最后一次从睡眠状态转为WAKE的时刻）
     */
    @JsonProperty("wake_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime wakeTime;
    
    /**
     * 窗口内记录数
     */
    @JsonProperty("sample_count")
    private Integer sampleCount;
    
    /**
     * REM时长（分钟）
     */
    @JsonProperty("rem_duration_min")
    private Double remDurationMin;
    
    /**
     * NREM时长（分钟）
     */
    @JsonProperty("nrem_duration_min")
    private Double nremDurationMin;
    
    /**
     * 总睡眠时长（分钟）= REM + NREM
     */
    @JsonProperty("total_sleep_min")
    private Double totalSleepMin;
    
    /**
     * 平均心率（次/半分钟）
     * 注意：设备上报的单位是"次/半分钟"，如需换算为"次/分钟"请乘以2
     */
    @JsonProperty("avg_heart_rate")
    private Double avgHeartRate;
    
    /**
     * 平均呼吸频率（次/半分钟）
     * 注意：设备上报的单位是"次/半分钟"，如需换算为"次/分钟"请乘以2
     */
    @JsonProperty("avg_breathing_rate")
    private Double avgBreathingRate;
    
    /**
     * 起床次数
     */
    @JsonProperty("wake_count")
    private Integer wakeCount;
    
    /**
     * 清醒段起始时间列表（JSON数组格式，升序）
     */
    @JsonProperty("wake_start_times")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private List<LocalDateTime> wakeStartTimes;
    
    /**
     * 提示信息（无数据时的说明）
     */
    @JsonProperty("message")
    private String message;
}

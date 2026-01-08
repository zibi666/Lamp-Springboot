package com.example.edog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 闹钟响应对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlarmResponse {
    
    /**
     * 闹钟ID
     */
    private Long id;
    
    /**
     * 闹钟响铃时间，格式 HH:MM:SS
     */
    private LocalTime alarmTime;
    
    /**
     * 类型：1-单次(OneTime), 2-循环(Recurring)
     */
    private Integer type;
    
    /**
     * 类型描述：OneTime 或 Recurring
     */
    private String typeDesc;
    
    /**
     * 仅针对单次闹钟：具体的触发日期 YYYY-MM-DD
     */
    private LocalDate targetDate;
    
    /**
     * 仅针对循环闹钟：存储周几，如 "1,2,3,4,5" 代表周一到周五
     */
    private String repeatDays;
    
    /**
     * 循环闹钟的中文描述
     */
    private String repeatDaysDesc;
    
    /**
     * 闹钟标签，如"起床"、"开会"
     */
    private String tag;
    
    /**
     * 状态：1-启用, 0-禁用
     */
    private Integer status;
    
    /**
     * 创建时间
     */
    private String createdAt;
    
    /**
     * 更新时间
     */
    private String updatedAt;
}

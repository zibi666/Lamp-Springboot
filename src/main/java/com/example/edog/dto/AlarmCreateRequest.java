package com.example.edog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建闹钟请求对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlarmCreateRequest {
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 闹钟响铃时间，格式 HH:MM 或 HH:MM:SS
     */
    private String alarmTime;
    
    /**
     * 类型：1-单次(OneTime), 2-循环(Recurring)
     */
    private Integer type;
    
    /**
     * 仅针对单次闹钟：具体的触发日期 YYYY-MM-DD
     */
    private String targetDate;
    
    /**
     * 仅针对循环闹钟：存储周几，如 "1,2,3,4,5" 代表周一到周五
     * 1=周一, 2=周二, ..., 7=周日
     */
    private String repeatDays;
    
    /**
     * 闹钟标签，如"起床"、"开会"
     */
    private String tag;
}

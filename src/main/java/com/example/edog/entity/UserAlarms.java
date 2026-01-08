package com.example.edog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 用户闹钟实体类
 * 支持单次闹钟和循环闹钟两种类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_alarms")
public class UserAlarms {
    
    /**
     * 主键ID，自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID，关联用户表
     */
    private String userId;
    
    /**
     * 闹钟响铃时间，格式 HH:MM:00
     */
    private LocalTime alarmTime;
    
    /**
     * 类型：1-单次(OneTime), 2-循环(Recurring)
     */
    private Integer type;
    
    /**
     * 仅针对单次闹钟：具体的触发日期 YYYY-MM-DD
     */
    private LocalDate targetDate;
    
    /**
     * 仅针对循环闹钟：存储周几，如 "1,2,3,4,5" 代表周一到周五
     */
    private String repeatDays;
    
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
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}

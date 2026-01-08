package com.example.edog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时间范围内闹钟计数响应对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlarmCountResponse {
    
    /**
     * 查询开始时间
     */
    private String startTime;
    
    /**
     * 查询结束时间
     */
    private String endTime;
    
    /**
     * 时间范围内的闹钟数量
     */
    private Integer count;
    
    /**
     * 提示信息
     */
    private String message;
}

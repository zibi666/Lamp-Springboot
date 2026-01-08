package com.example.edog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康数据实体类
 * 用于存储设备上传的心率和呼吸频率数据
 */
@Data
@TableName("health_data")
public class HealthData {
    
    /**
     * 主键ID，自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 心率（次/分钟）
     */
    private Integer heartRate;
    
    /**
     * 呼吸频率（次/分钟）
     */
    private Integer breathingRate;
    
    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;
}

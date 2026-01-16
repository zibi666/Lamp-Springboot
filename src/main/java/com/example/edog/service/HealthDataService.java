package com.example.edog.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.edog.entity.HealthData;
import com.example.edog.mapper.HealthDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 健康数据服务类
 * 处理健康数据的业务逻辑
 */
@Slf4j
@Service
public class HealthDataService extends ServiceImpl<HealthDataMapper, HealthData> {
    
    /**
     * 保存健康数据
     * @param heartRate 心率
     * @param breathingRate 呼吸频率
     * @param sleepStatus 睡眠状态
     * @param motionIndex 体动强度指标
     * @return 保存的数据
     */
    public HealthData saveHealthData(Integer heartRate, Integer breathingRate, String sleepStatus, Float motionIndex) {
        HealthData healthData = new HealthData();
        healthData.setHeartRate(heartRate);
        healthData.setBreathingRate(breathingRate);
        healthData.setSleepStatus(sleepStatus);
        healthData.setMotionIndex(motionIndex);
        healthData.setUploadTime(LocalDateTime.now());
        
        boolean success = this.save(healthData);
        
        if (success) {
            log.info("健康数据保存成功: 心率={}, 呼吸频率={}, 体动={}, 睡眠状态={}, 时间={}", 
                heartRate, breathingRate, motionIndex, sleepStatus, healthData.getUploadTime());
        } else {
            log.error("健康数据保存失败");
        }
        
        return healthData;
    }
}

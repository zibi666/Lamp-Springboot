package com.example.edog.controller;

import com.example.edog.entity.HealthData;
import com.example.edog.service.HealthDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康数据控制器
 * 提供设备上传健康数据的接口
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthDataController {
    
    private final HealthDataService healthDataService;
    
    /**
     * 上传健康数据接口
     * 设备每30秒调用一次此接口上传心率、呼吸频率和睡眠状态数据
     * 
     * @param request 包含心率、呼吸频率和睡眠状态的请求体
     * @return 响应结果
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadHealthData(@RequestBody HealthDataRequest request) {
        log.info("收到健康数据上传请求: 心率={}, 呼吸频率={}, 体动={}, 睡眠状态={}", 
            request.getHeartRate(), request.getBreathingRate(), request.getMotionIndex(), request.getSleepStatus());
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 参数校验
            if (request.getHeartRate() == null || request.getBreathingRate() == null || request.getSleepStatus() == null) {
                response.put("success", false);
                response.put("message", "心率、呼吸频率或睡眠状态不能为空");
                return response;
            }
            
            if (request.getHeartRate() < 0 || request.getHeartRate() > 300) {
                response.put("success", false);
                response.put("message", "心率数值异常（正常范围: 0-300）");
                return response;
            }
            
            if (request.getBreathingRate() < 0 || request.getBreathingRate() > 100) {
                response.put("success", false);
                response.put("message", "呼吸频率数值异常（正常范围: 0-100）");
                return response;
            }
            
            if (request.getMotionIndex() != null && (request.getMotionIndex() < 0 || request.getMotionIndex() > 100)) {
                response.put("success", false);
                response.put("message", "体动强度数值异常（正常范围: 0-100）");
                return response;
            }
            
            // 保存数据
            HealthData savedData = healthDataService.saveHealthData(
                request.getHeartRate(), 
                request.getBreathingRate(),
                request.getSleepStatus(),
                request.getMotionIndex()
            );
            
            response.put("success", true);
            response.put("message", "数据上传成功");
            response.put("data", savedData);
            
        } catch (Exception e) {
            log.error("健康数据上传失败", e);
            response.put("success", false);
            response.put("message", "数据上传失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 健康数据请求体
     */
    @lombok.Data
    public static class HealthDataRequest {
        /**
         * 心率（次/分钟）
         */
        private Integer heartRate;
        
        /**
         * 呼吸频率（次/分钟）
         */
        private Integer breathingRate;
        
        /**
         * 睡眠状态
         */
        private String sleepStatus;
        
        /**
         * 体动强度指标（0-100）
         */
        private Float motionIndex;
    }
}

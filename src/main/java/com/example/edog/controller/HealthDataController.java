package com.example.edog.controller;

import com.example.edog.entity.HealthData;
import com.example.edog.service.HealthDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
        log.info("收到健康数据上传请求: userId={}, 心率={}, 呼吸频率={}, 体动={}, 睡眠状态={}", 
            request.getUserId(), request.getHeartRate(), request.getBreathingRate(), request.getMotionIndex(), request.getSleepStatus());
        
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
            
            // 保存数据（userId 可选，默认 user123）
            HealthData savedData = healthDataService.saveHealthData(
                request.getHeartRate(), 
                request.getBreathingRate(),
                request.getSleepStatus(),
                request.getMotionIndex(),
                request.getUserId()
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
     * 获取指定用户在指定日期最新一条健康数据
     *
     * @param date 查询日期（YYYY-MM-DD）
     * @param userId 用户ID（可选，默认 user123）
     * @return 最新健康数据（当日无数据时 data 为 null）
     */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> getLatestHealthData(
            @RequestParam("date") String date,
            @RequestParam(value = "userId", required = false, defaultValue = "user123") String userId) {
        Map<String, Object> response = new HashMap<>();
        String normalizedUserId = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "user123";

        LocalDate queryDate;
        try {
            queryDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            response.put("success", false);
            response.put("message", "日期格式错误，请使用 YYYY-MM-DD 格式");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            HealthData latestData = healthDataService.getLatestHealthDataByDateAndUser(queryDate, normalizedUserId);
            response.put("success", true);
            response.put("query_date", queryDate.toString());
            response.put("user_id", normalizedUserId);

            if (latestData == null) {
                response.put("message", "该日期暂无健康数据");
                response.put("data", null);
                return ResponseEntity.ok(response);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("id", latestData.getId());
            data.put("user_id", latestData.getUserId());
            data.put("heart_rate", latestData.getHeartRate());
            data.put("breathing_rate", latestData.getBreathingRate());
            data.put("sleep_status", latestData.getSleepStatus());
            data.put("motion_index", latestData.getMotionIndex());
            data.put("upload_time", latestData.getUploadTime());

            response.put("message", "查询成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询最新健康数据失败: date={}, userId={}", date, normalizedUserId, e);
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 健康数据请求体
     */
    @lombok.Data
    public static class HealthDataRequest {
        /**
         * 用户ID（可选，默认 user123）
         */
        private String userId;
        
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

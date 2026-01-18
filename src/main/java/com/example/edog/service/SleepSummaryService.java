package com.example.edog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.edog.dto.SleepSummaryResponse;
import com.example.edog.entity.SleepSummary;
import com.example.edog.mapper.SleepSummaryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 睡眠汇总数据服务类
 * 从 sleep_summary 表读取睡眠汇总数据
 * 如果查询不到会尝试自动生成
 */
@Slf4j
@Service
public class SleepSummaryService extends ServiceImpl<SleepSummaryMapper, SleepSummary> {
    
    private final ObjectMapper objectMapper;
    
    @Autowired
    @Lazy  // 避免循环依赖
    private SleepReportGeneratorService sleepReportGeneratorService;
    
    public SleepSummaryService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * 根据日期获取睡眠汇总数据（默认用户 user123）
     * 
     * @param queryDate 查询日期
     * @return 睡眠汇总响应
     */
    public SleepSummaryResponse getSleepSummaryByDate(LocalDate queryDate) {
        return getSleepSummaryByDateAndUser(queryDate, "user123");
    }
    
    /**
     * 根据日期和用户ID获取睡眠汇总数据
     * 如果查询不到会尝试自动生成
     * 
     * @param queryDate 查询日期
     * @param userId 用户ID
     * @return 睡眠汇总响应
     */
    public SleepSummaryResponse getSleepSummaryByDateAndUser(LocalDate queryDate, String userId) {
        log.info("查询睡眠汇总: queryDate={}, userId={}", queryDate, userId);
        
        // 1. 先尝试从 sleep_summary 表查询
        LambdaQueryWrapper<SleepSummary> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SleepSummary::getQueryDate, queryDate)
                    .eq(SleepSummary::getUserId, userId);
        
        SleepSummary summary = this.getOne(queryWrapper);
        
        // 2. 如果没有找到，尝试自动生成
        if (summary == null) {
            log.info("未找到睡眠汇总，尝试自动生成: queryDate={}, userId={}", queryDate, userId);
            
            // 第一次尝试（基于现有 health_data）
            SleepReportGeneratorService.GenerateResult result = 
                    sleepReportGeneratorService.tryGenerateReport(userId, queryDate);
            
            if (result.isSuccess()) {
                summary = result.getSummary();
                log.info("自动生成睡眠报告成功: queryDate={}, userId={}", queryDate, userId);
            } else {
                // 生成失败，返回错误信息
                log.info("无法生成睡眠报告: {}", result.getMessage());
                return buildErrorResponse(queryDate, result.getMessage());
            }
        }
        
        // 3. 解析并构建响应
        return convertToResponse(summary);
    }
    
    private SleepSummaryResponse buildErrorResponse(LocalDate queryDate, String message) {
        return SleepSummaryResponse.builder()
                .queryDate(queryDate)
                .sampleCount(0)
                .lightSleepDurationMin(0.0)
                .deepSleepDurationMin(0.0)
                .remDurationMin(0.0)
                .totalSleepMin(0.0)
                .avgHeartRate(0.0)
                .avgBreathingRate(0.0)
                .wakeCount(0)
                .wakeStartTimes(new ArrayList<>())
                .message(message)
                .build();
    }
    
    private SleepSummaryResponse convertToResponse(SleepSummary summary) {
        List<LocalDateTime> wakeStartTimes = parseWakeStartTimes(summary.getWakeStartTimes());
        
        return SleepSummaryResponse.builder()
                .queryDate(summary.getQueryDate())
                .sleepTime(summary.getSleepTime())
                .wakeTime(summary.getWakeTime())
                .sampleCount(summary.getSampleCount() != null ? summary.getSampleCount() : 0)
                .lightSleepDurationMin(summary.getLightSleepDurationMin() != null ? summary.getLightSleepDurationMin() : 0.0)
                .deepSleepDurationMin(summary.getDeepSleepDurationMin() != null ? summary.getDeepSleepDurationMin() : 0.0)
                .remDurationMin(summary.getRemDurationMin() != null ? summary.getRemDurationMin() : 0.0)
                .totalSleepMin(summary.getTotalSleepMin() != null ? summary.getTotalSleepMin() : 0.0)
                .avgHeartRate(summary.getAvgHeartRate())
                .avgBreathingRate(summary.getAvgBreathingRate())
                .wakeCount(summary.getWakeCount() != null ? summary.getWakeCount() : 0)
                .wakeStartTimes(wakeStartTimes)
                .message(summary.getMessage())
                .build();
    }
    
    /**
     * 解析 wake_start_times JSON 字符串为 List<LocalDateTime>
     * 
     * @param json JSON 数组字符串，如 ["2026-01-16 03:20:00", "2026-01-16 05:10:00"]
     * @return LocalDateTime 列表
     */
    private List<LocalDateTime> parseWakeStartTimes(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(json, new TypeReference<List<LocalDateTime>>() {});
        } catch (Exception e) {
            log.warn("解析 wake_start_times 失败: {}, 原始值: {}", e.getMessage(), json);
            return new ArrayList<>();
        }
    }
    
    /**
     * 根据用户ID和日期范围获取睡眠报告列表
     * 
     * @param userId 用户ID
     * @param startDate 开始日期（包含）
     * @param endDate 结束日期（包含）
     * @return 睡眠汇总响应列表
     */
    public List<SleepSummaryResponse> getSleepSummariesByDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        log.info("查询睡眠汇总列表: userId={}, startDate={}, endDate={}", userId, startDate, endDate);
        
        List<SleepSummaryResponse> responses = new ArrayList<>();
        
        // 遍历每一天
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            try {
                // 调用单日查询方法（包含自动生成逻辑）
                // 如果生成失败，getSleepSummaryByDateAndUser 会返回包含错误信息的 Response
                // 我们通过检查 sampleCount > 0 来判断是否为有效报告
                SleepSummaryResponse response = getSleepSummaryByDateAndUser(currentDate, userId);
                
                if (response.getSampleCount() != null && response.getSampleCount() > 0) {
                    responses.add(response);
                } else {
                    log.debug("日期 {} 无有效睡眠报告，跳过", currentDate);
                }
            } catch (Exception e) {
                log.error("处理日期 {} 失败", currentDate, e);
            }
            currentDate = currentDate.plusDays(1);
        }
        
        if (responses.isEmpty()) {
            log.info("未找到睡眠汇总数据: userId={}, startDate={}, endDate={}", userId, startDate, endDate);
        } else {
            log.info("查询睡眠汇总列表成功: userId={}, 共 {} 条记录", userId, responses.size());
        }
        
        return responses;
    }
}

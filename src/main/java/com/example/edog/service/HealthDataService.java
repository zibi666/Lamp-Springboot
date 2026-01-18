package com.example.edog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.edog.dto.SleepSummaryResponse;
import com.example.edog.entity.HealthData;
import com.example.edog.enums.SleepStatusEnum;
import com.example.edog.mapper.HealthDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 健康数据服务类
 * 处理健康数据的业务逻辑
 */
@Slf4j
@Service
public class HealthDataService extends ServiceImpl<HealthDataMapper, HealthData> {
    
    /**
     * 每条记录代表的时长（分钟）
     * 设备每30秒上报一次数据，因此每条记录 = 0.5 分钟
     */
    private static final double SAMPLE_INTERVAL_MIN = 0.5;
    
    /**
     * 起床检测的体动阈值（可配置）
     * 当 motion_index >= 此阈值时，认为有"起床动作"
     * 默认值：30，可通过配置文件调整
     */
    @Value("${sleep.motion-threshold:30}")
    private float motionThreshold;
    
    /**
     * 默认用户ID
     */
    private static final String DEFAULT_USER_ID = "user123";
    
    /**
     * 保存健康数据
     * @param heartRate 心率
     * @param breathingRate 呼吸频率
     * @param sleepStatus 睡眠状态
     * @param motionIndex 体动强度指标
     * @param userId 用户ID（可选，默认 user123）
     * @return 保存的数据
     */
    public HealthData saveHealthData(Integer heartRate, Integer breathingRate, String sleepStatus, Float motionIndex, String userId) {
        HealthData healthData = new HealthData();
        healthData.setUserId(userId != null && !userId.trim().isEmpty() ? userId : DEFAULT_USER_ID);
        healthData.setHeartRate(heartRate);
        healthData.setBreathingRate(breathingRate);
        healthData.setSleepStatus(sleepStatus);
        healthData.setMotionIndex(motionIndex);
        healthData.setUploadTime(LocalDateTime.now());
        
        boolean success = this.save(healthData);
        
        if (success) {
            log.info("健康数据保存成功: userId={}, 心率={}, 呼吸频率={}, 体动={}, 睡眠状态={}, 时间={}", 
                healthData.getUserId(), heartRate, breathingRate, motionIndex, sleepStatus, healthData.getUploadTime());
        } else {
            log.error("健康数据保存失败");
        }
        
        return healthData;
    }
    
    /**
     * 保存健康数据（使用默认用户ID）
     */
    public HealthData saveHealthData(Integer heartRate, Integer breathingRate, String sleepStatus, Float motionIndex) {
        return saveHealthData(heartRate, breathingRate, sleepStatus, motionIndex, DEFAULT_USER_ID);
    }
    
    /**
     * 获取睡眠汇总数据
     * 
     * @param queryDate 查询日期
     * @param windowStart 窗口开始时间
     * @param windowEnd 窗口结束时间
     * @return 睡眠汇总响应
     */
    public SleepSummaryResponse getSleepSummary(LocalDate queryDate, LocalDateTime windowStart, LocalDateTime windowEnd) {
        log.info("开始查询睡眠汇总: queryDate={}, window=[{}, {}]", queryDate, windowStart, windowEnd);
        
        // 1. 查询窗口内的所有健康数据（按 upload_time 升序，利用索引）
        LambdaQueryWrapper<HealthData> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.ge(HealthData::getUploadTime, windowStart)
                    .lt(HealthData::getUploadTime, windowEnd)
                    .orderByAsc(HealthData::getUploadTime);
        
        List<HealthData> dataList = this.list(queryWrapper);
        
        // 2. 无数据时返回空结果
        if (dataList == null || dataList.isEmpty()) {
            log.info("查询窗口内无数据: queryDate={}", queryDate);
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
                    .message("该时间窗口内无睡眠数据记录")
                    .build();
        }
        
        // 3. 统计各项指标
        int sampleCount = dataList.size();
        int remCount = 0;
        int deepCount = 0;   // 深度睡眠计数（DEEP状态）
        int lightCount = 0;  // 浅度睡眠计数（LIGHT状态）
        long heartRateSum = 0;
        long breathingRateSum = 0;
        int validHeartRateCount = 0;
        int validBreathingRateCount = 0;
        
        LocalDateTime sleepTime = null;  // 首次进入睡眠的时间
        LocalDateTime wakeTime = null;   // 最后一次从睡眠转为清醒的时间
        List<LocalDateTime> wakeStartTimes = new ArrayList<>();  // 清醒段起始时间列表
        
        String prevStatus = null;  // 上一条记录的睡眠状态
        Float prevMotionIndex = null;  // 上一条记录的体动指数
        
        // 用于计算起床次数的状态机变量
        // 完整周期：睡眠→醒来→下床(高体动)→离床(体动=0)→上床(高体动)→重新入睡 = 计数+1
        int wakeCount = 0;
        
        /**
         * 起床检测状态机：
         * STATE_IDLE(0): 初始状态，等待从睡眠转为清醒
         * STATE_WOKE_UP(1): 已从睡眠(REM/NREM)转为清醒(WAKE)
         * STATE_GETTING_OFF_BED(2): 检测到下床高体动
         * STATE_LEFT_BED(3): 检测到离床(体动=0)
         * STATE_GETTING_ON_BED(4): 检测到上床高体动，等待重新入睡
         */
        int wakeState = 0;
        final int STATE_IDLE = 0;
        final int STATE_WOKE_UP = 1;
        final int STATE_GETTING_OFF_BED = 2;
        final int STATE_LEFT_BED = 3;
        final int STATE_GETTING_ON_BED = 4;
        
        for (int i = 0; i < dataList.size(); i++) {
            HealthData data = dataList.get(i);
            String currentStatus = data.getSleepStatus();
            Float currentMotion = data.getMotionIndex();
            
            // 3.1 统计 REM/DEEP/LIGHT 记录数
            if (SleepStatusEnum.REM.getValue().equalsIgnoreCase(currentStatus)) {
                remCount++;
            } else if (SleepStatusEnum.DEEP.getValue().equalsIgnoreCase(currentStatus)) {
                deepCount++;
            } else if (SleepStatusEnum.LIGHT.getValue().equalsIgnoreCase(currentStatus)) {
                lightCount++;
            }
            
            // 3.2 累加心率和呼吸频率（用于计算平均值）
            if (data.getHeartRate() != null && data.getHeartRate() > 0) {
                heartRateSum += data.getHeartRate();
                validHeartRateCount++;
            }
            if (data.getBreathingRate() != null && data.getBreathingRate() > 0) {
                breathingRateSum += data.getBreathingRate();
                validBreathingRateCount++;
            }
            
            // 3.3 检测入睡时间（首次进入 REM 或 NREM）
            if (sleepTime == null && SleepStatusEnum.isSleeping(currentStatus)) {
                sleepTime = data.getUploadTime();
                log.debug("检测到入睡时间: {}", sleepTime);
            }
            
            // 3.4 检测清醒段起始时间（从睡眠状态切换到 Wake）
            if (prevStatus != null && SleepStatusEnum.isSleeping(prevStatus) && SleepStatusEnum.isWake(currentStatus)) {
                wakeStartTimes.add(data.getUploadTime());
                wakeTime = data.getUploadTime();  // 更新最后清醒时间
                log.debug("检测到清醒段起始: {} (从 {} 转为 {})", data.getUploadTime(), prevStatus, currentStatus);
            }
            
            // 3.5 起床次数检测状态机
            // 状态转移：IDLE -> WOKE_UP（从睡眠转为清醒）
            if (wakeState == STATE_IDLE && prevStatus != null 
                    && SleepStatusEnum.isSleeping(prevStatus) && SleepStatusEnum.isWake(currentStatus)) {
                wakeState = STATE_WOKE_UP;
                log.debug("起床状态机: IDLE->WOKE_UP (从{}转为{})", prevStatus, currentStatus);
            }
            
            // 状态转移：WOKE_UP -> GETTING_OFF_BED（检测到下床高体动）
            if (wakeState == STATE_WOKE_UP && currentMotion != null && currentMotion >= motionThreshold) {
                wakeState = STATE_GETTING_OFF_BED;
                log.debug("起床状态机: WOKE_UP->GETTING_OFF_BED (体动={} >= 阈值={})", currentMotion, motionThreshold);
            }
            
            // 状态转移：GETTING_OFF_BED -> LEFT_BED（体动变为0，用户离床）
            if (wakeState == STATE_GETTING_OFF_BED && currentMotion != null && currentMotion == 0) {
                wakeState = STATE_LEFT_BED;
                log.debug("起床状态机: GETTING_OFF_BED->LEFT_BED (体动=0，用户离床)");
            }
            
            // 状态转移：LEFT_BED -> GETTING_ON_BED（检测到上床高体动）
            if (wakeState == STATE_LEFT_BED && currentMotion != null && currentMotion >= motionThreshold) {
                wakeState = STATE_GETTING_ON_BED;
                log.debug("起床状态机: LEFT_BED->GETTING_ON_BED (体动={} >= 阈值={})", currentMotion, motionThreshold);
            }
            
            // 状态转移：GETTING_ON_BED -> IDLE + 计数（用户重新入睡）
            if (wakeState == STATE_GETTING_ON_BED && SleepStatusEnum.isSleeping(currentStatus)) {
                wakeCount++;
                wakeState = STATE_IDLE;
                log.debug("起床状态机: GETTING_ON_BED->IDLE (重新入睡，完成一次完整起床周期，wakeCount={})", wakeCount);
            }
            
            // 异常情况处理：如果在中间状态时用户直接重新入睡（未完成完整周期），重置状态机
            // 这种情况可能是用户只是在床上翻身，不算真正起床
            if (wakeState > STATE_IDLE && wakeState < STATE_GETTING_ON_BED && SleepStatusEnum.isSleeping(currentStatus)) {
                log.debug("起床状态机: 状态{}被重置（用户未完成完整起床周期就重新入睡）", wakeState);
                wakeState = STATE_IDLE;
            }
            
            prevStatus = currentStatus;
            prevMotionIndex = currentMotion;
        }
        
        // 4. 计算时长和平均值
        double remDurationMin = remCount * SAMPLE_INTERVAL_MIN;
        double deepSleepDurationMin = deepCount * SAMPLE_INTERVAL_MIN;
        double lightSleepDurationMin = lightCount * SAMPLE_INTERVAL_MIN;
        double totalSleepMin = remDurationMin + deepSleepDurationMin + lightSleepDurationMin;
        
        // 平均心率和呼吸频率（单位：次/半分钟，与设备上报一致）
        double avgHeartRate = validHeartRateCount > 0 ? 
                (double) heartRateSum / validHeartRateCount : 0.0;
        double avgBreathingRate = validBreathingRateCount > 0 ? 
                (double) breathingRateSum / validBreathingRateCount : 0.0;
        
        // 5. 构建响应
        SleepSummaryResponse response = SleepSummaryResponse.builder()
                .queryDate(queryDate)
                .sleepTime(sleepTime)
                .wakeTime(wakeTime)
                .sampleCount(sampleCount)
                .lightSleepDurationMin(round2(lightSleepDurationMin))
                .deepSleepDurationMin(round2(deepSleepDurationMin))
                .remDurationMin(round2(remDurationMin))
                .totalSleepMin(round2(totalSleepMin))
                .avgHeartRate(round2(avgHeartRate))
                .avgBreathingRate(round2(avgBreathingRate))
                .wakeCount(wakeCount)
                .wakeStartTimes(wakeStartTimes)
                .message(null)
                .build();
        
        log.info("睡眠汇总统计完成: sampleCount={}, remMin={}, deepMin={}, lightMin={}, totalMin={}, wakeCount={}", 
                sampleCount, remDurationMin, deepSleepDurationMin, lightSleepDurationMin, totalSleepMin, wakeCount);
        
        return response;
    }
    
    /**
     * 保留两位小数
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

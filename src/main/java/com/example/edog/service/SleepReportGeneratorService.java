package com.example.edog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edog.entity.HealthData;
import com.example.edog.entity.SleepSummary;
import com.example.edog.enums.SleepStatusEnum;
import com.example.edog.mapper.HealthDataMapper;
import com.example.edog.mapper.SleepSummaryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 睡眠报告生成服务
 * 负责检测用户起床并自动生成睡眠质量报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SleepReportGeneratorService {
    
    private final HealthDataMapper healthDataMapper;
    private final SleepSummaryMapper sleepSummaryMapper;
    
    /**
     * JSON序列化工具
     */
    private final ObjectMapper objectMapper = createObjectMapper();
    
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    /**
     * 每条记录代表的时长（分钟）
     * 设备每30秒上报一次数据，因此每条记录 = 0.5 分钟
     */
    private static final double SAMPLE_INTERVAL_MIN = 0.5;
    
    /**
     * 起床检测的体动阈值
     */
    @Value("${sleep.motion-threshold:30}")
    private float motionThreshold;
    
    /**
     * 起床确认需要连续检测到的 Wake + motion_index=0 的记录数
     * 用于确认用户真正起床而非短暂清醒
     */
    @Value("${sleep.wake-confirm-count:5}")
    private int wakeConfirmCount;
    
    /**
     * 睡眠窗口起始小时（默认18点，即下午6点）
     */
    @Value("${sleep.window-start-hour:18}")
    private int windowStartHour;
    
    /**
     * 睡眠窗口结束小时（默认12点，即中午12点）
     */
    @Value("${sleep.window-end-hour:12}")
    private int windowEndHour;
    
    /**
     * 生成结果封装类
     */
    @Data
    public static class GenerateResult {
        private boolean success;
        private String message;
        private SleepSummary summary;
        
        public static GenerateResult success(SleepSummary summary) {
            GenerateResult result = new GenerateResult();
            result.setSuccess(true);
            result.setSummary(summary);
            return result;
        }
        
        public static GenerateResult fail(String message) {
            GenerateResult result = new GenerateResult();
            result.setSuccess(false);
            result.setMessage(message);
            return result;
        }
    }
    
    /**
     * 定时任务：每5分钟检查是否有用户起床，生成睡眠报告
     * 只在早上6点到中午12点之间执行（用户通常在此时段起床）
     */
    @Scheduled(cron = "0 */5 6-12 * * ?")
    public void scheduledCheckAndGenerateReports() {
        log.info("定时任务：开始检查用户起床状态并生成睡眠报告");
        checkAndGenerateReportsForAllUsers();
    }
    
    /**
     * 检查所有用户的起床状态并生成报告
     */
    public void checkAndGenerateReportsForAllUsers() {
        // 获取所有有健康数据的用户ID
        List<String> userIds = getDistinctUserIds();
        
        for (String userId : userIds) {
            try {
                checkAndGenerateReportForUser(userId);
            } catch (Exception e) {
                log.error("为用户 {} 生成睡眠报告失败", userId, e);
            }
        }
    }
    
    /**
     * 尝试为指定用户生成睡眠报告（供查询时自动调用）
     * 生成报告后删除已使用的原始健康数据，只保留睡眠汇总数据
     * 
     * @param userId 用户ID
     * @param queryDate 查询日期
     * @return 生成结果
     */
    @Transactional(rollbackFor = Exception.class)
    public GenerateResult tryGenerateReport(String userId, LocalDate queryDate) {
        log.info("尝试为用户 {} 生成 {} 的睡眠报告", userId, queryDate);
        
        // 计算睡眠窗口
        LocalDateTime windowStart = queryDate.atTime(windowStartHour, 0, 0);
        LocalDateTime windowEnd = queryDate.plusDays(1).atTime(windowEndHour, 0, 0);
        
        // 查询数据
        LambdaQueryWrapper<HealthData> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(HealthData::getUserId, userId)
                    .ge(HealthData::getUploadTime, windowStart)
                    .lt(HealthData::getUploadTime, windowEnd)
                    .orderByAsc(HealthData::getUploadTime);
        
        List<HealthData> dataList = healthDataMapper.selectList(queryWrapper);
        
        // 1. 检查是否有数据
        if (dataList == null || dataList.isEmpty()) {
            return GenerateResult.fail("该日期睡眠窗口内无健康数据记录");
        }
        
        // 2. 验证是否包含完整的睡眠周期 (WAKE -> NREM -> REM -> WAKE)
        String cycleValidation = validateCompleteSleepCycle(dataList);
        if (cycleValidation != null) {
            // 即使验证失败，也需要清理数据，避免残留无效数据
            LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(HealthData::getUserId, userId)
                        .ge(HealthData::getUploadTime, windowStart)
                        .lt(HealthData::getUploadTime, windowEnd);
            int deletedCount = healthDataMapper.delete(deleteWrapper);
            log.info("用户 {} 睡眠数据不完整（{}），已清理 {} 条相关数据", userId, cycleValidation, deletedCount);
            
            return GenerateResult.fail(cycleValidation);
        }
        
        // 3. 生成报告
        SleepSummary summary = generateSleepSummary(userId, queryDate, dataList);
        
        // 4. 检查睡眠时长是否过短（至少30分钟）
        if (summary.getTotalSleepMin() < 30) {
            // 同样需要清理数据
            LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(HealthData::getUserId, userId)
                         .ge(HealthData::getUploadTime, windowStart)
                         .lt(HealthData::getUploadTime, windowEnd);
            healthDataMapper.delete(deleteWrapper);
            
            return GenerateResult.fail("睡眠时间过短（少于30分钟），无法生成有效报告，已清理数据");
        }
        
        // 5. 保存到数据库
        LambdaQueryWrapper<SleepSummary> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(SleepSummary::getUserId, userId)
                     .eq(SleepSummary::getQueryDate, queryDate);
        SleepSummary existing = sleepSummaryMapper.selectOne(existsWrapper);
        
        if (existing != null) {
            summary.setId(existing.getId());
            sleepSummaryMapper.updateById(summary);
        } else {
            sleepSummaryMapper.insert(summary);
        }
        
        // 6. 删除已使用的健康数据，只保留睡眠汇总数据
        LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(HealthData::getUserId, userId)
                     .ge(HealthData::getUploadTime, windowStart)
                     .lt(HealthData::getUploadTime, windowEnd);
        int deletedCount = healthDataMapper.delete(deleteWrapper);
        log.info("已删除用户 {} 的 {} 条原始健康数据（时间范围: {} 至 {}）", 
                userId, deletedCount, windowStart, windowEnd);
        
        log.info("为用户 {} 生成睡眠报告成功: queryDate={}, totalSleepMin={}", 
                userId, queryDate, summary.getTotalSleepMin());
        
        return GenerateResult.success(summary);
    }
    
    /**
     * 验证数据是否包含完整的睡眠周期
     * 完整周期定义：WAKE -> 非REM睡眠(LIGHT/DEEP/NREM) -> REM -> WAKE
     * 
     * @param dataList 健康数据列表
     * @return 如果验证失败返回错误信息，成功返回null
     */
    private String validateCompleteSleepCycle(List<HealthData> dataList) {
        // 状态机：跟踪睡眠周期进度
        // 0: 初始/等待WAKE, 1: 已有WAKE等待非REM睡眠, 2: 已有非REM睡眠等待REM, 3: 已有REM等待WAKE, 4: 完整周期
        int cycleState = 0;
        boolean hasWake = false;
        boolean hasNonRemSleep = false;  // 包括 LIGHT/DEEP/NREM
        boolean hasRem = false;
        
        for (HealthData data : dataList) {
            String status = data.getSleepStatus();
            if (status == null) continue;
            
            // 判断是否为非REM睡眠状态（LIGHT/DEEP）
            boolean isNonRemSleep = SleepStatusEnum.LIGHT.getValue().equalsIgnoreCase(status) 
                    || SleepStatusEnum.DEEP.getValue().equalsIgnoreCase(status);
            
            switch (cycleState) {
                case 0: // 等待初始WAKE
                    if (SleepStatusEnum.isWake(status)) {
                        cycleState = 1;
                        hasWake = true;
                    }
                    break;
                    
                case 1: // 已有WAKE，等待非REM睡眠
                    if (isNonRemSleep) {
                        cycleState = 2;
                        hasNonRemSleep = true;
                    }
                    break;
                    
                case 2: // 已有非REM睡眠，等待REM
                    if (SleepStatusEnum.REM.getValue().equalsIgnoreCase(status)) {
                        cycleState = 3;
                        hasRem = true;
                    } else if (SleepStatusEnum.isWake(status)) {
                        // 还没进入REM就醒了，重新开始等待非REM睡眠
                        cycleState = 1;
                    }
                    break;
                    
                case 3: // 已有REM，等待最终WAKE
                    if (SleepStatusEnum.isWake(status)) {
                        cycleState = 4; // 完整周期！
                        log.debug("检测到完整睡眠周期: WAKE -> 非REM睡眠 -> REM -> WAKE");
                    } else if (isNonRemSleep) {
                        // REM后又进入非REM睡眠，继续等待REM
                        cycleState = 2;
                    }
                    break;
                    
                case 4: // 已有完整周期，继续检测更多周期（可选）
                    break;
            }
            
            if (cycleState == 4) {
                break; // 已找到完整周期，可以生成报告
            }
        }
        
        // 检查是否有完整周期
        if (cycleState < 4) {
            StringBuilder sb = new StringBuilder("睡眠数据不完整，缺少完整的睡眠周期。");
            sb.append("需要完整的 WAKE→非REM睡眠(LIGHT/DEEP)→REM→WAKE 周期。");
            sb.append("当前数据：");
            if (!hasWake) sb.append("缺少清醒(WAKE)状态；");
            if (!hasNonRemSleep) sb.append("缺少非REM睡眠(LIGHT/DEEP)状态；");
            if (!hasRem) sb.append("缺少REM睡眠状态；");
            if (cycleState == 3) sb.append("睡眠周期未完成（最终未检测到清醒）；");
            
            return sb.toString();
        }
        
        return null; // 验证通过
    }
    
    /**
     * 检查指定用户是否起床，如果起床则生成睡眠报告
     * 
     * @param userId 用户ID
     * @return 是否成功生成报告
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean checkAndGenerateReportForUser(String userId) {
        log.info("检查用户 {} 的起床状态", userId);
        
        // 1. 计算睡眠窗口（前一天18:00 到 今天12:00）
        LocalDate today = LocalDate.now();
        LocalDateTime windowStart = today.minusDays(1).atTime(windowStartHour, 0, 0);
        LocalDateTime windowEnd = today.atTime(windowEndHour, 0, 0);
        
        // 2. 查询该用户在睡眠窗口内的所有数据
        LambdaQueryWrapper<HealthData> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(HealthData::getUserId, userId)
                    .ge(HealthData::getUploadTime, windowStart)
                    .lt(HealthData::getUploadTime, windowEnd)
                    .orderByAsc(HealthData::getUploadTime);
        
        List<HealthData> dataList = healthDataMapper.selectList(queryWrapper);
        
        if (dataList == null || dataList.isEmpty()) {
            log.info("用户 {} 在睡眠窗口内无数据", userId);
            return false;
        }
        
        // 3. 检测用户是否已经起床
        if (!isUserWokenUp(dataList)) {
            log.info("用户 {} 尚未起床或数据不足以确认起床", userId);
            return false;
        }
        
        // 4. 验证是否有完整睡眠周期
        String cycleValidation = validateCompleteSleepCycle(dataList);
        if (cycleValidation != null) {
            log.info("用户 {} 睡眠数据不完整: {}", userId, cycleValidation);
            // 即使验证失败，也需要清理数据，避免残留无效数据
            LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(HealthData::getUserId, userId)
                         .ge(HealthData::getUploadTime, windowStart)
                         .lt(HealthData::getUploadTime, windowEnd);
            int deletedCount = healthDataMapper.delete(deleteWrapper);
            log.info("用户 {} 睡眠数据不完整（{}），已清理 {} 条相关数据", userId, cycleValidation, deletedCount);
            return false;
        }
        
        // 5. 计算睡眠报告的查询日期（取睡眠开始的日期）
        LocalDate queryDate = today.minusDays(1);  // 睡眠通常从前一天晚上开始
        
        // 6. 检查是否已经生成过该日期的报告
        LambdaQueryWrapper<SleepSummary> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(SleepSummary::getUserId, userId)
                     .eq(SleepSummary::getQueryDate, queryDate);
        if (sleepSummaryMapper.selectCount(existsWrapper) > 0) {
            log.info("用户 {} 的 {} 睡眠报告已存在，跳过生成", userId, queryDate);
            return false;
        }
        
        // 7. 生成睡眠报告
        SleepSummary summary = generateSleepSummary(userId, queryDate, dataList);
        
        // 8. 检查睡眠时长
        if (summary.getTotalSleepMin() < 30) {
            log.info("用户 {} 睡眠时间过短（{}分钟），跳过生成", userId, summary.getTotalSleepMin());
            // 同样需要清理数据
            LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(HealthData::getUserId, userId)
                         .ge(HealthData::getUploadTime, windowStart)
                         .lt(HealthData::getUploadTime, windowEnd);
            healthDataMapper.delete(deleteWrapper);
            return false;
        }
        
        // 9. 保存到 sleep_summary 表
        sleepSummaryMapper.insert(summary);
        log.info("用户 {} 的睡眠报告已保存: queryDate={}, totalSleepMin={}", 
                userId, queryDate, summary.getTotalSleepMin());
        
        // 10. 删除已使用的 health_data 数据
        LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(HealthData::getUserId, userId)
                     .ge(HealthData::getUploadTime, windowStart)
                     .lt(HealthData::getUploadTime, windowEnd);
        int deletedCount = healthDataMapper.delete(deleteWrapper);
        log.info("已删除用户 {} 的 {} 条健康数据", userId, deletedCount);
        
        return true;
    }
    
    /**
     * 手动触发为指定用户生成睡眠报告（忽略起床检测）
     * 
     * @param userId 用户ID
     * @param queryDate 查询日期
     * @return 生成的睡眠报告，无数据返回null
     */
    @Transactional(rollbackFor = Exception.class)
    public SleepSummary generateReportManually(String userId, LocalDate queryDate) {
        log.info("手动触发为用户 {} 生成 {} 的睡眠报告", userId, queryDate);
        
        // 计算睡眠窗口
        LocalDateTime windowStart = queryDate.atTime(windowStartHour, 0, 0);
        LocalDateTime windowEnd = queryDate.plusDays(1).atTime(windowEndHour, 0, 0);
        
        // 查询数据
        LambdaQueryWrapper<HealthData> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(HealthData::getUserId, userId)
                    .ge(HealthData::getUploadTime, windowStart)
                    .lt(HealthData::getUploadTime, windowEnd)
                    .orderByAsc(HealthData::getUploadTime);
        
        List<HealthData> dataList = healthDataMapper.selectList(queryWrapper);
        
        if (dataList == null || dataList.isEmpty()) {
            log.info("用户 {} 在 {} 睡眠窗口内无数据", userId, queryDate);
            return null;
        }
        
        // 检查是否已存在
        LambdaQueryWrapper<SleepSummary> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(SleepSummary::getUserId, userId)
                     .eq(SleepSummary::getQueryDate, queryDate);
        SleepSummary existing = sleepSummaryMapper.selectOne(existsWrapper);
        
        // 生成报告
        SleepSummary summary = generateSleepSummary(userId, queryDate, dataList);
        
        if (existing != null) {
            // 更新现有记录
            summary.setId(existing.getId());
            sleepSummaryMapper.updateById(summary);
            log.info("更新用户 {} 的 {} 睡眠报告", userId, queryDate);
        } else {
            // 插入新记录
            sleepSummaryMapper.insert(summary);
            log.info("创建用户 {} 的 {} 睡眠报告", userId, queryDate);
        }
        
        // 删除已使用的数据
        LambdaQueryWrapper<HealthData> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(HealthData::getUserId, userId)
                     .ge(HealthData::getUploadTime, windowStart)
                     .lt(HealthData::getUploadTime, windowEnd);
        int deletedCount = healthDataMapper.delete(deleteWrapper);
        log.info("已删除用户 {} 的 {} 条健康数据", userId, deletedCount);
        
        return summary;
    }
    
    /**
     * 检测用户是否已经起床
     * 起床条件：REM -> Wake 转移 + 高体动 + motion_index=0 持续一段时间
     */
    private boolean isUserWokenUp(List<HealthData> dataList) {
        if (dataList.size() < wakeConfirmCount) {
            return false;
        }
        
        boolean remToWakeDetected = false;
        boolean highMotionDetected = false;
        int zeroMotionCount = 0;
        String prevStatus = null;
        
        for (HealthData data : dataList) {
            String currentStatus = data.getSleepStatus();
            
            // 检测 REM -> Wake 转移
            if (prevStatus != null && 
                SleepStatusEnum.REM.getValue().equalsIgnoreCase(prevStatus) && 
                SleepStatusEnum.isWake(currentStatus)) {
                remToWakeDetected = true;
                highMotionDetected = false;
                zeroMotionCount = 0;
            }
            
            // 在 Wake 状态下检测体动模式
            if (remToWakeDetected && SleepStatusEnum.isWake(currentStatus)) {
                Float motionIndex = data.getMotionIndex();
                if (motionIndex != null) {
                    if (!highMotionDetected && motionIndex >= motionThreshold) {
                        highMotionDetected = true;
                    } else if (highMotionDetected && motionIndex == 0) {
                        zeroMotionCount++;
                        // 连续检测到足够多的 motion_index=0，确认起床
                        if (zeroMotionCount >= wakeConfirmCount) {
                            log.info("确认用户已起床: REM->Wake + 高体动 + {}次连续零体动", zeroMotionCount);
                            return true;
                        }
                    } else if (highMotionDetected && motionIndex > 0) {
                        // 体动恢复，重置计数（用户可能只是翻身后又起来活动）
                        zeroMotionCount = 0;
                    }
                }
            }
            
            // 如果重新进入睡眠，重置状态
            if (SleepStatusEnum.isSleeping(currentStatus)) {
                remToWakeDetected = false;
                highMotionDetected = false;
                zeroMotionCount = 0;
            }
            
            prevStatus = currentStatus;
        }
        
        return false;
    }
    
    /**
     * 生成睡眠汇总数据
     */
    private SleepSummary generateSleepSummary(String userId, LocalDate queryDate, List<HealthData> dataList) {
        int sampleCount = dataList.size();
        int remCount = 0;
        int deepCount = 0;   // 深度睡眠计数（DEEP状态）
        int lightCount = 0;  // 浅度睡眠计数（LIGHT状态）
        long heartRateSum = 0;
        long breathingRateSum = 0;
        int validHeartRateCount = 0;
        int validBreathingRateCount = 0;
        
        LocalDateTime sleepTime = null;
        LocalDateTime wakeTime = null;
        List<LocalDateTime> wakeStartTimes = new ArrayList<>();
        
        String prevStatus = null;
        
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
        
        for (HealthData data : dataList) {
            String currentStatus = data.getSleepStatus();
            Float currentMotion = data.getMotionIndex();
            
            // 统计 REM/DEEP/LIGHT 记录数
            if (SleepStatusEnum.REM.getValue().equalsIgnoreCase(currentStatus)) {
                remCount++;
            } else if (SleepStatusEnum.DEEP.getValue().equalsIgnoreCase(currentStatus)) {
                deepCount++;
            } else if (SleepStatusEnum.LIGHT.getValue().equalsIgnoreCase(currentStatus)) {
                lightCount++;
            }
            
            // 累加心率和呼吸频率
            if (data.getHeartRate() != null && data.getHeartRate() > 0) {
                heartRateSum += data.getHeartRate();
                validHeartRateCount++;
            }
            if (data.getBreathingRate() != null && data.getBreathingRate() > 0) {
                breathingRateSum += data.getBreathingRate();
                validBreathingRateCount++;
            }
            
            // 检测入睡时间
            if (sleepTime == null && SleepStatusEnum.isSleeping(currentStatus)) {
                sleepTime = data.getUploadTime();
            }
            
            // 检测清醒段起始时间
            if (prevStatus != null && SleepStatusEnum.isSleeping(prevStatus) && SleepStatusEnum.isWake(currentStatus)) {
                wakeStartTimes.add(data.getUploadTime());
                wakeTime = data.getUploadTime();
            }
            
            // 起床次数检测状态机
            // 状态转移：IDLE -> WOKE_UP（从睡眠转为清醒）
            if (wakeState == STATE_IDLE && prevStatus != null 
                    && SleepStatusEnum.isSleeping(prevStatus) && SleepStatusEnum.isWake(currentStatus)) {
                wakeState = STATE_WOKE_UP;
            }
            
            // 状态转移：WOKE_UP -> GETTING_OFF_BED（检测到下床高体动）
            if (wakeState == STATE_WOKE_UP && currentMotion != null && currentMotion >= motionThreshold) {
                wakeState = STATE_GETTING_OFF_BED;
            }
            
            // 状态转移：GETTING_OFF_BED -> LEFT_BED（体动变为0，用户离床）
            if (wakeState == STATE_GETTING_OFF_BED && currentMotion != null && currentMotion == 0) {
                wakeState = STATE_LEFT_BED;
            }
            
            // 状态转移：LEFT_BED -> GETTING_ON_BED（检测到上床高体动）
            if (wakeState == STATE_LEFT_BED && currentMotion != null && currentMotion >= motionThreshold) {
                wakeState = STATE_GETTING_ON_BED;
            }
            
            // 状态转移：GETTING_ON_BED -> IDLE + 计数（用户重新入睡）
            if (wakeState == STATE_GETTING_ON_BED && SleepStatusEnum.isSleeping(currentStatus)) {
                wakeCount++;
                wakeState = STATE_IDLE;
            }
            
            // 异常情况处理：如果在中间状态时用户直接重新入睡（未完成完整周期），重置状态机
            if (wakeState > STATE_IDLE && wakeState < STATE_GETTING_ON_BED && SleepStatusEnum.isSleeping(currentStatus)) {
                wakeState = STATE_IDLE;
            }
            
            prevStatus = currentStatus;
        }
        
        // 计算时长和平均值
        double remDurationMin = remCount * SAMPLE_INTERVAL_MIN;
        double deepSleepDurationMin = deepCount * SAMPLE_INTERVAL_MIN;
        double lightSleepDurationMin = lightCount * SAMPLE_INTERVAL_MIN;
        double totalSleepMin = remDurationMin + deepSleepDurationMin + lightSleepDurationMin;
        
        double avgHeartRate = validHeartRateCount > 0 ? 
                (double) heartRateSum / validHeartRateCount : 0.0;
        double avgBreathingRate = validBreathingRateCount > 0 ? 
                (double) breathingRateSum / validBreathingRateCount : 0.0;
        
        // 构建 SleepSummary 实体
        SleepSummary summary = new SleepSummary();
        summary.setUserId(userId);
        summary.setQueryDate(queryDate);
        summary.setSleepTime(sleepTime);
        summary.setWakeTime(wakeTime);
        summary.setSampleCount(sampleCount);
        summary.setLightSleepDurationMin(round2(lightSleepDurationMin));
        summary.setDeepSleepDurationMin(round2(deepSleepDurationMin));
        summary.setRemDurationMin(round2(remDurationMin));
        summary.setTotalSleepMin(round2(totalSleepMin));
        summary.setAvgHeartRate(round2(avgHeartRate));
        summary.setAvgBreathingRate(round2(avgBreathingRate));
        summary.setWakeCount(wakeCount);
        summary.setWakeStartTimes(serializeWakeStartTimes(wakeStartTimes));
        summary.setMessage(null);
        
        return summary;
    }
    
    /**
     * 获取所有有健康数据的不同用户ID
     */
    private List<String> getDistinctUserIds() {
        LambdaQueryWrapper<HealthData> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(HealthData::getUserId)
                    .groupBy(HealthData::getUserId);
        
        List<HealthData> results = healthDataMapper.selectList(queryWrapper);
        return results.stream()
                .map(HealthData::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }
    
    /**
     * 序列化清醒时间列表为JSON字符串
     */
    private String serializeWakeStartTimes(List<LocalDateTime> wakeStartTimes) {
        if (wakeStartTimes == null || wakeStartTimes.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(wakeStartTimes);
        } catch (JsonProcessingException e) {
            log.warn("序列化 wakeStartTimes 失败", e);
            return "[]";
        }
    }
    
    /**
     * 保留两位小数
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

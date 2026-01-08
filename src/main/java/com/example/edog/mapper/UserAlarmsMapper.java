package com.example.edog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edog.entity.UserAlarms;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 用户闹钟数据库操作接口
 */
@Mapper
public interface UserAlarmsMapper extends BaseMapper<UserAlarms> {
    
    /**
     * 获取用户所有启用的闹钟按时间排序
     *
     * @param userId 用户ID
     * @return 闹钟列表
     */
    List<UserAlarms> selectUserAlarmsByUserId(@Param("userId") String userId);
    
    /**
     * 获取指定时间范围内用户的所有闹钟
     *
     * @param userId 用户ID
     * @param startTime 范围开始时间 (HH:MM:SS)
     * @param endTime 范围结束时间 (HH:MM:SS)
     * @return 闹钟列表
     */
    List<UserAlarms> selectAlarmsByTimeRange(
            @Param("userId") String userId,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime
    );
    /**
     * 检查重复闹钟
     *
     * @param userId 用户ID
     * @param alarmTime 闹钟时间
     * @param type 类型
     * @param targetDate 目标日期（仅单次闹钟有效）
     * @return 闹钟对象
     */
    UserAlarms selectDuplicateAlarm(
            @Param("userId") String userId,
            @Param("alarmTime") LocalTime alarmTime,
            @Param("type") Integer type,
            @Param("targetDate") LocalDate targetDate
    );
}

package com.example.edog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edog.entity.SleepSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 睡眠汇总数据Mapper接口
 * 继承MyBatis-Plus的BaseMapper，提供基础的CRUD操作
 */
@Mapper
public interface SleepSummaryMapper extends BaseMapper<SleepSummary> {
    // MyBatis-Plus 已提供基础CRUD方法
}

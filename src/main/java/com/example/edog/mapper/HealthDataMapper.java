package com.example.edog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edog.entity.HealthData;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健康数据Mapper接口
 * 继承MyBatis-Plus的BaseMapper，提供基础的CRUD操作
 */
@Mapper
public interface HealthDataMapper extends BaseMapper<HealthData> {
    // MyBatis-Plus 已提供基础CRUD方法，无需额外定义
    // 如需自定义SQL，可在此添加方法并使用@Select、@Insert等注解
}

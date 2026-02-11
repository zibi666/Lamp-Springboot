package com.example.edog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edog.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户数据库操作接口。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按账号查询用户。
     *
     * @param phone 账号
     * @return 用户实体
     */
    User selectByPhone(@Param("phone") String phone);

    /**
     * 按对外 userId 查询用户。
     *
     * @param userId 对外用户ID
     * @return 用户实体
     */
    User selectByUserId(@Param("userId") String userId);

    /**
     * 检查 userId 是否已存在。
     *
     * @param userId 对外用户ID
     * @return 数量
     */
    Integer countByUserId(@Param("userId") String userId);

    /**
     * 查询当前六位数字 userId 的最大值。
     *
     * @return 最大 userId（数字）
     */
    Integer selectMaxNumericUserId();
}

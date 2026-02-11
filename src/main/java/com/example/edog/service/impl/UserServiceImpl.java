package com.example.edog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.edog.dto.AuthLoginRequest;
import com.example.edog.dto.AuthRegisterRequest;
import com.example.edog.dto.UserProfileResponse;
import com.example.edog.dto.UserProfileUpdateRequest;
import com.example.edog.entity.User;
import com.example.edog.mapper.UserMapper;
import com.example.edog.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 用户业务实现。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Pattern BCRYPT_HASH_PATTERN = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final int USER_ID_RETRY_TIMES = 20;
    private static final int USER_ID_MIN = 1;
    private static final int USER_ID_MAX = 999999;
    private static final int NICKNAME_MAX_LENGTH = 64;
    private static final int AVATAR_URL_MAX_LENGTH = 512;
    private static final int WX_OPENID_MAX_LENGTH = 64;
    private static final String DEFAULT_AVATAR_PATH = "/images/avatars/avatar-01.svg";

    @Override
    public UserProfileResponse register(AuthRegisterRequest request) {
        String phone = normalize(request == null ? null : request.getPhone());
        String password = normalize(request == null ? null : request.getPassword());

        if (!StringUtils.hasText(phone)) {
            throw new IllegalArgumentException("请输入账号");
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("请设置密码");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("密码至少6位");
        }

        User existed = baseMapper.selectByPhone(phone);
        if (existed != null) {
            throw new IllegalArgumentException("该账号已注册");
        }

        for (int attempt = 0; attempt < USER_ID_RETRY_TIMES; attempt++) {
            User user = new User();
            user.setUserId(generateUniqueUserId());
            user.setPhone(phone);
            user.setPassword(PASSWORD_ENCODER.encode(password));
            user.setNickname(defaultNickname(request == null ? null : request.getNickname()));
            user.setAvatarUrl(defaultAvatarPath(normalize(request == null ? null : request.getAvatarUrl())));
            user.setWxOpenid(normalize(request == null ? null : request.getWxOpenid()));

            try {
                boolean saved = save(user);
                if (saved) {
                    return toProfile(user);
                }
            } catch (DuplicateKeyException e) {
                User raceUser = baseMapper.selectByPhone(phone);
                if (raceUser != null) {
                    throw new IllegalArgumentException("该账号已注册");
                }
                // user_id 并发冲突时重试
                log.warn("用户ID冲突，重试中，attempt={}", attempt + 1);
            }
        }

        throw new IllegalStateException("用户ID生成失败，请稍后重试");
    }

    @Override
    public UserProfileResponse login(AuthLoginRequest request) {
        String phone = normalize(request == null ? null : request.getPhone());
        String password = normalize(request == null ? null : request.getPassword());

        if (!StringUtils.hasText(phone)) {
            throw new IllegalArgumentException("请输入账号");
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("请输入密码");
        }

        User user = baseMapper.selectByPhone(phone);
        if (user == null || !matchPassword(password, user)) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        return toProfile(user);
    }

    @Override
    public UserProfileResponse getProfileByUserId(String userId) {
        String normalizedUserId = normalize(userId);
        if (!StringUtils.hasText(normalizedUserId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        User user = baseMapper.selectByUserId(normalizedUserId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return toProfile(user);
    }

    @Override
    public UserProfileResponse updateProfile(String userId, UserProfileUpdateRequest request) {
        String normalizedUserId = normalize(userId);
        if (!StringUtils.hasText(normalizedUserId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (request == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        User user = baseMapper.selectByUserId(normalizedUserId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        String nickname = normalize(request.getNickname());
        String avatarUrl = normalize(request.getAvatarUrl());
        String wxOpenid = normalize(request.getWxOpenid());

        if (nickname != null && nickname.length() > NICKNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("昵称长度不能超过64");
        }
        if (avatarUrl != null && avatarUrl.length() > AVATAR_URL_MAX_LENGTH) {
            throw new IllegalArgumentException("头像链接长度不能超过512");
        }
        if (avatarUrl != null) {
            validateAvatarUrl(avatarUrl);
        }
        if (wxOpenid != null && wxOpenid.length() > WX_OPENID_MAX_LENGTH) {
            throw new IllegalArgumentException("微信OpenID长度不能超过64");
        }

        // 仅更新前端传入字段，未传字段保持原值
        if (request.getNickname() != null) {
            user.setNickname(defaultNickname(nickname));
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(defaultAvatarPath(avatarUrl));
        }
        if (request.getWxOpenid() != null) {
            user.setWxOpenid(wxOpenid);
        }

        boolean updated = updateById(user);
        if (!updated) {
            throw new IllegalStateException("更新失败，请稍后重试");
        }
        return toProfile(user);
    }

    /**
     * 组装返回给前端的用户资料。
     */
    private UserProfileResponse toProfile(User user) {
        return UserProfileResponse.builder()
                .userId(user.getUserId())
                .phone(user.getPhone())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .wxOpenid(user.getWxOpenid())
                .build();
    }

    private String defaultNickname(String nickname) {
        String value = normalize(nickname);
        return StringUtils.hasText(value) ? value : "新用户";
    }

    private String defaultAvatarPath(String avatarUrl) {
        return StringUtils.hasText(avatarUrl) ? avatarUrl : DEFAULT_AVATAR_PATH;
    }

    /**
     * 兼容老数据的密码校验：
     * 1) 新数据：BCrypt 哈希校验
     * 2) 老数据：明文比对，成功后自动升级为 BCrypt
     */
    private boolean matchPassword(String rawPassword, User user) {
        String storedPassword = user.getPassword();
        if (!StringUtils.hasText(storedPassword)) {
            return false;
        }

        if (BCRYPT_HASH_PATTERN.matcher(storedPassword).matches()) {
            return PASSWORD_ENCODER.matches(rawPassword, storedPassword);
        }

        if (!storedPassword.equals(rawPassword)) {
            return false;
        }

        user.setPassword(PASSWORD_ENCODER.encode(rawPassword));
        boolean upgraded = updateById(user);
        if (!upgraded) {
            log.warn("用户密码升级为BCrypt失败，userId={}", user.getUserId());
        }
        return true;
    }

    /**
     * 生成规则化六位数字 userId（000001~999999）。
     */
    private String generateUniqueUserId() {
        Integer maxUserId = baseMapper.selectMaxNumericUserId();
        int next = (maxUserId == null ? USER_ID_MIN : maxUserId + 1);
        if (next > USER_ID_MAX) {
            throw new IllegalStateException("用户ID已达到上限");
        }
        return String.format("%06d", next);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 头像地址支持站内相对路径和 http/https 远程链接。
     */
    private void validateAvatarUrl(String avatarUrl) {
        if (!StringUtils.hasText(avatarUrl)) {
            return;
        }
        if (avatarUrl.startsWith("/")) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(avatarUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("头像链接格式不正确，仅支持 http/https 或站内相对路径");
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean validScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!validScheme || !StringUtils.hasText(host)) {
            throw new IllegalArgumentException("头像链接格式不正确，仅支持 http/https 或站内相对路径");
        }
    }
}

package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.mapper.UserMapper;
import com.sygzcd.seckillmall.service.impl.UserCacheService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl.login 纯单元测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplLoginTest {

    @InjectMocks
    private UserServiceImpl service;

    @Mock
    private UserMapper userMapper;

    @Mock
    private HttpSession session;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private UserCacheService userCacheService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ================================================================
    // 场景 1：admin 登录 → set isAdmin=true
    // ================================================================

    @Nested
    @DisplayName("admin 登录")
    class AdminLogin {

        @Test
        @DisplayName("admin 登录 → 设 isAdmin=true，不调 removeAttribute")
        void adminSetsIsAdminTrue() {
            User admin = new User();
            admin.setId(1L);
            admin.setUsername("admin");
            admin.setPassword(encoder.encode("password"));
            when(userMapper.selectByUsername("admin")).thenReturn(admin);
            when(session.getId()).thenReturn("session-id-123");

            service.login("admin", "password");

            verify(session).setAttribute("userId", 1L);
            verify(session).setAttribute("isAdmin", true);
            verify(session, never()).removeAttribute("isAdmin");
            verify(valueOps).set(eq("login:session:1"), eq("session-id-123"), eq(24L), eq(TimeUnit.HOURS));
            verify(userCacheService).putUser(admin);
        }
    }

    // ================================================================
    // 场景 2：非 admin 登录 → removeAttribute("isAdmin")
    // ================================================================

    @Nested
    @DisplayName("非 admin 登录")
    class NormalUserLogin {

        @Test
        @DisplayName("非 admin 登录 → 显式 remove isAdmin（防止同 Session 残留）")
        void normalUserRemovesIsAdmin() {
            User user = new User();
            user.setId(2L);
            user.setUsername("alice");
            user.setPassword(encoder.encode("password"));
            when(userMapper.selectByUsername("alice")).thenReturn(user);
            when(session.getId()).thenReturn("session-id-456");

            service.login("alice", "password");

            verify(session).setAttribute("userId", 2L);
            verify(session, never()).setAttribute(eq("isAdmin"), any());
            verify(session).removeAttribute("isAdmin");
        }

        @Test
        @DisplayName("同一 Session：admin 登录后不登出直接登普通账号 → isAdmin 应被清除")
        void sameSessionAdminThenNormalUser() {
            User admin = new User();
            admin.setId(1L);
            admin.setUsername("admin");
            admin.setPassword(encoder.encode("password"));
            when(userMapper.selectByUsername("admin")).thenReturn(admin);
            when(session.getId()).thenReturn("session-id-shared");

            service.login("admin", "password");
            verify(session).setAttribute("isAdmin", true);

            // 重置 mock 计数
            reset(session);
            when(session.getId()).thenReturn("session-id-shared");

            User normal = new User();
            normal.setId(2L);
            normal.setUsername("bob");
            normal.setPassword(encoder.encode("password"));
            when(userMapper.selectByUsername("bob")).thenReturn(normal);

            service.login("bob", "password");

            verify(session).removeAttribute("isAdmin");
        }
    }

    // ================================================================
    // 场景 3：登录失败
    // ================================================================

    @Nested
    @DisplayName("登录失败路径")
    class LoginFailed {

        @Test
        @DisplayName("用户名不存在 → 抛 LOGIN_FAIL(2002)，不写入 Session")
        void userNotFoundThrows() {
            when(userMapper.selectByUsername("ghost")).thenReturn(null);

            assertThatThrownBy(() -> service.login("ghost", "password"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(ResultCode.LOGIN_FAIL.getCode());
                    });

            verifyNoInteractions(session);
        }

        @Test
        @DisplayName("密码错误 → 抛 LOGIN_FAIL(2002)，不写入 Session")
        void wrongPasswordThrows() {
            User user = new User();
            user.setId(1L);
            user.setUsername("alice");
            user.setPassword(encoder.encode("correct"));
            when(userMapper.selectByUsername("alice")).thenReturn(user);

            assertThatThrownBy(() -> service.login("alice", "wrong")).isInstanceOf(BusinessException.class);

            verifyNoInteractions(session);
        }
    }
}

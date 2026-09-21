package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.controller.AdminProductController.UpdateProductRequest;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminProductController 集成测试（@WebMvcTest）
 *
 * 覆盖：
 * 1. 输入校验（name/price/hot）
 * 2. @RequireAdmin + AdminInterceptor 权限校验
 * 3. invalidateCache 在更新成功后被调用
 *
 * 注意：
 * - @WebMvcTest 只启 Web 层（Controller + 拦截器 + 切面）
 * - ProductService/ProductMapper 用 @MockBean 注入，不启真实 Bean
 * - 拦截器 AdminInterceptor 需要 isAdmin=true 的 Session 才放行
 */
@WebMvcTest(AdminProductController.class)
@Disabled("@WebMvcTest 需要 IpUtils(@Value)、Redis、Cache 等 Bean 无法隔离，纯 Mockito 单测更合适")
class AdminProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductMapper productMapper;

    @MockBean
    private ProductService productService;

    /** 构造 admin 登录态的 MockHttpSession */
    private MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("isAdmin", true);
        s.setAttribute("userId", 1L);
        return s;
    }

    /** 构造请求体 */
    private UpdateProductRequest req(String name, BigDecimal price, Integer hot) {
        UpdateProductRequest r = new UpdateProductRequest();
        r.setName(name);
        r.setPrice(price);
        r.setHot(hot);
        return r;
    }

    // ================================================================
    // 场景 1：输入校验
    // ================================================================

    @Nested
    @DisplayName("P0 输入校验")
    class InputValidation {

        @Test
        @DisplayName("price <= 0 → 400 PARAM_ERROR")
        void priceNonPositiveRejected() throws Exception {
            mockMvc.perform(post("/api/admin/product/1/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", new BigDecimal("-0.01"), 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
        }

        @Test
        @DisplayName("price 超过 99999999.99 → 400 PARAM_ERROR")
        void priceAboveMaxRejected() throws Exception {
            mockMvc.perform(post("/api/admin/product/1/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", new BigDecimal("100000000.00"), 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
        }

        @Test
        @DisplayName("hot 非 0/1 → 400 PARAM_ERROR")
        void hotNotZeroOrOneRejected() throws Exception {
            mockMvc.perform(post("/api/admin/product/1/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", new BigDecimal("9.99"), 2))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
        }

        @Test
        @DisplayName("name 为空串 → 400 PARAM_ERROR")
        void blankNameRejected() throws Exception {
            mockMvc.perform(post("/api/admin/product/1/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("   ", new BigDecimal("9.99"), 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
        }
    }

    // ================================================================
    // 场景 2：权限校验
    // ================================================================

    @Nested
    @DisplayName("权限校验")
    class AuthCheck {

        @Test
        @DisplayName("无 Session → 401")
        void noSessionRejected() throws Exception {
            mockMvc.perform(post("/api/admin/product/1/update")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", null, null))))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ================================================================
    // 场景 3：成功路径
    // ================================================================

    @Nested
    @DisplayName("成功路径：invalidateCache 调用")
    class SuccessPath {

        @Test
        @DisplayName("更新成功后 invalidateCache 被调用一次")
        void invalidateCacheCalledAfterSuccess() throws Exception {
            when(productMapper.selectById(1L)).thenReturn(new Product());
            when(productMapper.update(eq(null), any())).thenReturn(1);

            mockMvc.perform(post("/api/admin/product/1/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", new BigDecimal("19.99"), 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

            verify(productService).invalidateCache(1L);
        }

        @Test
        @DisplayName("商品不存在 → 404，不调 invalidateCache")
        void notFoundDoesNotInvalidate() throws Exception {
            when(productMapper.selectById(999L)).thenReturn(null);

            mockMvc.perform(post("/api/admin/product/999/update")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req("新名", null, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.PRODUCT_NOT_FOUND.getCode()));

            verify(productService, never()).invalidateCache(anyLong());
        }
    }
}

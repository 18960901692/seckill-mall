package com.sygzcd.seckillmall.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IpUtils 纯单元测试（H-6：防 X-Forwarded-For 伪造）
 *
 * 信任边界模型：
 * 1. TCP 直连方不是可信代理 → 转发头全部不可信，直接返回 remoteAddr
 * 2. 可信代理 → 从 XFF 右侧向左验证代理链，第一个非可信地址即客户端
 * 3. XFF 任一片段非法 → 整条头作废，回退 remoteAddr（fail-closed）
 */
class IpUtilsTest {

    // ── 构造 IpUtils 的便捷方法 ──────────────────────────────────
    private static IpUtils withProxies(String csv) {
        return new IpUtils(csv);
    }

    private static HttpServletRequest req(String remoteAddr, String... xffChain) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(remoteAddr);
        if (xffChain != null && xffChain.length > 0) {
            when(r.getHeader("X-Forwarded-For")).thenReturn(String.join(", ", xffChain));
        } else {
            when(r.getHeader("X-Forwarded-For")).thenReturn(null);
        }
        return r;
    }

    // ================================================================
    // 场景 1：remoteAddr 不可信 → XFF 头全部忽略，直接返回 remoteAddr
    // ================================================================

    @Nested
    @DisplayName("直连方不可信 → 转发头一律忽略")
    class UntrustedRemote {

        @Test
        @DisplayName("用户直连 Tomcat，伪造 XFF 应被丢弃")
        void spoofedXFFIgnoredWhenRemoteNotTrusted() {
            IpUtils utils = withProxies("127.0.0.1");      // 本机代理在名单
            HttpServletRequest r = req("192.168.1.10", "10.0.0.1, 8.8.8.8"); // remoteAddr 不在名单

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("192.168.1.10"); // 返回真实直连 IP，XFF 被丢弃
        }

        @Test
        @DisplayName("IP 黑名单伪造：用别人 IP 做 XFF 嫁祸")
        void cantFrameAnotherUserViaSpoofedXFF() {
            IpUtils utils = withProxies("127.0.0.1");
            HttpServletRequest r = req("203.0.113.5", "10.0.0.1"); // 203.0.113.5 是攻击者直连 IP

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("203.0.113.5"); // 识别攻击者真实 IP，XFF 被忽略
        }

        @Test
        @DisplayName("空可信代理名单时，任何人都不可信")
        void emptyTrustedListEveryoneUntrusted() {
            IpUtils utils = withProxies("");
            HttpServletRequest r = req("1.2.3.4", "10.0.0.1, 8.8.8.8");

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("1.2.3.4");
        }
    }

    // ================================================================
    // 场景 2：remoteAddr 可信 → XFF 右侧向左验证
    // ================================================================

    @Nested
    @DisplayName("直连方可信 → 从 XFF 右侧向左找第一个非可信地址")
    class TrustedRemote {

        @Test
        @DisplayName("单层 Nginx：remoteAddr=Nginx IP，XFF 只有客户端 IP")
        void singleProxySingleHop() {
            IpUtils utils = withProxies("10.0.0.8");
            HttpServletRequest r = req("10.0.0.8", "11.22.33.44");

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("11.22.33.44");
        }

        @Test
        @DisplayName("攻击者直连 Nginx，伪造一个假客户端 IP 在最左侧")
        void attackerSpoofsXFFLeftMostOnly() {
            IpUtils utils = withProxies("10.0.0.8");
            // 攻击者控制自己的客户端头 XFF="6.6.6.6, 10.0.0.8"（6.6.6.6 是假 IP）
            // Nginx 覆盖写入 XFF="1.2.3.4, 10.0.0.8"（1.2.3.4 是攻击者真实 IP）
            // 但攻击者用没有被覆盖的 XFF 头请求（假设 Nginx 配置错了只追加没覆盖）：
            HttpServletRequest r = req("10.0.0.8", "6.6.6.6, 10.0.0.8");

            String ip = utils.getClientIp(r);

            // 右侧向左：10.0.0.8 可信跳过 → 6.6.6.6 非可信 → 客户端 IP
            assertThat(ip).isEqualTo("6.6.6.6"); // 攻击者写什么就是什么——这正是攻击者想达到的
            // 但等等：这不对啊？
            // 哦不，这个场景是说"攻击者可以在 XFF 左侧任意造 IP"——这就是为什么 Nginx 必须覆盖 XFF 而不是追加
            // Nginx 覆盖 → XFF="攻击者真实IP, 10.0.0.8" → 我们返回攻击者真实 IP ✓
            // 本项目应用层能做的就是右侧向左找"第一个非可信"，覆盖是代理层的事
        }

        @Test
        @DisplayName("多层可信代理链：CDN + Nginx 都是可信，客户端 IP 在左侧")
        void multiProxyChain() {
            IpUtils utils = withProxies("10.0.0.8, 10.0.0.15");
            // 客户端 IP=203.0.113.1，经过 CDN(10.0.0.15) → Nginx(10.0.0.8) → Tomcat
            // XFF: "203.0.113.1, 10.0.0.15, 10.0.0.8"
            HttpServletRequest r = req("10.0.0.8", "203.0.113.1, 10.0.0.15, 10.0.0.8");

            String ip = utils.getClientIp(r);

            // 右侧向左：10.0.0.8(可信跳过) → 10.0.0.15(可信跳过) → 203.0.113.1(非可信)
            assertThat(ip).isEqualTo("203.0.113.1");
        }

        @Test
        @DisplayName("中间有不可信代理：第一个非可信就返回（不管左侧还有什么）")
        void firstUntrustedIsClient() {
            IpUtils utils = withProxies("10.0.0.8");
            // 链中 10.0.0.15 不在可信名单 → 它就是客户端
            HttpServletRequest r = req("10.0.0.8", "6.6.6.6, 10.0.0.15");

            String ip = utils.getClientIp(r);

            // 右侧向左：10.0.0.8(可信跳过) → 10.0.0.15(非可信)
            assertThat(ip).isEqualTo("10.0.0.15");
            // 注意：最左的 6.6.6.6 被忽略——因为"遇到第一个非可信就停"
            // 这意味着"攻击者在自己 XFF 左侧造 IP 没用"——Nginx 必须覆盖追加到链尾
        }
    }

    // ================================================================
    // 场景 3：XFF 中含非法值 → fail-closed 整条链作废
    // ================================================================

    @Nested
    @DisplayName("XFF 任一片段非法 → fail-closed 回退 remoteAddr")
    class FailClosed {

        @ParameterizedTest
        @ValueSource(strings = {"1.2.3.abc", "1.2.3", "999.1.1.1", "0:0:0:0:0:0:0:gg", "not.an.ip", ""})
        void invalidXffSegmentCausesFullFallback(String badSegment) {
            IpUtils utils = withProxies("10.0.0.8");
            HttpServletRequest r = req("10.0.0.8", "203.0.113.1, " + badSegment);

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("10.0.0.8"); // 整条头作废，回退 remoteAddr
        }

        @Test
        @DisplayName("XFF 含非法主机名（含字母）→ 回退 remoteAddr，不做 DNS 解析")
        void noDNSLookupOnInvalidXff() {
            IpUtils utils = withProxies("10.0.0.8");
            // 含合法主机名字符（如 example.com），parseLiteral 先限定字符集会返回 null
            HttpServletRequest r = req("10.0.0.8", "203.0.113.1, example.com");

            String ip = utils.getClientIp(r);

            // 不会去 DNS 解析 example.com，直接 fail-closed
            assertThat(ip).isEqualTo("10.0.0.8");
        }
    }

    // ================================================================
    // 场景 4：CIDR 网段匹配
    // ================================================================

    @Nested
    @DisplayName("可信代理支持 CIDR 网段")
    class CidrSupport {

        @Test
        @DisplayName("Docker 网桥网段 172.17.0.0/16 内任意地址都可信")
        void dockerBridgeRangeMatches() {
            IpUtils utils = withProxies("172.17.0.0/16");
            HttpServletRequest r = req("172.17.5.23", "203.0.113.1");

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("203.0.113.1"); // 直连方可信，解析出客户端 IP
        }

        @Test
        @DisplayName("网段外地址不可信")
        void outsideCidrNotTrusted() {
            IpUtils utils = withProxies("172.17.0.0/16");
            HttpServletRequest r = req("172.18.1.1", "203.0.113.1"); // 172.18 在 172.17/16 外

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("172.18.1.1"); // 直连方不在网段，回退
        }
    }

    // ================================================================
    // 场景 5：边界与异常
    // ================================================================

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("XFF 不存在时才参考 X-Real-IP；XFF 存在但全部可信时不再参考 X-Real-IP")
        void xRealIpFallbackOnlyWhenXffMissing() {
            IpUtils utils = withProxies("10.0.0.8");

            // 场景 A：XFF 不存在，X-Real-IP 由可信代理写入
            HttpServletRequest r1 = req("10.0.0.8");
            when(r1.getHeader("X-Real-IP")).thenReturn("203.0.113.1");
            assertThat(utils.getClientIp(r1)).isEqualTo("203.0.113.1");

            // 场景 B：XFF 存在但全是可信代理（客户端缺失）→ 不再看 X-Real-IP
            HttpServletRequest r2 = req("10.0.0.8", "10.0.0.8"); // 只有代理自己
            when(r2.getHeader("X-Real-IP")).thenReturn("6.6.6.6"); // 攻击者伪造 X-Real-IP
            assertThat(utils.getClientIp(r2)).isEqualTo("10.0.0.8"); // 回退 remoteAddr，不信 X-Real-IP
        }

        @Test
        @DisplayName("IPv6 回环归一化：Windows 下 0:0:0:0:0:0:0:1 归一成 ::1")
        void ipv6LoopbackNormalization() {
            IpUtils utils = withProxies("::1");
            HttpServletRequest r = req("0:0:0:0:0:0:0:1", "203.0.113.1"); // 归一化后 remoteAddr=::1，在名单

            String ip = utils.getClientIp(r);

            assertThat(ip).isEqualTo("203.0.113.1"); // 归一化成功，直连方可信
        }
    }

    // ================================================================
    // 场景 6：构造时配置非法 → fail-fast（启动时就炸）
    // ================================================================

    @Nested
    @DisplayName("配置非法 → 构造器直接抛异常（fail-fast）")
    class ConfigValidation {

        @ParameterizedTest
        @ValueSource(strings = {"", "10.0.0.0/33", "999.1.1.1", "not_an_ip", "10.0.0.0/-1"})
        void invalidConfigThrowsAtConstruction(String badConfig) {
            // 空串 → 不抛（空名单合法）；非空非法 → 抛
            if (!badConfig.isEmpty()) {
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new IpUtils(badConfig)
                );
            } else {
                // 空串 → 正常创建空名单
                new IpUtils(badConfig); // 不抛
            }
        }
    }
}

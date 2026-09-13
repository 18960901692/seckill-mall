package com.sygzcd.seckillmall.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端真实 IP 解析（H-6：防 X-Forwarded-For 伪造）
 *
 * 信任边界模型：
 * 1. 只以 TCP 层的 remoteAddr 判断直连方是否为可信代理；不可信（用户直连）时转发头一律不读，直接返回 remoteAddr。
 * 2. 直连方可信时，从 X-Forwarded-For 右侧向左沿代理链验证，只跳过明确配置的可信代理，
 *    遇到的第一个非可信地址即视为客户端 IP——绝不信最左侧（那是客户端可自造的位置）。
 * 3. XFF 中任一片段非法 → 整条头作废，回退 remoteAddr（fail-closed，不静默丢弃非法片段改变链位置）。
 * 4. 仅当 XFF 头不存在时才参考 X-Real-IP；XFF 存在但解析不出客户端时不再回退 X-Real-IP（二者同源，不具独立可信度）。
 *
 * 可信代理支持单 IP 与 CIDR（如 172.18.0.0/16）；名单为空时任何人都不可信。
 *
 * 代理层（Nginx）必须覆盖而非追加客户端自造头，形成双保险：
 *   proxy_set_header X-Real-IP $remote_addr;
 *   proxy_set_header X-Forwarded-For $remote_addr;
 */
@Component
public class IpUtils {

    /**
     * 预解析后的可信代理条目（启动时解析一次，避免每请求重复 parse）。
     */
    private final List<IpEntry> trustedProxies;

    public IpUtils(@Value("${app.security.trusted-proxies:}") String trustedProxiesCsv) {
        List<IpEntry> entries = new ArrayList<>();
        if (trustedProxiesCsv != null && !trustedProxiesCsv.isBlank()) {
            for (String raw : trustedProxiesCsv.split(",")) {
                String token = raw.trim();
                if (!token.isEmpty()) {
                    entries.add(parseEntry(token));
                }
            }
        }
        this.trustedProxies = List.copyOf(entries);
    }

    /**
     * 获取客户端真实 IP。
     */
    public String getClientIp(HttpServletRequest request) {
        String remoteAddr = normalize(request.getRemoteAddr());

        // TCP 直连方不是可信代理（典型：外部用户直连 Tomcat）→ 转发头全部不可信
        if (!isValidIp(remoteAddr) || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            List<String> chain = new ArrayList<>(parts.length);
            for (String part : parts) {
                String ip = normalize(part.trim());
                // 任一片段非法：整条链不可信，直接回退（不能 filter 删除，否则改变后续位置语义）
                if (!isValidIp(ip)) {
                    return remoteAddr;
                }
                chain.add(ip);
            }
            // 从右往左沿代理链验证：只跨越可信代理，第一个非可信地址即客户端
            for (int i = chain.size() - 1; i >= 0; i--) {
                String ip = chain.get(i);
                if (!isTrustedProxy(ip)) {
                    return ip;
                }
            }
            // 整条 XFF 都是可信代理（客户端地址缺失/链异常）→ 不再参考 X-Real-IP
            return remoteAddr;
        }

        // XFF 不存在时才参考 X-Real-IP（可信代理场景下通常由 Nginx 覆盖写入）
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            realIp = normalize(realIp.trim());
            // 合法且不是可信代理自身地址，才作为客户端 IP
            if (isValidIp(realIp) && !isTrustedProxy(realIp)) {
                return realIp;
            }
        }

        return remoteAddr;
    }

    /**
     * 判断给定 IP 是否在可信代理名单内（精确匹配或 CIDR 包含）。
     */
    public boolean isTrustedProxy(String ip) {
        if (ip == null) {
            return false;
        }
        byte[] addr = parseLiteral(ip);
        if (addr == null) {
            return false;
        }
        for (IpEntry entry : trustedProxies) {
            if (entry.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化：Windows/部分容器下 IPv6 回环可能以长形式出现。
     */
    private static String normalize(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            return "::1";
        }
        return ip;
    }

    /**
     * 严格校验 IP 字面值（IPv4/IPv6），绝不触发 DNS 查询。
     */
    private static boolean isValidIp(String ip) {
        return parseLiteral(ip) != null;
    }

    /**
     * 解析配置条目：单 IP 或 CIDR（ip/prefix）。配置非法直接 fail-fast，避免带病启动。
     */
    private static IpEntry parseEntry(String token) {
        int slash = token.indexOf('/');
        String ipPart = slash >= 0 ? token.substring(0, slash) : token;
        byte[] addr = parseLiteral(ipPart);
        if (addr == null) {
            throw new IllegalArgumentException("trusted-proxies 配置的 IP 非法: " + token);
        }
        int prefix = addr.length * 8;
        if (slash >= 0) {
            String prefixPart = token.substring(slash + 1);
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("trusted-proxies 的 CIDR 前缀非法: " + token);
            }
            if (prefix < 0 || prefix > addr.length * 8) {
                throw new IllegalArgumentException("trusted-proxies 的 CIDR 前缀越界: " + token);
            }
        }
        return new IpEntry(addr, prefix);
    }

    /**
     * 严格解析 IP 字面值为字节数组（IPv4=4 字节，IPv6=16 字节），非法返回 null，不做 DNS。
     */
    private static byte[] parseLiteral(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        // 手动严格解析 IPv4（避免 InetAddress 对前导零的八进制歧义，也不碰 DNS）
        if (ip.indexOf(':') < 0) {
            return parseIpv4(ip);
        }
        // IPv6：先限定字符集（只允许十六进制字符、':'、'.'，后者用于 IPv4-mapped 形式），
        // 不含任何合法主机名字符，再交给 JDK 做字面值解析，不会触发 DNS
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') {
                return null;
            }
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] parseIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String seg = parts[i];
            if (seg.isEmpty() || seg.length() > 3) {
                return null;
            }
            // 拒绝前导零（如 010），避免不同组件按八进制/十进制解释产生歧义
            if (seg.length() > 1 && seg.charAt(0) == '0') {
                return null;
            }
            int value = 0;
            for (int j = 0; j < seg.length(); j++) {
                char c = seg.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    /**
     * 可信代理条目：网络字节 + 前缀位数（单 IP 时前缀为地址全长）。
     */
    private static final class IpEntry {
        private final byte[] network;
        private final int prefixBits;

        private IpEntry(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        private boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != candidate[i]) {
                    return false;
                }
            }
            int remainBits = prefixBits % 8;
            if (remainBits != 0 && fullBytes < network.length) {
                int mask = 0xFF << (8 - remainBits);
                if ((network[fullBytes] & mask) != (candidate[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}

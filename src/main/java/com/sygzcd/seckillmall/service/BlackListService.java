package com.sygzcd.seckillmall.service;

import java.util.Set;

/**
 * 黑名单服务
 * 基于 Redis Set 实现 IP/用户防刷黑名单
 */
public interface BlackListService {

    /**
     * 记录违规次数，超过阈值自动加入黑名单
     * @param type 类型："ip" 或 "user"
     * @param key IP地址或用户ID
     * @return true 表示本次记录后触发了拉黑
     */
    boolean recordViolation(String type, String key);

    /**
     * 检查是否在黑名单中
     * @param type 类型："ip" 或 "user"
     * @param key IP地址或用户ID
     * @return true 表示在黑名单中
     */
    boolean isBlackListed(String type, String key);

    /**
     * 手动加入黑名单
     * @param type 类型："ip" 或 "user"
     * @param key IP地址或用户ID
     */
    void addToBlackList(String type, String key);

    /**
     * 移出黑名单
     * @param type 类型："ip" 或 "user"
     * @param key IP地址或用户ID
     */
    void removeFromBlackList(String type, String key);

    /**
     * 获取黑名单列表
     * @param type 类型："ip" 或 "user"
     * @return 黑名单成员集合
     */
    Set<Object> getBlackList(String type);
}

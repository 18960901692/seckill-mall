package com.sygzcd.seckillmall.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.common.ProductDTO;
import com.sygzcd.seckillmall.config.CacheInvalidateConfig;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.BloomFilterService;
import com.sygzcd.seckillmall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Cache<String, ProductDTO> caffeineCache;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private BloomFilterService bloomFilterService;

    private static final String PRODUCT_KEY = "product:";
    private static final String STOCK_KEY = "seckill:stock:";
    private static final String LOCK_KEY = "lock:product:";
    /** 空值标记 key 前缀：与 product:{id} 隔离，避免污染 ProductDTO 反序列化 */
    private static final String EMPTY_KEY_PREFIX = "product:empty:";
    /** 空值缓存 TTL：60s 基础 + 0~30s 随机，挡住布隆误判流量，同时避免新上架商品被长期挡住 */
    private static final long EMPTY_BASE_TTL = 60;
    private static final long EMPTY_RANDOM_TTL = 30;

    /**
     * 空值标记单例：Caffeine 不能存 null，用 id=null 的 ProductDTO 表示"DB 确认不存在"。
     * 正常商品 id 不可能为 null，命中后以 getId() == null 识别。
     */
    private static final ProductDTO EMPTY_MARKER = new ProductDTO();

    @Override
    public ProductDTO getById(Long id) {
        // 第 0 层：布隆过滤器前置。false=一定不存在，直接返回，不碰缓存/DB，防缓存穿透
        // （过滤器未就绪时 mightContain 默认放行，不会误伤；布隆 1% 误判由下方空值缓存兜住）
        if (!bloomFilterService.mightContain(id)) {
            return null;
        }

        String key = PRODUCT_KEY + id;
        String emptyKey = EMPTY_KEY_PREFIX + id;

        // 第一层：Caffeine 本地缓存（存 ProductDTO，不含 stock/version）
        ProductDTO productDTO = caffeineCache.getIfPresent(key);
        if (productDTO != null) {
            // 命中空值标记：DB 已确认不存在；否则是正常商品
            return productDTO.getId() == null ? null : productDTO;
        }

        // 第二层：Redis 缓存
        // 2.1 先查空值标记（独立 key，StringRedisTemplate 存纯字符串 "1"）
        if ("1".equals(stringRedisTemplate.opsForValue().get(emptyKey))) {
            caffeineCache.put(key, EMPTY_MARKER);
            return null;
        }
        // 2.2 再查正常商品缓存（存 ProductDTO，与 Caffeine 一致，不含 stock/version）
        ProductDTO redisDTO = (ProductDTO) redisTemplate.opsForValue().get(key);
        if (redisDTO != null) {
            caffeineCache.put(key, redisDTO);
            return redisDTO;
        }

        // 第三层：缓存未命中，使用互斥锁防止缓存击穿
        String lockKey = LOCK_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        // 尝试获取锁，等待 50ms
        // leaseTime=0 表示由 Redisson 看门狗自动续期（默认30s，线程持有期间自动续期）
        // 避免设置固定过期时间导致锁提前释放、被其他线程误持
        boolean locked;
        try {
            locked = lock.tryLock(50, 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 线程被中断时直接查 DB 兜底
            return toDTO(productMapper.selectById(id));
        }
        if (!locked) {
            // 等待超时仍未获取锁，说明数据可能已被其他线程加载，双重检查两种缓存
            if ("1".equals(stringRedisTemplate.opsForValue().get(emptyKey))) {
                caffeineCache.put(key, EMPTY_MARKER);
                return null;
            }
            redisDTO = (ProductDTO) redisTemplate.opsForValue().get(key);
            if (redisDTO != null) {
                caffeineCache.put(key, redisDTO);
                return redisDTO;
            }
            // Redis 也未命中（极端情况），直接查 DB
            return toDTO(productMapper.selectById(id));
        }

        try {
            // 再次检查缓存（双重检查）：空值标记 + 正常缓存
            if ("1".equals(stringRedisTemplate.opsForValue().get(emptyKey))) {
                caffeineCache.put(key, EMPTY_MARKER);
                return null;
            }
            redisDTO = (ProductDTO) redisTemplate.opsForValue().get(key);
            if (redisDTO != null) {
                caffeineCache.put(key, redisDTO);
                return redisDTO;
            }

            // 从数据库加载
            Product product = productMapper.selectById(id);
            if (product != null) {
                productDTO = toDTO(product);
                // Redis 存 ProductDTO（与 Caffeine 一致，不含 stock/version，避免脏数据）
                // 热点商品永不过期，普通商品设置随机TTL防止缓存雪崩
                if (product.getHot() != null && product.getHot() == 1) {
                    redisTemplate.opsForValue().set(key, productDTO);
                } else {
                    long baseTtl = 30 * 60;
                    long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
                    redisTemplate.opsForValue().set(key, productDTO, baseTtl + randomTtl, TimeUnit.SECONDS);
                }
                // Caffeine 存 ProductDTO（不含 stock/version，避免脏数据）
                caffeineCache.put(key, productDTO);
            } else {
                // DB 确认不存在：写两层空值缓存（Redis 短 TTL + Caffeine 30s），挡住后续穿透请求
                long emptyTtl = EMPTY_BASE_TTL + ThreadLocalRandom.current().nextLong(0, EMPTY_RANDOM_TTL);
                stringRedisTemplate.opsForValue().set(emptyKey, "1", emptyTtl, TimeUnit.SECONDS);
                caffeineCache.put(key, EMPTY_MARKER);
                log.debug("商品不存在，写入空值缓存，商品ID: {}，TTL: {}s", id, emptyTtl);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // 商品不存在时 productDTO 保持 null（EMPTY_MARKER 不赋给返回变量）
        return productDTO;
    }

    @Override
    public Integer getStock(Long id) {
        String key = STOCK_KEY + id;

        // 第一层：Redis（实时库存，秒杀一致性保证）
        // 使用 StringRedisTemplate 读取，确保与 DECR/INCR 操作一致
        String stockStr = stringRedisTemplate.opsForValue().get(key);
        if (stockStr != null) {
            return Integer.parseInt(stockStr);
        }

        // 第二层：MySQL（Redis 未初始化时回填，返回后后续请求走 Redis）
        Product product = productMapper.selectById(id);
        if (product != null) {
            Integer stock = product.getStock();
            // 使用 StringRedisTemplate 写入，确保值为纯数字字符串
            // 随机TTL防止缓存雪崩：基础30分钟 + 随机0-5分钟
            long baseTtl = 30 * 60;
            long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
            stringRedisTemplate.opsForValue().set(key, String.valueOf(stock), baseTtl + randomTtl, TimeUnit.SECONDS);
            return stock;
        }

        return 0;
    }

    @Override
    public void warmUpProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product != null) {
            String key = PRODUCT_KEY + id;
            String stockKey = STOCK_KEY + id;
            ProductDTO dto = toDTO(product);

            // 新商品激活即时补位：把商品ID加入布隆过滤器，避免被防穿透拦截误判为"不存在"
            bloomFilterService.put(product.getId());

            // 清理可能残留的空值标记（防御"先被空标记挡住、后预热上架"的时序）
            // Caffeine 下方的 put 会自动覆盖 EMPTY_MARKER，Redis 空标记需显式删除
            stringRedisTemplate.delete(EMPTY_KEY_PREFIX + id);

            // 商品信息预热到 Redis（存 ProductDTO，与 Caffeine 一致，不含库存）
            redisTemplate.opsForValue().set(key, dto);
            // 库存预热到 Redis（独立 key，使用 StringRedisTemplate 保证纯数字字符串）
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));

            // 商品信息预热到 Caffeine（存 ProductDTO，不含库存）
            caffeineCache.put(key, dto);
        }
    }

    /**
     * 失效商品缓存
     * 清除 Redis product:{id} + 失效 Caffeine 本地缓存 + 广播通知其他实例
     * 注意：seckill:stock:{id} 是实时计数器，由秒杀/取消订单逻辑维护，不在此删除
     */
    @Override
    public void invalidateCache(Long id) {
        String key = PRODUCT_KEY + id;

        // 1. 清除 Redis 商品信息缓存
        //    使用 StringRedisTemplate 避免 Jackson 序列化给 key 加双引号
        stringRedisTemplate.delete(key);

        // 2. 失效本地 Caffeine 缓存（仅商品信息 ProductDTO）
        caffeineCache.invalidate(key);

        // 3. 广播通知其他实例清除本地 Caffeine
        //    统一使用 StringRedisTemplate，与监听端 new String(getBody()) 匹配
        stringRedisTemplate.convertAndSend(CacheInvalidateConfig.CACHE_INVALIDATE_CHANNEL, key);

        log.debug("商品缓存已失效，商品ID: {}", id);
    }

    /**
     * Product → ProductDTO 转换
     * 脱敏高频变更字段（stock、version），仅保留基本信息
     */
    private ProductDTO toDTO(Product product) {
        if (product == null) {
            return null;
        }
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setHot(product.getHot());
        dto.setCreateTime(product.getCreateTime());
        return dto;
    }
}
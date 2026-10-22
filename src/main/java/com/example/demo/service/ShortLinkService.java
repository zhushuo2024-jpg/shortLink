package com.example.demo.service;

import com.example.demo.entity.ShortLink;
import com.example.demo.mapper.ShortLinkMapper;
import com.example.demo.mq.ClickEvent;
import com.example.demo.util.Base62Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.spring.core.RocketMQTemplate;




@Service
public class ShortLinkService {

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    private static final String CACHE_KEY_PREFIX = "shortlink:";
    private static final long CACHE_TTL_MINUTES = 60;

    @Autowired//redission集成bloom过滤器
    private RedissonClient redissonClient;

    private RBloomFilter<String> bloomFilter;
    //布隆过滤器
    @PostConstruct
    public void initBloomFilter() {
        bloomFilter = redissonClient.getBloomFilter("shortlink:bloom");
        // 预期插入 1 亿条，误判率 1%
        bloomFilter.tryInit(100_000_000L, 0.01);

        // 把数据库里已有的短码加载进布隆过滤器
        List<ShortLink> all = shortLinkMapper.selectAll();
        for (ShortLink link : all) {
            bloomFilter.add(link.getShortCode());
        }
        System.out.println("布隆过滤器初始化完成，加载短码数：" + all.size());
    }
    //固定窗口限流器：
    //逻辑：第一次请求 INCR 返回 1，设置 1 分钟过期。
    // 之后每次请求计数 +1。超过 10 就抛异常
    public void checkRateLimit(String ip) {
        String key = "limit:shorten:" + ip;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        if (count != null && count > 10) {
            throw new RuntimeException("请求过于频繁，请稍后再试");
        }
    }
    //用滑动窗口处理
    public void checkRateLimitSliding(String ip) {
        String key = "limit:shorten:" + ip;
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;   // 60 秒前

        // 1. 移除窗口外的请求
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // 2. 统计窗口内的请求数
        //ZCARD limit:shorten:192.168.1.1,ZCARD	Redis 命令，返回 ZSet 的元素个数
        Long count = redisTemplate.opsForZSet().zCard(key);
        if (count != null && count >= 10) {
            throw new RuntimeException("请求过于频繁，请稍后再试");
        }

        // 3. 记录当前请求
        redisTemplate.opsForZSet().add(key, String.valueOf(now), now);
        redisTemplate.expire(key, 1, TimeUnit.MINUTES);
    }

    public String shorten(String originalUrl, String channel) {
        ShortLink shortLink = new ShortLink();
        shortLink.setOriginalUrl(originalUrl);
        shortLink.setChannel(channel);
        // 关键点 1：给 short_code 一个占位值，避免第一次 INSERT 时数据库报错
        shortLink.setShortCode("temp_" + System.currentTimeMillis());

        // 1. 插入数据库，获取自增 ID
        shortLinkMapper.insert(shortLink);

        // 2. 用自增 ID 生成 Base62 短码
        String shortCode = Base62Util.encode(shortLink.getId());

        // 3. 更新真正的短码
        shortLinkMapper.updateShortCode(shortLink.getId(), shortCode);

        // 4. 加入布隆过滤器
        bloomFilter.add(shortCode);

        return shortCode;
    }
    //查询点击次数
    public long getClickCount(String shortCode) {
        String count = redisTemplate.opsForValue().get("shortlink:clicks:" + shortCode);
        return count == null ? 0 : Long.parseLong(count);
    }
    //降级兜底方法，redis不可用，调用此方法直接查数据库
    private String queryFromMysql(String shortCode) {
        ShortLink shortLink = shortLinkMapper.selectByShortCode(shortCode);
        if (shortLink == null || shortLink.getOriginalUrl() == null
                || shortLink.getOriginalUrl().isEmpty()) {
            return null;
        }
        return shortLink.getOriginalUrl();
    }

    //渠道归因与点击统计
//    private void recordClick(String shortCode, String channel) {
//        try {
//            // 1. 总点击量
//            redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode);
//            // 2. 渠道点击量
//            if (channel != null && !channel.isEmpty()) {
//                redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode + ":channel:" + channel);
//            }
//        } catch (Exception e) {
//            System.out.println("Redis 异常，点击计数忽略：" + e.getMessage());
//        }
//    }
    //
    //引入消息队列的渠道归因与数据统计
    private void recordClick(String shortCode, String channel) {
        try {
            ClickEvent event = new ClickEvent(shortCode, channel, System.currentTimeMillis());
            rocketMQTemplate.convertAndSend("shortlink-click-topic", event);
        } catch (Exception e) {
            System.out.println("MQ 发送失败，降级直接更新 Redis：" + e.getMessage());
            // MQ 发送失败时的降级兜底
            try {
                redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode);
            } catch (Exception ignore) {}
        }
    }
    public Map<String, Object> getStats(String shortCode) {
        Map<String, Object> result = new HashMap<>();
        String totalStr = redisTemplate.opsForValue().get("shortlink:clicks:" + shortCode);
        result.put("total", totalStr == null ? 0 : Long.parseLong(totalStr));

        // 这里仅做演示，真实场景可能需要通过扫描或者预先存一个渠道列表
        // 假设我们已知渠道有 wechat, weibo, qq
        Map<String, Long> channels = new HashMap<>();
        String[] knownChannels = {"wechat", "weibo", "qq", "default"};
        for (String ch : knownChannels) {
            String countStr = redisTemplate.opsForValue().get("shortlink:clicks:" + shortCode + ":channel:" + ch);
            if (countStr != null) {
                channels.put(ch, Long.parseLong(countStr));
            }
        }
        result.put("channels", channels);
        return result;
    }

    public String getOriginalUrl(String shortCode) {
        // 0. 布隆过滤器拦截 ,降级try catch包裹
        try{
            if (!bloomFilter.contains(shortCode)) {
                System.out.println("布隆过滤器拦截，shortCode=" + shortCode);
                return null;
            }

        }
        catch (Exception e){
            System.out.println("布隆过滤器异常，降级跳过：" + e.getMessage());

        }

        //前缀拼接
        String cacheKey = CACHE_KEY_PREFIX + shortCode;

        // 1. 查 Redis
        try{
            String cachedUrl = redisTemplate.opsForValue().get(cacheKey);
            if (cachedUrl != null) {
                if (cachedUrl.isEmpty()) {
                    return null;   // 空值缓存
                }
                try{
                    // 命中有效缓存，计数 +1
                    redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode);
                }catch (Exception ignore){
                }
                  return cachedUrl;
            }

        } catch (Exception e) {
            System.out.println("Redis 异常，降级查 MySQL：" + e.getMessage());
            return queryFromMysql(shortCode);   // 直接降级
        }


        // 2. 缓存未命中，尝试获取互斥锁
        String lockKey = "lock:" + shortCode;
        Boolean locked = false;
        try{
            locked = redisTemplate.opsForValue().setIfAbsent(lockKey,"1",10,TimeUnit.SECONDS);
        }catch(Exception e){
            System.out.println("获取互斥锁异常，降级查 MySQL：" + e.getMessage());
            return queryFromMysql(shortCode);   // 加锁失败，直接降级

        }

        if (Boolean.TRUE.equals(locked)) {
            // 拿到锁，查 MySQL 并写回 Redis
            try {
                ShortLink shortLink = shortLinkMapper.selectByShortCode(shortCode);
                if (shortLink == null || shortLink.getOriginalUrl() == null
                        || shortLink.getOriginalUrl().isEmpty()) {
                    // 写空值缓存
                    try{
                        redisTemplate.opsForValue().set(cacheKey, "", 60, TimeUnit.SECONDS);

                    }catch (Exception e){
                    }
                       return null;
                }

                // 写回 Redis，TTL 加随机值防雪崩
                try {
                    long ttl = CACHE_TTL_MINUTES + (long) (Math.random() * 10);
                    redisTemplate.opsForValue().set(cacheKey, shortLink.getOriginalUrl(),
                            ttl, TimeUnit.MINUTES);
                    // 点击计数 +1
                    redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode);

                    //
                    recordClick(shortCode,shortLink.getChannel());
                }catch (Exception e){
                    System.out.println("写缓存异常，但已查到数据，不影响跳转：" + e.getMessage());
                }
                return shortLink.getOriginalUrl();
            } finally {
                try {redisTemplate.delete(lockKey);}
                catch (Exception e){}// 释放锁
            }
        } else {
            // 没拿到锁，等待后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return getOriginalUrl(shortCode);   // 递归重试
        }
    }
}
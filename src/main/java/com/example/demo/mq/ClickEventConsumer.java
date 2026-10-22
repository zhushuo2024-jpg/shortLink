package com.example.demo.mq;

import com.example.demo.mapper.ShortLinkMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
@Component
@RocketMQMessageListener(
        topic = "shortlink-click-topic",
        consumerGroup = "shortlink-consumer-group"
)
public class ClickEventConsumer implements RocketMQListener<ClickEvent> {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Override
    public void onMessage(ClickEvent event) {
        String shortCode = event.getShortCode();
        String channel = event.getChannel();

        // 1. 保持 Redis 的高效更新（保证前台实时查询）
        try {
            redisTemplate.opsForValue().increment("shortlink:clicks:" + shortCode);
            if (channel != null && !channel.isEmpty()) {
                redisTemplate.opsForValue().increment(
                        "shortlink:clicks:" + shortCode + ":channel:" + channel);
            }
        } catch (Exception e) {
            System.out.println("Redis 计数异常：" + e.getMessage());
        }

        // 2. 直接持久化到 MySQL（或者采用批量写入）
        // 注意：高并发下直接 UPDATE 压力大，这里演示基础逻辑
        try {
            shortLinkMapper.incrementClickCount(shortCode);
            System.out.println("消费者落库成功：" + shortCode);
        } catch (Exception e) {
            System.out.println("MySQL 落库失败，触发 MQ 重试：" + e.getMessage());
            throw e; // 抛出异常，RocketMQ 会重试
        }
    }
}
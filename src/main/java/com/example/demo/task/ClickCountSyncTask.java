package com.example.demo.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.demo.mapper.ShortLinkMapper;
import java.util.Set;

@Component
public class ClickCountSyncTask {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Scheduled(fixedRate = 300000)   // 每 5 分钟
    public void syncClickCount() {
        Set<String> keys = redisTemplate.keys("shortlink:clicks:*");
        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {
            String shortCode = key.replace("shortlink:clicks:", "");
            String count = redisTemplate.opsForValue().get(key);
            if (count != null) {
                shortLinkMapper.updateClickCount(shortCode, Long.parseLong(count));
                System.out.println("同步点击次数，shortCode=" + shortCode + "，count=" + count);
            }
        }
    }
}
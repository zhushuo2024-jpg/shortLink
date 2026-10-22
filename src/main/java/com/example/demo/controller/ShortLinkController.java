package com.example.demo.controller;

import com.example.demo.service.ShortLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController //@RestController = @Controller + @ResponseBody
public class ShortLinkController {

    @Autowired //IOP注入
    private ShortLinkService shortLinkService;

    /**
     * 生成短链
     * POST /shorten  body: {"url": "https://..."}
     */

    //@PostMapping("/shorten")：把 HTTP 的 POST /shorten 请求映射到这个方法。
    //
    //@RequestBody：把请求体里的 JSON 反序列化成 Java 对象。这里用的是 Map<String, String>，因为请求体很简单：{"url": "https://..."}。
    //
    //Map<String, String>：方法的返回值会由 Jackson 自动转成 JSON
    @PostMapping("/shorten")
    public Map<String, String> shorten(@RequestBody Map<String, String> body, HttpServletRequest request) {

        String ip = request.getRemoteAddr();//获取主机ip
        //shortLinkService.checkRateLimit(ip);//设置ip限流 固定窗口
        shortLinkService.checkRateLimitSliding(ip);//设置滑动窗口ip限流
        String originalUrl = body.get("url");
        String channel =body.getOrDefault("channel","default");
        String shortCode = shortLinkService.shorten(originalUrl,channel);
        return Map.of("shortUrl", "http://localhost:8080/" + shortCode);
    }

    /**
     * 跳转
     * GET /{shortCode}
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = shortLinkService.getOriginalUrl(shortCode);
        if (originalUrl == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }


    @GetMapping("/stats/{shortCode}")
    public Map<String, Object> stats(@PathVariable String shortCode) {
        // 由于之前你是从 Redis 直接拿的，这里写个 Service 方法
        return shortLinkService.getStats(shortCode);
    }
}
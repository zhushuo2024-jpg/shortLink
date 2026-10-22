package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController//与@Controller的直接区别是返回的是数据,而不是视图
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello Spring Boot 4.1.1";
    }
}
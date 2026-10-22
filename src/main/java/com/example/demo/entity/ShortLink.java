package com.example.demo.entity;

import lombok.Data;

import java.time.LocalDateTime;


//在pom.xml中用lombok依赖书写实体类
@Data
public class ShortLink {
    private Long id;
    private String shortCode;
    private String originalUrl;
    private LocalDateTime createTime;
    private  Integer clinkCount;
    private String channel;
}

package com.example.demo.mq;

import lombok.Data;

@Data
public class ClickEvent {
    private String shortCode;
    private String channel;
    private long timestamp;

    public ClickEvent(String  shortCode,String channel,long timestamp){
        this.shortCode =shortCode;
        this.channel = channel;
        this.timestamp =timestamp;

    }
}

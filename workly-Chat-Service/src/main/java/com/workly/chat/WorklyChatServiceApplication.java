package com.workly.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = { "com.workly.chat", "com.workly.common" })
public class WorklyChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorklyChatServiceApplication.class, args);
    }
}

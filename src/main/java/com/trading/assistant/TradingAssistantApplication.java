package com.trading.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradingAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingAssistantApplication.class, args);
    }

}

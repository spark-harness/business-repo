package com.spark.quote.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.spark.quote")
public class QuoteApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuoteApiApplication.class, args);
    }
}

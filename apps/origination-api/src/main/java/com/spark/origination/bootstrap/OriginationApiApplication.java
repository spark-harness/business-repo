package com.spark.origination.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.spark.origination")
public class OriginationApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(OriginationApiApplication.class, args);
    }
}

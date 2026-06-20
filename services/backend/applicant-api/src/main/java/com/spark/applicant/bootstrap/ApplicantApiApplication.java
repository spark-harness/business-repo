package com.spark.applicant.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.spark.applicant", exclude = DataSourceAutoConfiguration.class)
public class ApplicantApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApplicantApiApplication.class, args);
    }
}

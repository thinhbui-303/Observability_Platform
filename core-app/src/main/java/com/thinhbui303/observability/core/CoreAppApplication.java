package com.thinhbui303.observability.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = "com.thinhbui303.observability")
@EntityScan(basePackages = "com.thinhbui303.observability.core.domain")
public class CoreAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreAppApplication.class, args);
    }
}

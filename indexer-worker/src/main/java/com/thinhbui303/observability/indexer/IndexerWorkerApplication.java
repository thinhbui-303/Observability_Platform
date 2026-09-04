package com.thinhbui303.observability.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class IndexerWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IndexerWorkerApplication.class, args);
    }
}

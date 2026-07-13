package com.ruikang.qc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FabricQcApplication {

    public static void main(String[] args) {
        SpringApplication.run(FabricQcApplication.class, args);
    }
}

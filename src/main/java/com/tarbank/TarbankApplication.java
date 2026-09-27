package com.tarbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TarbankApplication {

    public static void main(String[] args) {
        SpringApplication.run(TarbankApplication.class, args);
    }
}

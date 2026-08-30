package com.smsapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsBackendApplication.class, args);
    }
}

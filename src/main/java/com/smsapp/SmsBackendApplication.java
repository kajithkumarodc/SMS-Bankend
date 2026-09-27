package com.smsapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// Drives CapturePhotoPurgeService, which deletes classroom photos once their
// retention window closes -- a promise the consent screen makes to parents.
@EnableScheduling
public class SmsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsBackendApplication.class, args);
    }
}

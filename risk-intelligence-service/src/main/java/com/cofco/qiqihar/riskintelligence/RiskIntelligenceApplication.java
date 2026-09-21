package com.cofco.qiqihar.riskintelligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(proxyBeanMethods = false, scanBasePackages = {
        "com.cofco.qiqihar.riskintelligence",
        "com.cofco.qiqihar.graintrade.risk"
})
public class RiskIntelligenceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskIntelligenceApplication.class, args);
    }
}

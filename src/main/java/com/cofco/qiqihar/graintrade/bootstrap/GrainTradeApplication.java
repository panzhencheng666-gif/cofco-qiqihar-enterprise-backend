package com.cofco.qiqihar.graintrade.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(
        systemName = "Qiqihar Grain Trade Enterprise",
        additionalPackages = GrainTradeApplication.APPLICATION_BASE_PACKAGE,
        useFullyQualifiedModuleNames = true)
@SpringBootApplication(scanBasePackages = GrainTradeApplication.APPLICATION_BASE_PACKAGE)
public class GrainTradeApplication {

    static final String APPLICATION_BASE_PACKAGE = "com.cofco.qiqihar.graintrade";

    private GrainTradeApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(GrainTradeApplication.class, args);
    }
}

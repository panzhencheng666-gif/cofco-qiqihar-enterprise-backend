package com.cofco.qiqihar.graintrade.risk.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="qiqihar.risk.training.enabled",matchIfMissing=true)
class RiskTrainingConfiguration {
    @Bean(defaultCandidate=false)
    ThreadPoolTaskScheduler riskTrainingScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("risk-model-training-");
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}

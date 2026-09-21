package com.cofco.qiqihar.graintrade.regionalproduction.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "qiqihar.regional-public-data.enabled", matchIfMissing = true)
class RegionalPublicDataRefreshConfiguration {
    // Keep Boot's default scheduler available to imports, identity delivery and business events.
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler regionalPublicDataScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("regional-public-data-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}

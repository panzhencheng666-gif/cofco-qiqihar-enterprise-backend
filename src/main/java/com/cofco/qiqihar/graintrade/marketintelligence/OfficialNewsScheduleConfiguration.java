package com.cofco.qiqihar.graintrade.marketintelligence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keep official source requests from queuing behind unrelated scheduled work. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OfficialNewsScheduleConfiguration {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler officialNewsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(7);
        scheduler.setThreadNamePrefix("official-news-");
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}

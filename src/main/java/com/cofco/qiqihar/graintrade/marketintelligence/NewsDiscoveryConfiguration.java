package com.cofco.qiqihar.graintrade.marketintelligence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.io.IOException;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="qiqihar.market-intelligence.discovery.enabled",havingValue="true",matchIfMissing=false)
@EnableScheduling
class NewsDiscoveryConfiguration {
    private static final String PREFIX="qiqihar.market-intelligence.discovery.";
    @Bean
    ThreadPoolTaskScheduler newsDiscoveryTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("news-discovery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
    @Bean
    NewsDiscoverySchedule newsDiscoverySchedule(DataSource ds,Environment environment) throws IOException {
        Clock clock=Clock.systemUTC();
        Instant deadline=Instant.parse(environment.getRequiredProperty(PREFIX+"deadline"));
        if(!deadline.isAfter(clock.instant())) throw new IllegalArgumentException("Discovery authorization has expired");
        // Explicit existing ledger only. Never reset counts or create a new allowance on startup.
        var budget=new NewsSearchBudget(Path.of(environment.getRequiredProperty(PREFIX+"budget-directory")),
            deadline,Integer.parseInt(environment.getRequiredProperty(PREFIX+"remaining-per-engine")));
        var credentials=new EcsNewsCredentials(environment.getRequiredProperty(PREFIX+"ecs-role"),clock);
        var client=new IqsNewsSearchClient(credentials,budget,clock);
        var worker=new NewsDiscoveryWorker(ds,clock);
        return new NewsDiscoverySchedule(ds,clock,deadline,client::search,worker::runOnce);
    }
}

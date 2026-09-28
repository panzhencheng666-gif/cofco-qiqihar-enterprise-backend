package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

class OfficialNewsScheduleConfigurationTest {
    @Test
    void aSlowSourceDoesNotBlockAnotherOfficialSource() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(
                OfficialNewsScheduleConfiguration.class, BlockingSources.class)) {
            var sources = context.getBean(BlockingSources.class);
            try {
                assertThat(sources.slowStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(sources.otherStarted.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                sources.releaseSlow.countDown();
            }
        }
    }

    @Component
    static class BlockingSources {
        final CountDownLatch slowStarted = new CountDownLatch(1);
        final CountDownLatch otherStarted = new CountDownLatch(1);
        final CountDownLatch releaseSlow = new CountDownLatch(1);

        @Scheduled(scheduler = "officialNewsScheduler", initialDelay = 0, fixedDelay = 60_000)
        public void slow() throws InterruptedException {
            slowStarted.countDown();
            releaseSlow.await(5, TimeUnit.SECONDS);
        }

        @Scheduled(scheduler = "officialNewsScheduler", initialDelay = 0, fixedDelay = 60_000)
        public void other() {
            otherStarted.countDown();
        }
    }
}

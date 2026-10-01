package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import javax.sql.DataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class NewsDiscoveryConfigurationTest {
    @TempDir Path directory;
    @Test void defaultOffNeedsNoDatabaseBudgetOrCloudCredentials() {
        new ApplicationContextRunner().withUserConfiguration(NewsDiscoveryConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(NewsDiscoverySchedule.class);
            assertThat(context.containsBean("newsDiscoveryTaskScheduler")).isFalse();
        });
    }
    @Test void enablingWithoutExplicitProvisioningFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(NewsDiscoveryConfiguration.class)
            .withPropertyValues("qiqihar.market-intelligence.discovery.enabled=true")
            .run(context -> assertThat(context).hasFailed());
    }
    @Test void explicitLedgerStartsOneThreadWithoutConsumingSearchAllowance() throws Exception {
        Instant deadline=Instant.now().plusSeconds(600);
        Path ledger=directory.resolve("ledger");
        NewsSearchBudget.initialize(ledger,deadline,98);
        configured(ledger,deadline).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NewsDiscoverySchedule.class);
            assertThat(context.getBean("newsDiscoveryTaskScheduler",ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(1);
            assertThat(context.getBean(NewsDiscoverySchedule.class).lastCycle()).isEqualTo("NOT_RUN");
        });
        try(var files=Files.list(ledger)) { assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("manifest"); }
    }
    @Test void startupNeverCreatesOrResetsMissingLedger() {
        Path absent=directory.resolve("absent");
        configured(absent,Instant.now().plusSeconds(600)).run(context -> assertThat(context).hasFailed());
        assertThat(Files.exists(absent)).isFalse();
    }
    private ApplicationContextRunner configured(Path ledger,Instant deadline) {
        return new ApplicationContextRunner().withUserConfiguration(NewsDiscoveryConfiguration.class)
            .withBean(DataSource.class,() -> ProtectedTestDatabase.shared().dataSource())
            .withPropertyValues("qiqihar.market-intelligence.discovery.enabled=true",
                "qiqihar.market-intelligence.discovery.deadline="+deadline,
                "qiqihar.market-intelligence.discovery.budget-directory="+ledger,
                "qiqihar.market-intelligence.discovery.remaining-per-engine=98",
                "qiqihar.market-intelligence.discovery.ecs-role=TestFixtureRole");
    }
}

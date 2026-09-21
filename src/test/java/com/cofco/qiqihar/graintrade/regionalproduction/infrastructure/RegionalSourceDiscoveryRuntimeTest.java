package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;
import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalSourceDiscovery;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes=GrainTradeApplication.class,properties="qiqihar.regional-public-data.enabled=false")
@UsesProtectedTestDatabase
@Transactional
class RegionalSourceDiscoveryRuntimeTest {
    @Autowired JdbcClient jdbc;
    @Test void catchesUpAfterMissedEightThirtyWithoutCallingFailuresUnchanged() {
        var discovery=new RegionalSourceDiscovery(jdbc,"","");
        jdbc.sql("UPDATE production.regional_public_source SET last_attempt_at='2026-09-15 08:00:00+08' WHERE active").update();
        assertThat(discovery.dailyRefreshNeeded(Instant.parse("2026-09-15T00:29:59Z"))).isFalse();
        assertThat(discovery.dailyRefreshNeeded(Instant.parse("2026-09-15T00:30:00Z"))).isTrue();
        assertThat(discovery.dailyRefreshNeeded(Instant.parse("2026-09-15T00:35:00Z"))).isTrue();
        jdbc.sql("UPDATE production.regional_public_source SET last_attempt_at='2026-09-15 08:35:00+08' WHERE active").update();
        assertThat(discovery.dailyRefreshNeeded(Instant.parse("2026-09-15T00:36:00Z"))).isFalse();
    }
    @Test void runtimeRoleCanRegisterDiscoveredSourcesAndPreservesPartialEngineStatus() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search", exchange -> {
            byte[] bytes="""
              {"results":[{"title":"齐齐哈尔农业物流资料","url":"https://1.1.1.1/regional-public-fixture","content":"粮食运输"},{"title":"齐齐哈尔粮食政策招聘笔试模拟试题","url":"https://1.1.1.1/exam-fixture","content":"农业粮食试题"}],"unresponsive_engines":[["engine","timeout"]]}
              """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            jdbc.sql("UPDATE production.regional_public_source SET next_refresh_at='2099-01-01' WHERE parser_key='WEB_DISCOVERY'").update();
            jdbc.sql("UPDATE production.regional_public_source SET next_refresh_at=NULL WHERE source_id='search-230200'").update();
            jdbc.sql("SET LOCAL ROLE qiqihar_enterprise_runtime").update();
            new RegionalSourceDiscovery(jdbc,"","http://127.0.0.1:"+server.getAddress().getPort()).discover(Instant.now());
            assertThat(jdbc.sql("SELECT last_status FROM production.regional_public_source WHERE source_id='search-230200'").query(String.class).single()).isEqualTo("SEARCH_PARTIAL");
            assertThat(jdbc.sql("SELECT count(*) FROM production.regional_public_source WHERE source_url='https://1.1.1.1/regional-public-fixture'").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM production.regional_public_source WHERE source_url='https://1.1.1.1/exam-fixture'").query(Integer.class).single()).isZero();
        } finally { server.stop(0); }
    }
}

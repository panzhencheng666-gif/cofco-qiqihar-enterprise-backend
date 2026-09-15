package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.infrastructure.JdbcRegionalAgricultureBoundaryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes=GrainTradeApplication.class,properties="qiqihar.regional-public-data.enabled=false")
@UsesProtectedTestDatabase
@Transactional
class RegionalHierarchyPersistenceTest {
    @Autowired JdbcClient jdbc;
    @Test void savesChangedUnchangedAndFailedResultsWithRuntimePermissions() {
        var profiles=mock(RegionalAgricultureProfileService.class);
        var profile=new RegionalAgricultureProfile("230221100001","龙东村","VILLAGE",2026,true,Instant.now().toString(),"估算",
                new RegionalAgricultureProfile.RegionFacts(BigDecimal.ONE,0,0,0,0),"来源","面积分配",null,null,List.of(),List.of(),List.of(),List.of());
        when(profiles.profileForRefresh(eq(2026),eq("230221100001"),anyMap())).thenReturn(profile);
        var job=new RegionalHierarchyRefresh(jdbc,profiles);
        jdbc.sql("SET LOCAL ROLE qiqihar_enterprise_runtime").update();
        var now=Instant.parse("2026-09-15T00:30:00Z");
        job.refreshRegions(List.of("230221100001"),now);
        assertThat(status()).isEqualTo("RECALCULATED_CHANGED");
        job.refreshRegions(List.of("230221100001"),now.plusSeconds(86400));
        assertThat(status()).isEqualTo("RECALCULATED_UNCHANGED");
        String last=jdbc.sql("SELECT calculated_at::text FROM production.regional_hierarchy_calculation WHERE region_code='230221100001'").query(String.class).single();
        when(profiles.profileForRefresh(eq(2026),eq("230221100001"),anyMap())).thenThrow(new IllegalStateException("controlled test failure"));
        job.refreshRegions(List.of("230221100001"),now.plusSeconds(172800));
        assertThat(status()).isEqualTo("FAILED_RETAINED");
        assertThat(jdbc.sql("SELECT calculated_at::text FROM production.regional_hierarchy_calculation WHERE region_code='230221100001'").query(String.class).single()).isEqualTo(last);
    }
    @Test void siblingAllocationConservesTheParentTotalEvenWhenBoundariesAreIncomplete() {
        var boundaries=new JdbcRegionalAgricultureBoundaryRepository(jdbc);
        var children=jdbc.sql("SELECT code FROM platform.region WHERE parent_code='230200'").query(String.class).list();
        assertThat(children).isNotEmpty();
        var sum=children.stream().map(code -> boundaries.allocation(code,"230200").orElseThrow().share())
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(sum.subtract(BigDecimal.ONE).abs()).isLessThan(new BigDecimal("0.000000001"));
    }
    private String status() { return jdbc.sql("SELECT calculation_status FROM production.regional_hierarchy_calculation WHERE region_code='230221100001'").query(String.class).single(); }
}

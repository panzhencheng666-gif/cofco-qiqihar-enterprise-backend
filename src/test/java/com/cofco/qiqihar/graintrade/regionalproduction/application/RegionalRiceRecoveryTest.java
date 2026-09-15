package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.cofco.qiqihar.graintrade.shared.security.application.*;

class RegionalRiceRecoveryTest {
    @Test void missingLocalYieldUsesExplicitPublicRegionalReferenceInsteadOfHidingRice() {
        var annual=mock(RegionalCropAnnualStatRepository.class);
        var summaries=mock(RegionalCropSummaryRepository.class);
        var boundaries=mock(RegionalAgricultureBoundaryRepository.class);
        var data=mock(RegionalPublicDataRepository.class);
        var access=mock(AccessControl.class);
        when(access.requireBusinessReadScope()).thenReturn(new AuthorizedReadScope("test",Set.of("*")));
        when(annual.region("150700")).thenReturn(Optional.of(new RegionalCropAnnualStatRepository.RegionDescriptor("150700","呼伦贝尔市",null,"PREFECTURE")));
        when(boundaries.areaSquareMetres(any())).thenReturn(Optional.of(new BigDecimal("1000000")));
        when(boundaries.facts(any())).thenReturn(new RegionalAgricultureBoundaryRepository.RegionFacts(new BigDecimal("1000000"),0,0,0,0));
        when(summaries.summarize(eq(2026),eq("RICE"),eq("150700"),any())).thenReturn(Optional.of(summary(2026,"1000")));
        when(data.history(any(),anyInt())).thenReturn(List.of());
        when(data.history("231100",2026)).thenReturn(List.of(new RegionalAgricultureProfile.Indicator("CROP_GRAIN","稻谷平均单产",new BigDecimal("600"),"公斤/亩",2025,"OBSERVED","公开单产","黑河统计公报","https://example.org/heihe",null)));
        when(data.load(any(),anyInt())).thenReturn(new RegionalPublicDataRepository.Context(List.of(),null,null,List.of(),List.of(),List.of()));
        when(data.load("231100",2026)).thenReturn(new RegionalPublicDataRepository.Context(List.of(),null,null,List.of(),List.of(),List.of(
                new RegionalAgricultureProfile.Source("heihe-proof","AGRICULTURE","黑河统计公报","https://example.org/heihe","OFFICIAL",BigDecimal.ONE,null,null,"SUCCESS","公开单产"))));
        var p=new RegionalAgricultureProfileService(annual,summaries,boundaries,new RegionalAgricultureProfileCalculator(),data,access).profile(2026,"150700");
        assertThat(p.sources()).anyMatch(source -> source.url().equals("https://example.org/heihe"));
        assertThat(p.crops()).singleElement().satisfies(c -> {
            assertThat(c.yieldPerMuKg()).isEqualByComparingTo("600");
            assertThat(c.totalOutputKg()).isEqualByComparingTo("600000");
            assertThat(c.basis()).contains("参考情景","黑河","2025","https://example.org/heihe");
        });
        when(summaries.summarize(eq(2026),eq("RICE"),eq("150700"),any())).thenReturn(Optional.empty());
        when(summaries.summarize(eq(2026),eq("CORN"),eq("150700"),any())).thenReturn(Optional.of(summary(2026,"10000")));
        when(summaries.summarize(eq(2026),eq("RICE"),eq("231100"),any())).thenReturn(Optional.of(summary(2026,"2000")));
        when(summaries.summarize(eq(2026),eq("CORN"),eq("231100"),any())).thenReturn(Optional.of(summary(2026,"10000")));
        var missingArea=new RegionalAgricultureProfileService(annual,summaries,boundaries,new RegionalAgricultureProfileCalculator(),data,access).profile(2026,"150700");
        assertThat(missingArea.crops()).filteredOn(c -> c.productCode().equals("RICE")).singleElement().satisfies(c -> {
            assertThat(c.plantedAreaMu()).isEqualByComparingTo("2000");
            assertThat(c.basis()).contains("当地其余两类作物10000亩","面积比中位数0.2");
        });
    }
    @Test void derivesRiceYieldFromSameYearPublicOutputAndRegionalArea() {
        var annual=mock(RegionalCropAnnualStatRepository.class);
        var summaries=mock(RegionalCropSummaryRepository.class);
        var boundaries=mock(RegionalAgricultureBoundaryRepository.class);
        var data=mock(RegionalPublicDataRepository.class);
        var access=mock(AccessControl.class);
        when(access.requireBusinessReadScope()).thenReturn(new AuthorizedReadScope("test",Set.of("*")));
        when(annual.region("230200")).thenReturn(Optional.of(new RegionalCropAnnualStatRepository.RegionDescriptor("230200","齐齐哈尔市",null,"PREFECTURE")));
        when(boundaries.areaSquareMetres(any())).thenReturn(Optional.of(new BigDecimal("1000000")));
        when(boundaries.facts(any())).thenReturn(new RegionalAgricultureBoundaryRepository.RegionFacts(new BigDecimal("1000000"),16,16,118,1197));
        when(summaries.summarize(eq(2026),eq("RICE"),any(),any())).thenReturn(Optional.of(summary(2026,"5354460")));
        when(summaries.summarize(eq(2025),eq("RICE"),any(),any())).thenReturn(Optional.of(summary(2025,"5364140")));
        var output=new RegionalAgricultureProfile.Indicator("CROP_GRAIN","稻谷产量",new BigDecimal("284.5"),"万吨",2025,"OBSERVED","公开总产","统计公报","https://example.org/report","2026-09-14T00:30:00Z");
        when(data.history(any(),anyInt())).thenReturn(List.of(output));
        when(data.load(any(),anyInt())).thenReturn(new RegionalPublicDataRepository.Context(List.of(),null,null,List.of(output),List.of(),List.of()));
        var service=new RegionalAgricultureProfileService(annual,summaries,boundaries,new RegionalAgricultureProfileCalculator(),data,access);
        var profile=service.profile(2026,"230200");
        assertThat(profile.crops()).singleElement().satisfies(crop -> {
            assertThat(crop.productCode()).isEqualTo("RICE");
            assertThat(crop.productName()).isEqualTo("稻谷");
            assertThat(crop.yieldPerMuKg()).isBetween(new BigDecimal("530"),new BigDecimal("531"));
            assertThat(crop.basis()).contains("2025","284.5","5364140","2026","同年");
            assertThat(crop.dataKind()).isEqualTo("MODEL_ESTIMATE");
        });
    }
    private static RegionalCropSummary summary(int year,String area) {
        return new RegionalCropSummary("230200","齐齐哈尔市","PREFECTURE",year,"RICE",new BigDecimal(area),null,null,null,null,true,false,false,"");
    }
}

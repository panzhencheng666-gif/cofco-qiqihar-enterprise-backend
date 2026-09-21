package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.cofco.qiqihar.graintrade.shared.security.application.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class RegionalHierarchyEstimateTest {
    @Test void fillsCountyTownshipAndVillageFromNearestParentAndRecalculatesWhenInputsChange() {
        var annual = mock(RegionalCropAnnualStatRepository.class);
        var summaries = mock(RegionalCropSummaryRepository.class);
        var boundaries = mock(RegionalAgricultureBoundaryRepository.class);
        var data = mock(RegionalPublicDataRepository.class);
        var access = mock(AccessControl.class);
        when(access.requireBusinessReadScope()).thenReturn(new AuthorizedReadScope("test", Set.of("*")));
        String[] codes = {"230200", "230221", "230221100", "230221100001"};
        String[] names = {"齐齐哈尔市", "龙江县", "龙江镇", "龙东村"};
        String[] levels = {"PREFECTURE", "COUNTY", "TOWNSHIP", "VILLAGE"};
        for (int i=0; i<codes.length; i++) {
            when(annual.region(codes[i])).thenReturn(Optional.of(new RegionalCropAnnualStatRepository.RegionDescriptor(
                    codes[i], names[i], i==0 ? null : codes[i-1], levels[i])));
            when(boundaries.areaSquareMetres(codes[i])).thenReturn(Optional.of(BigDecimal.valueOf(Math.pow(10, 6-i))));
            when(boundaries.facts(codes[i])).thenReturn(new RegionalAgricultureBoundaryRepository.RegionFacts(
                    BigDecimal.valueOf(Math.pow(10, 6-i)),10,0,0,0));
            if (i>0) when(boundaries.allocation(codes[i],codes[i-1])).thenReturn(Optional.of(
                    new RegionalAgricultureBoundaryRepository.Allocation(new BigDecimal("0.1"),"同级面积权重")));
        }
        when(data.history(any(),anyInt())).thenReturn(List.of());
        when(data.load(any(),anyInt())).thenReturn(new RegionalPublicDataRepository.Context(
                List.of(),null,null,List.of(new RegionalAgricultureProfile.Indicator("CROP_VEGETABLE","蔬菜产量",
                    new BigDecimal("1000"),"万吨",2025,"OBSERVED","统计公报","齐齐哈尔统计公报","https://example.org/report",null)),List.of(),List.of()));
        for (String crop : List.of("CORN","SOYBEAN","RICE")) {
            when(summaries.summarize(2026,crop,"230200",Set.of("*"))).thenReturn(Optional.of(
                    new RegionalCropSummary("230200","齐齐哈尔市","PREFECTURE",2026,crop,
                            new BigDecimal("100000"),new BigDecimal("500"),new BigDecimal("50000000"),null,null,true,false,false,"")));
        }
        var service = new RegionalAgricultureProfileService(annual,summaries,boundaries,new RegionalAgricultureProfileCalculator(),data,access);
        var village = service.profile(2026,codes[3]);
        assertThat(village.indicators()).singleElement().satisfies(i -> {
            assertThat(i.value()).isEqualByComparingTo("1");
            assertThat(i.dataKind()).isEqualTo("ESTIMATED");
            assertThat(i.method()).contains("1000","0.001","假设","https://example.org/report");
        });
        assertThat(village.crops()).hasSize(3).allSatisfy(crop -> {
            assertThat(crop.plantedAreaMu()).isEqualByComparingTo("100");
            assertThat(crop.totalOutputKg()).isEqualByComparingTo("50000");
            assertThat(crop.dataKind()).isEqualTo("MODEL_ESTIMATE");
            assertThat(crop.basis()).contains("龙江镇","龙江县","齐齐哈尔市","2026","假设");
        });
        when(summaries.summarize(2026,"RICE","230221",Set.of("*"))).thenReturn(Optional.of(
                new RegionalCropSummary("230221","龙江县","COUNTY",2026,"RICE",
                        new BigDecimal("20000"),new BigDecimal("600"),new BigDecimal("12000000"),null,null,true,false,false,"")));
        var updated = service.profile(2026,codes[3]).crops().stream().filter(c -> c.productCode().equals("RICE")).findFirst().orElseThrow();
        assertThat(updated.plantedAreaMu()).isEqualByComparingTo("200");
        assertThat(updated.totalOutputKg()).isEqualByComparingTo("120000");
    }
}

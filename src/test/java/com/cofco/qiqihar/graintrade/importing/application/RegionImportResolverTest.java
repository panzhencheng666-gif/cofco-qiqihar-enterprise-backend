package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.masterdata.importing.RegionImportDirectory;
import com.cofco.qiqihar.graintrade.masterdata.importing.RegionImportDirectory.RegionEntry;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionImportResolverTest {

    private final RegionImportResolver resolver = new RegionImportResolver(directory());

    @Test
    void resolvesUniqueBusinessFriendlyRegionNamesWithoutRequiringFormalSuffixes() {
        Map.ofEntries(
                Map.entry("克东", "230230"),
                Map.entry("甘南", "230225"),
                Map.entry("五大连池", "231182"),
                Map.entry("阿荣", "150721"),
                Map.entry("龙江", "230221"),
                Map.entry("北安", "231181"),
                Map.entry("孙吴", "231124"),
                Map.entry("拜泉", "230231"),
                Map.entry("克山", "230229"),
                Map.entry("依安", "230223"),
                Map.entry("讷河", "230281"),
                Map.entry("泰来", "230224"),
                Map.entry("嫩江", "231183"),
                Map.entry("逊克", "231123"),
                Map.entry("扎兰屯", "150783"),
                Map.entry("富裕", "230227"),
                Map.entry("鄂伦春", "150723"),
                Map.entry("莫力达瓦达斡尔族自治", "150722"),
                Map.entry("梅里斯达斡尔族", "230208"),
                Map.entry("瑷珲", "231102"))
                .forEach((input, expected) -> assertThat(resolver.resolve(input))
                        .as("地区简称 %s", input)
                        .isEqualTo(expected));
    }

    @Test
    void keepsCodeOfficialNameAndFullPathCompatible() {
        assertThat(resolver.resolve("230230")).isEqualTo("230230");
        assertThat(resolver.resolve("克东县")).isEqualTo("230230");
        assertThat(resolver.resolve("齐齐哈尔市 / 克东县")).isEqualTo("230230");
    }

    @Test
    void resolvesAUniqueCountyFromConcatenatedProvinceCityCountyAndVillageText() {
        Map.of(
                "黑龙江齐齐哈尔富裕县二道湾村", "230227",
                "黑龙江齐齐哈尔梅里斯小红星村", "230208",
                "黑龙江齐齐哈尔甘南平阳", "230225",
                "黑龙江齐齐哈尔龙江鲁河", "230221",
                "梅里斯区", "230208")
                .forEach((input, expected) -> assertThat(resolver.resolve(input))
                        .as("业务地区 %s", input)
                        .isEqualTo(expected));
    }

    @Test
    void rejectsConcatenatedVillageTextWhenNoCountyCanBeUniquelyIdentified() {
        assertThatThrownBy(() -> resolver.resolve("黑龙江齐齐哈尔友谊村二道湾村"))
                .isInstanceOfSatisfying(ClientRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_REGION_NOT_FOUND"));
    }

    @Test
    void rejectsAnAmbiguousShortNameInsteadOfGuessing() {
        RegionImportResolver ambiguous = new RegionImportResolver(() -> List.of(
                new RegionEntry("100000", "示例市", null),
                new RegionEntry("100001", "新城区", "100000"),
                new RegionEntry("100002", "新城县", "100000")));

        assertThatThrownBy(() -> ambiguous.resolve("新城"))
                .isInstanceOfSatisfying(ClientRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_REGION_NOT_FOUND"));
    }

    private static RegionImportDirectory directory() {
        return () -> List.of(
                new RegionEntry("230200", "齐齐哈尔市", null),
                new RegionEntry("230208", "梅里斯达斡尔族区", "230200"),
                new RegionEntry("230221", "龙江县", "230200"),
                new RegionEntry("230223", "依安县", "230200"),
                new RegionEntry("230224", "泰来县", "230200"),
                new RegionEntry("230225", "甘南县", "230200"),
                new RegionEntry("230227", "富裕县", "230200"),
                new RegionEntry("230229", "克山县", "230200"),
                new RegionEntry("230230", "克东县", "230200"),
                new RegionEntry("230231", "拜泉县", "230200"),
                new RegionEntry("230281", "讷河市", "230200"),
                new RegionEntry("231100", "黑河市", null),
                new RegionEntry("231102", "爱辉区", "231100"),
                new RegionEntry("231123", "逊克县", "231100"),
                new RegionEntry("231124", "孙吴县", "231100"),
                new RegionEntry("231181", "北安市", "231100"),
                new RegionEntry("231182", "五大连池市", "231100"),
                new RegionEntry("231183", "嫩江市", "231100"),
                new RegionEntry("150700", "呼伦贝尔市", null),
                new RegionEntry("150721", "阿荣旗", "150700"),
                new RegionEntry("150722", "莫力达瓦达斡尔族自治旗", "150700"),
                new RegionEntry("150723", "鄂伦春自治旗", "150700"),
                new RegionEntry("150783", "扎兰屯市", "150700"));
    }
}

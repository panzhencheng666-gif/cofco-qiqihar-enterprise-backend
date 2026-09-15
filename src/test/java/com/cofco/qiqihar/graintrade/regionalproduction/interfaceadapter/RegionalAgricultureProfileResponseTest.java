package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureProfile;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalEstimateBatch;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalAgricultureProfileResponseTest {
    @Test
    void exposesComparisonsOnlyForTheRequestedGeographyAndYear() {
        for (String root : List.of("230200", "231100", "150700", "232700")) {
            var batch = new RegionalEstimateBatch(root, 2026, "", "", "", "", "", "", List.of());
            assertSame(batch, RegionalAgricultureProfileResponse.from(profile(root, 2026), batch).estimateBatch());
            for (String child : List.of(root.substring(0, 4) + "21", root.substring(0, 4) + "21100", root.substring(0, 4) + "21100001")) {
                assertNull(RegionalAgricultureProfileResponse.from(profile(child, 2026), batch).estimateBatch());
            }
            assertNull(RegionalAgricultureProfileResponse.from(profile(root, 2025), batch).estimateBatch());
        }
    }

    private RegionalAgricultureProfile profile(String code, int year) {
        return new RegionalAgricultureProfile(code, "地区", "", year, true, "", "", null, "", "", null, null,
                List.of(), List.of(), List.of(), List.of());
    }
}

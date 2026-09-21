package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.importing.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ImportJobViewTest {
    @Test void exposesActualRowAndFieldWithoutLeakingTheSubmittedValues() {
        var job = new ImportJob(UUID.randomUUID(), "PRODUCTION", "test", "digest", "test", "TEST",
                null, "COMPLETED_WITH_ERRORS", Instant.now(), Instant.now(), List.of(
                ImportRowOutcome.error(7, "IMPORT_ROW_VALUE_FORMAT", "播种面积：数值不能为负数。",
                        Map.of("PROD_SAMPLE_CONTACT", "private-contact"))));
        var json = new ObjectMapper().valueToTree(ImportJobView.from(job));
        assertThat(json.path("rowErrors").path(0).path("rowNumber").asInt()).isEqualTo(7);
        assertThat(json.path("rowErrors").path(0).path("field").asText()).isEqualTo("播种面积");
        assertThat(json.path("rowErrors").path(0).path("message").asText()).isEqualTo("数值不能为负数。");
        assertThat(json.toString()).doesNotContain("private-contact");
    }
}

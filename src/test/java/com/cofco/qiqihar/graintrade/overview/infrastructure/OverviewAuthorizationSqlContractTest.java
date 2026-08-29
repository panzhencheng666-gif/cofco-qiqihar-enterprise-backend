package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewAuthorizationSqlContractTest {
    private static final String SCALAR_ARRAY_AUTHORIZATION =
            "=ANY(string_to_array(:authorizedRegionList,','))";
    private static final String SET_BASED_AUTHORIZATION =
            "IN (SELECT unnest(string_to_array(:authorizedRegionList,',')))";

    @Test
    void usesSetBasedAuthorizationForLargeOverviewRegionScopes() throws IOException {
        for (Path source : List.of(
                Path.of("src/main/java/com/cofco/qiqihar/graintrade/analysis/infrastructure/"
                        + "JdbcObservableAnalysisRepository.java"),
                Path.of("src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/"
                        + "JdbcOverviewRepository.java"),
                Path.of("src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/"
                        + "JdbcOverviewSamplePointRepository.java"))) {
            String sqlSource = Files.readString(source);
            assertThat(sqlSource)
                    .as("%s must not compare every region against every authorized array item", source)
                    .doesNotContain(SCALAR_ARRAY_AUTHORIZATION)
                    .contains(SET_BASED_AUTHORIZATION);
            if (sqlSource.contains("current_valid_sample(sample_point_id)")) {
                assertThat(sqlSource)
                        .as("%s must compute the approved sample set once per overview query", source)
                        .doesNotContain("current_valid_sample(sample_point_id) AS (")
                        .contains("current_valid_sample(sample_point_id) AS MATERIALIZED (");
            }
        }
    }

    @Test
    void joinsThePublishedMapBoundaryOnceInsteadOfRevalidatingEverySourceRow()
            throws IOException {
        Path source = Path.of("src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/"
                + "JdbcOverviewSamplePointRepository.java");
        String sqlSource = Files.readString(source);

        assertThat(sqlSource)
                .contains("LEFT JOIN overview.administrative_boundary_render published_boundary")
                .doesNotContain("LEFT JOIN overview.administrative_boundary containment_boundary")
                .contains("source.unresolved_reason IS NULL")
                .contains("point.approval_state='APPROVED'")
                .contains("point.location_state='VALID'")
                .doesNotContain("FROM overview.administrative_boundary boundary\n"
                        + "                           JOIN overview.administrative_boundary_render");
    }
}

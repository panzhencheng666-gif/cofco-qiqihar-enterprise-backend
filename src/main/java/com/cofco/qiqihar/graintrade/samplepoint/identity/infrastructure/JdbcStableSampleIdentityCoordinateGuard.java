package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.StableSampleIdentityCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStableSampleIdentityCoordinateGuard implements StableSampleIdentityCoordinateGuard {
    private final JdbcClient jdbc;

    public JdbcStableSampleIdentityCoordinateGuard(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void requireCompatible(
            String canonicalName, String contact, BigDecimal longitude, BigDecimal latitude) {
        if (canonicalName == null || canonicalName.isBlank() || contact == null || contact.isBlank()) return;
        List<Coordinate> coordinates = jdbc.sql("""
                WITH candidate AS (
                  SELECT record.sample_point_id
                  FROM production.production_record record
                  JOIN production.production_record_submission_metadata sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='PROD_SAMPLE_NAME'
                  JOIN production.production_record_submission_metadata sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='PROD_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                    AND regexp_replace(lower(btrim(sample_name.value)),'[[:space:]]+','','g')=:nameKey
                    AND regexp_replace(lower(btrim(sample_contact.value)),'[[:space:]()（）-]+','','g')=:contactKey
                  UNION
                  SELECT record.sample_point_id
                  FROM market.market_record record
                  JOIN market.market_record_core_value sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='MKT_SAMPLE_NAME'
                  JOIN market.market_record_core_value sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='MKT_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                    AND regexp_replace(lower(btrim(sample_name.value)),'[[:space:]]+','','g')=:nameKey
                    AND regexp_replace(lower(btrim(sample_contact.value)),'[[:space:]()（）-]+','','g')=:contactKey
                )
                SELECT DISTINCT ST_X(point.governed_point) longitude,
                                ST_Y(point.governed_point) latitude
                FROM candidate
                JOIN registry.sample_point point ON point.sample_point_id=candidate.sample_point_id
                WHERE point.kind_code='SURVEY_SITE' AND point.approval_state='APPROVED'
                  AND point.location_state='VALID' AND point.governed_point IS NOT NULL
                """).param("nameKey", normalizedName(canonicalName))
                .param("contactKey", normalizedContact(contact))
                .query((row, ignored) -> new Coordinate(
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude")))
                .list();
        if (coordinates.size() > 1) {
            throw new ConflictException("SAMPLE_IDENTITY_CONFLICT",
                    "同一样本身份已关联多个正式位置，请先完成身份治理");
        }
        if (coordinates.size() == 1 && !coordinates.getFirst().matches(longitude, latitude)) {
            throw new ConflictException("SAMPLE_IDENTITY_COORDINATE_MISMATCH",
                    "同一样本身份的经纬度与已有正式样本点不一致，请按位置变更流程处理");
        }
    }

    private static String normalizedName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000]+", "");
    }

    private static String normalizedContact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000()（）-]+", "");
    }

    private record Coordinate(BigDecimal longitude, BigDecimal latitude) {
        private boolean matches(BigDecimal submittedLongitude, BigDecimal submittedLatitude) {
            return longitude != null && latitude != null
                    && longitude.compareTo(submittedLongitude) == 0
                    && latitude.compareTo(submittedLatitude) == 0;
        }
    }
}

package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OperationalFacilityCatalogue;
import com.cofco.qiqihar.graintrade.overview.application.OperationalStorageFacilityRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOperationalStorageFacilityRepository implements OperationalStorageFacilityRepository {
    private final JdbcClient jdbc;

    public JdbcOperationalStorageFacilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OperationalFacilityCatalogue.StorageFacility> find(
            String regionCode, String productCode, LocalDate asOf) {
        return jdbc.sql("""
                WITH RECURSIVE selected_regions(code) AS (
                  SELECT code FROM platform.region
                  WHERE code=:region OR (:region IS NULL AND administrative_level='PREFECTURE')
                  UNION ALL
                  SELECT child.code FROM platform.region child JOIN selected_regions parent ON child.parent_code=parent.code
                )
                SELECT facility.*,region.name AS region_name,
                  CASE WHEN facility.geometry IS NULL THEN NULL ELSE ST_X(facility.geometry) END AS longitude,
                  CASE WHEN facility.geometry IS NULL THEN NULL ELSE ST_Y(facility.geometry) END AS latitude
                FROM overview.storage_facility facility
                JOIN selected_regions selected ON selected.code=facility.region_code
                JOIN platform.region region ON region.code=facility.region_code
                WHERE (facility.valid_from IS NULL OR facility.valid_from<=:as_of)
                  AND (facility.valid_to IS NULL OR facility.valid_to>=:as_of)
                ORDER BY CASE facility.relation_type WHEN 'OWNED' THEN 1 WHEN 'LEASED' THEN 2 ELSE 3 END,
                  facility.facility_name,facility.facility_code
                """).param("region", regionCode, java.sql.Types.VARCHAR).param("as_of", asOf)
                .query((rs, row) -> {
                    String code = rs.getString("facility_code");
                    return new OperationalFacilityCatalogue.StorageFacility(
                            code, rs.getString("facility_name"), rs.getString("work_unit_code"),
                            rs.getString("relation_type"), relationLabel(rs.getString("relation_type")),
                            rs.getString("region_code"), rs.getString("region_name"), rs.getString("address"),
                            rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"),
                            rs.getString("coordinate_precision"), precisionLabel(rs.getString("coordinate_precision")),
                            rs.getString("operational_status"), rs.getBigDecimal("capacity_tonnes"),
                            localDate(rs.getDate("capacity_as_of")), prices(code, productCode, asOf), evidence(code));
                }).list();
    }

    @Override
    public String latestSourceAsOf(String regionCode) {
        return jdbc.sql("""
                WITH RECURSIVE selected_regions(code) AS (
                  SELECT code FROM platform.region
                  WHERE code=:region OR (:region IS NULL AND administrative_level='PREFECTURE')
                  UNION ALL
                  SELECT child.code FROM platform.region child JOIN selected_regions parent ON child.parent_code=parent.code
                )
                SELECT max(evidence.source_as_of) latest
                FROM overview.storage_facility_evidence evidence
                JOIN overview.storage_facility facility USING(facility_code)
                JOIN selected_regions selected ON selected.code=facility.region_code
                """).param("region", regionCode, java.sql.Types.VARCHAR)
                .query((rs, row) -> localDate(rs.getDate("latest")))
                .optional().map(LocalDate::toString).orElse(null);
    }

    @Override
    public List<String> railwayRegionCodes(String regionCode) {
        return jdbc.sql("""
                SELECT code FROM platform.region
                WHERE code=:region OR (:region IS NULL AND administrative_level='PREFECTURE')
                ORDER BY sort_order,code
                """).param("region", regionCode, java.sql.Types.VARCHAR).query(String.class).list();
    }

    private List<OperationalFacilityCatalogue.Price> prices(String facilityCode, String productCode, LocalDate asOf) {
        return jdbc.sql("""
                SELECT price.*,product.name AS product_name
                FROM overview.storage_facility_price price
                LEFT JOIN platform.product product ON product.code=price.product_code
                WHERE price.facility_code=:facility
                  AND (:product IS NULL OR price.product_code=:product)
                  AND price.effective_on<=:as_of
                ORDER BY price.effective_on DESC,price.product_code,price.quality_requirement
                """).param("facility", facilityCode).param("product", productCode).param("as_of", asOf)
                .query((rs, row) -> {
                    LocalDate effectiveOn = rs.getDate("effective_on").toLocalDate();
                    LocalDate expiresOn = localDate(rs.getDate("expires_on"));
                    boolean current = !effectiveOn.isBefore(asOf.minusDays(30))
                            && (expiresOn == null || !expiresOn.isBefore(asOf));
                    return new OperationalFacilityCatalogue.Price(
                            rs.getString("product_code"), rs.getString("product_name"),
                            rs.getString("quality_requirement"), rs.getBigDecimal("price_value"),
                            rs.getString("price_unit"), effectiveOn, expiresOn, rs.getString("source_name"),
                            rs.getString("source_url"), rs.getString("source_classification"), current);
                }).list();
    }

    private List<OperationalFacilityCatalogue.Evidence> evidence(String facilityCode) {
        return jdbc.sql("""
                SELECT * FROM overview.storage_facility_evidence
                WHERE facility_code=:facility
                ORDER BY source_as_of DESC NULLS LAST,evidence_kind,title
                """).param("facility", facilityCode)
                .query((rs, row) -> new OperationalFacilityCatalogue.Evidence(
                        rs.getString("evidence_kind"), rs.getString("title"), rs.getString("source_name"),
                        rs.getString("source_url"), rs.getString("source_classification"),
                        localDate(rs.getDate("source_as_of")), rs.getString("note"))).list();
    }

    private static LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private static String relationLabel(String code) {
        return switch (code) {
            case "OWNED" -> "自有库点";
            case "LEASED" -> "租赁库点";
            case "HISTORICAL_LEASED" -> "历史租赁库点";
            default -> code;
        };
    }
    private static String precisionLabel(String code) {
        return switch (code) {
            case "EXACT" -> "已核定精确位置";
            case "STREET" -> "公开地址街道级近似位置";
            case "TOWN" -> "乡镇级近似位置";
            default -> "位置尚未核定";
        };
    }
}

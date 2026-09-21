package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Latest materialized calculation for every registered region; independent of browser requests. */
@Service
public class RegionalHierarchyRefresh {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RegionalHierarchyRefresh.class);
    private final JdbcClient jdbc;
    private final RegionalAgricultureProfileService profiles;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public RegionalHierarchyRefresh(JdbcClient jdbc, RegionalAgricultureProfileService profiles) {
        this.jdbc=jdbc; this.profiles=profiles;
    }
    public record CalculationStatus(String status,String attemptedAt,String calculatedAt,String sourceStatus) {}
    public CalculationStatus status(String region,int year) {
        return jdbc.sql("SELECT calculation_status,attempted_at,calculated_at,source_status FROM production.regional_hierarchy_calculation WHERE region_code=:region AND data_year=:year")
                .param("region",region).param("year",year).query((rs,row) -> new CalculationStatus(rs.getString(1),
                    rs.getTimestamp(2).toInstant().toString(),rs.getTimestamp(3)==null?null:rs.getTimestamp(3).toInstant().toString(),rs.getString(4)))
                .optional().orElse(null);
    }
    public void refresh(Instant now) {
        var regions=jdbc.sql("""
            SELECT code FROM platform.region
            WHERE left(code,4) IN ('2302','2311','1507','2327')
            ORDER BY CASE administrative_level WHEN 'PREFECTURE' THEN 0 WHEN 'COUNTY' THEN 1 WHEN 'TOWNSHIP' THEN 2 ELSE 3 END,code
            """).query(String.class).list();
        refreshRegions(regions,now);
    }

    void refreshRegions(List<String> regions, Instant now) {
        int year=now.atZone(ZoneId.of("Asia/Shanghai")).getYear();
        var completed=new HashMap<String,RegionalAgricultureProfile>();
        int success=0, failures=0;
        for (String code:regions) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                var p=profiles.profileForRefresh(year,code,completed);
                var indicators=p.indicators().stream().map(i -> Arrays.asList(i.category(),i.label(),i.value(),i.unit(),
                        i.dataYear(),i.dataKind(),i.method(),i.sourceName(),i.sourceUrl())).toList();
                String json=mapper.writeValueAsString(Map.of("crops",p.crops(),"indicators",indicators,"regionFacts",p.regionFacts()));
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
                jdbc.sql("""
                    INSERT INTO production.regional_hierarchy_calculation(region_code,data_year,attempted_at,calculated_at,
                        calculation_status,source_status,input_hash,payload)
                    VALUES(:code,:year,:now,:now,'RECALCULATED_CHANGED',:source,:hash,CAST(:payload AS jsonb))
                    ON CONFLICT(region_code,data_year) DO UPDATE SET attempted_at=EXCLUDED.attempted_at,calculated_at=EXCLUDED.calculated_at,
                        calculation_status=CASE WHEN regional_hierarchy_calculation.input_hash=EXCLUDED.input_hash
                          THEN 'RECALCULATED_UNCHANGED' ELSE 'RECALCULATED_CHANGED' END,
                        source_status=EXCLUDED.source_status,input_hash=EXCLUDED.input_hash,payload=EXCLUDED.payload
                    """).param("code",code).param("year",year).param("now",java.sql.Timestamp.from(Instant.now()))
                        .param("source",p.refreshStatus()==null?"UNKNOWN":p.refreshStatus().status())
                        .param("hash",hash).param("payload",json).update();
                success++;
            } catch (Exception e) {
                failures++;
                LOG.warn("Regional hierarchy calculation failed [region={}]",code,e);
                try { jdbc.sql("""
                    INSERT INTO production.regional_hierarchy_calculation(region_code,data_year,attempted_at,calculation_status,source_status)
                    VALUES(:code,:year,:now,'FAILED_RETAINED','UNKNOWN')
                    ON CONFLICT(region_code,data_year) DO UPDATE SET attempted_at=EXCLUDED.attempted_at,calculation_status='FAILED_RETAINED'
                    """).param("code",code).param("year",year).param("now",java.sql.Timestamp.from(Instant.now())).update();
                } catch (Exception statusFailure) { LOG.error("Unable to retain regional calculation failure [region={}]",code,statusFailure); }
            }
        }
        LOG.info("Regional hierarchy refresh finished: successful={}, failed={}, total={}",success,failures,regions.size());
    }
}

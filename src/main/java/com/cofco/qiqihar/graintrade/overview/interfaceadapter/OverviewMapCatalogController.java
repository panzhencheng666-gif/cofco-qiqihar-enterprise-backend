package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointIcon;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Read-only map catalogues. Business observations and contract values belong to detail APIs. */
@RestController
public class OverviewMapCatalogController {
    private final JdbcClient jdbc;
    private final AccessControl access;
    private final OverviewSamplePointService samples;
    public OverviewMapCatalogController(DataSource source, AccessControl access, OverviewSamplePointService samples) {
        this.jdbc = JdbcClient.create(source); this.access = access; this.samples = samples;
    }
    private static final String REGIONS = """
        WITH RECURSIVE scope(code) AS (
          SELECT code FROM platform.region WHERE code=:region
          UNION ALL SELECT r.code FROM platform.region r JOIN scope s ON r.parent_code=s.code
        ), region_path(code,path) AS (
          SELECT code,name::text FROM platform.region WHERE parent_code IS NULL
          UNION ALL SELECT r.code,p.path || ' / ' || r.name
          FROM platform.region r JOIN region_path p ON r.parent_code=p.code
        )
        """;

    @GetMapping("/api/v1/overview/map-samples")
    @Transactional(readOnly=true)
    ApiResponse<List<OverviewSamplePointIcon>> actual(@RequestParam Integer year,
            @RequestParam String productCode, @RequestParam String regionCode) {
        var scope=access.requireOverviewReadScope();
        // The established projection enforces source resolution, effective date and published hierarchy.
        var rows=new LinkedHashMap<UUID,OverviewSamplePointIcon>();
        samples.icons(year,productCode,regionCode,null,null,null).forEach(p -> rows.put(p.samplePointId(),p));
        var profiles=jdbc.sql(REGIONS + """
          SELECT p.sample_point_id,p.canonical_name,p.region_code,t.business_domain,t.name,t.code,
                 t.overview_icon_key,ST_X(p.display_point) lon,ST_Y(p.display_point) lat,p.location_mode
          FROM registry.sample_point p
          JOIN registry.formal_sample_point_profile f USING(sample_point_id)
          JOIN platform.object_type t ON t.code=f.object_type_code AND t.overview_enabled
          WHERE p.deletion_state='ACTIVE' AND p.approval_state='APPROVED'
            AND p.location_state='VALID' AND p.display_point IS NOT NULL
            AND COALESCE(p.display_region_code,p.region_code) IN (SELECT code FROM scope)
            AND p.effective_from<=LEAST(make_date(:year,12,31),CURRENT_DATE)
            AND (p.effective_to IS NULL OR p.effective_to>=LEAST(make_date(:year,12,31),CURRENT_DATE))
            AND (:all OR p.region_code=ANY(string_to_array(:allowed,',')))
          ORDER BY p.canonical_name,p.sample_point_id
          """).param("region",regionCode).param("year",year)
          .param("all",scope.isUnrestricted()).param("allowed",String.join(",",scope.regionCodes()))
          .query((rs,n) -> {
              String domain=rs.getString("business_domain");
              String icon=domain.toLowerCase(Locale.ROOT);
              String label=switch(domain){case "PRODUCTION" -> "产情类";case "MARKET" -> "市场类";default -> "物流类";};
              return new OverviewSamplePointIcon(rs.getObject("sample_point_id",UUID.class),rs.getString("canonical_name"),
                  rs.getString("region_code"),icon,List.of(new OverviewSamplePointIcon.RoleRef(domain,label,icon)),
                  List.of(new OverviewSamplePointIcon.TypeRef(rs.getString("code"),rs.getString("name"),rs.getString("overview_icon_key"))),
                  rs.getDouble("lon"),rs.getDouble("lat"),null,rs.getString("location_mode"));
          }).list();
        profiles.forEach(p -> rows.putIfAbsent(p.samplePointId(),p));
        return new ApiResponse<>(rows.values().stream().sorted(Comparator.comparing(OverviewSamplePointIcon::name)
            .thenComparing(p -> p.samplePointId().toString())).toList());
    }

    @GetMapping("/api/v1/overview/map-sample-counts")
    @Transactional(readOnly=true)
    ApiResponse<List<com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate>> counts(
            @RequestParam Integer year, @RequestParam String productCode,
            @RequestParam(required=false) String parentCode) {
        var scope=access.requireOverviewReadScope();
        var existing=samples.aggregates(year,productCode,parentCode);
        var extra=jdbc.sql("""
          WITH RECURSIVE profile_points AS MATERIALIZED (
            SELECT p.sample_point_id,p.display_region_code,p.region_code,t.business_domain
            FROM registry.sample_point p JOIN registry.formal_sample_point_profile f USING(sample_point_id)
            JOIN platform.object_type t ON t.code=f.object_type_code AND t.overview_enabled
            WHERE p.deletion_state='ACTIVE' AND p.approval_state='APPROVED' AND p.location_state='VALID'
              AND p.display_point IS NOT NULL
              AND p.effective_from<=LEAST(make_date(:year,12,31),CURRENT_DATE)
              AND (p.effective_to IS NULL OR p.effective_to>=LEAST(make_date(:year,12,31),CURRENT_DATE))
              AND (:all OR p.region_code=ANY(string_to_array(:allowed,',')))
          ), source_ids AS MATERIALIZED (
            SELECT DISTINCT COALESCE(r.target_sample_point_id,s.sample_point_id) id
            FROM overview.current_sample_point_query_source(:year,:product,false,false) s
            LEFT JOIN registry.current_sample_subject_resolution r
              ON r.source_domain=s.category_code AND r.source_record_id=s.source_record_id
            WHERE EXISTS(SELECT 1 FROM profile_points) AND s.unresolved_reason IS NULL
              AND r.resolution_action IS DISTINCT FROM 'VOID'
          ), ancestry(id,domain,code,parent_code) AS (
            SELECT p.sample_point_id,p.business_domain,r.code,r.parent_code
            FROM profile_points p JOIN platform.region r ON r.code=COALESCE(p.display_region_code,p.region_code)
            WHERE NOT EXISTS(SELECT 1 FROM source_ids s WHERE s.id=p.sample_point_id)
            UNION ALL SELECT a.id,a.domain,r.code,r.parent_code FROM ancestry a
            JOIN platform.region r ON r.code=a.parent_code
          )
          SELECT code,domain,count(DISTINCT id) n FROM ancestry
          WHERE parent_code IS NOT DISTINCT FROM CAST(:parent AS varchar)
          GROUP BY code,domain
          UNION ALL
          SELECT COALESCE(p.display_region_code,p.region_code),p.business_domain,count(*)
          FROM profile_points p WHERE COALESCE(p.display_region_code,p.region_code)=:parent
            AND NOT EXISTS(SELECT 1 FROM source_ids s WHERE s.id=p.sample_point_id)
          GROUP BY COALESCE(p.display_region_code,p.region_code),p.business_domain
          """).param("year",year).param("product",productCode).param("parent",parentCode)
          .param("all",scope.isUnrestricted()).param("allowed",String.join(",",scope.regionCodes()))
          .query((rs,n)->Map.entry(rs.getString("code")+":"+rs.getString("domain"),rs.getLong("n"))).list();
        Map<String,Long> additions=new HashMap<>();extra.forEach(e->additions.put(e.getKey(),e.getValue()));
        if (parentCode != null && additions.keySet().stream().anyMatch(k -> k.startsWith(parentCode+":"))
                && existing.stream().noneMatch(a -> a.regionCode().equals(parentCode))) {
            var direct=jdbc.sql("SELECT name,administrative_level FROM platform.region WHERE code=:code")
                .param("code",parentCode).query((rs,n)->new com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate(
                    parentCode,"本级样本",rs.getString("administrative_level"),"PARENT_DIRECT",parentCode,0,0,0,0,0,0,0,0)).single();
            existing=new ArrayList<>(existing);existing.add(direct);
        }
        return new ApiResponse<>(existing.stream().map(a->{
            long production=additions.getOrDefault(a.regionCode()+":PRODUCTION",0L);
            long market=additions.getOrDefault(a.regionCode()+":MARKET",0L);
            long logistics=additions.getOrDefault(a.regionCode()+":LOGISTICS",0L);
            long total=production+market+logistics;
            return new com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate(
                a.regionCode(),a.regionName(),a.regionLevel(),a.scopeKind(),a.anchorRegionCode(),
                a.samplePointCount()+total,a.productionCount()+production,a.marketCount()+market,
                a.logisticsCount()+logistics,a.validCoordinateCount()+total,a.dataQualityIssueCount(),
                a.correctionSourceCount(),a.unresolvedSourceCount());
        }).toList());
    }

    @GetMapping("/api/v1/overview/design-map-samples")
    @Transactional(readOnly=true)
    ApiResponse<List<Map<String,Object>>> design(@RequestParam String productCode,
            @RequestParam String regionCode) {
        var scope=access.requireOverviewReadScope();
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:region)")
                .param("region",regionCode).query(Boolean.class).single())
            throw new ClientRequestException("OVERVIEW_QUERY_INVALID","Unknown map region");
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:product)")
                .param("product",productCode).query(Boolean.class).single())
            throw new ClientRequestException("OVERVIEW_QUERY_INVALID","Unknown product");
        var points=jdbc.sql(REGIONS + """
          SELECT p.design_sample_point_id,p.contract_version,c.contract_digest,p.domain_code,p.product_code,
                 p.object_type_code,p.sample_name,p.region_code,r.path,
                 ST_X(p.governed_point) lon,ST_Y(p.governed_point) lat,
                 ST_X(p.display_point) display_lon,ST_Y(p.display_point) display_lat,
                 p.display_region_code,p.location_mode,p.version,p.updated_at
          FROM platform.design_sample_point p
          JOIN platform.design_sample_contract c ON c.contract_version=p.contract_version
          JOIN region_path r ON r.code=p.region_code
          WHERE p.product_code=:product AND p.region_code IN (SELECT code FROM scope)
            AND (:all OR p.region_code=ANY(string_to_array(:allowed,',')))
          ORDER BY p.sample_name,p.design_sample_point_id
          """).param("region",regionCode).param("product",productCode)
          .param("all",scope.isUnrestricted()).param("allowed",String.join(",",scope.regionCodes()))
          .query((rs,n) -> {
              Map<String,Object> point=new LinkedHashMap<>();
              point.put("id",rs.getString("design_sample_point_id"));
              point.put("contractVersion",rs.getString("contract_version"));
              point.put("contractDigest",rs.getString("contract_digest"));
              point.put("context",Map.of("domainCode",rs.getString("domain_code"),"productCode",rs.getString("product_code"),"objectTypeCode",rs.getString("object_type_code")));
              point.put("name",rs.getString("sample_name"));point.put("regionCode",rs.getString("region_code"));point.put("regionPath",rs.getString("path"));
              point.put("longitude",rs.getDouble("lon"));point.put("latitude",rs.getDouble("lat"));
              if(rs.getObject("display_lon")!=null){point.put("displayLongitude",rs.getDouble("display_lon"));point.put("displayLatitude",rs.getDouble("display_lat"));}
              if(rs.getString("display_region_code")!=null)point.put("displayRegionCode",rs.getString("display_region_code"));
              if(rs.getString("location_mode")!=null)point.put("locationMode",rs.getString("location_mode"));
              point.put("version",rs.getLong("version"));point.put("updatedAt",rs.getTimestamp("updated_at").toInstant().toString());
              return point;
          }).list();
        return new ApiResponse<>(points);
    }
}

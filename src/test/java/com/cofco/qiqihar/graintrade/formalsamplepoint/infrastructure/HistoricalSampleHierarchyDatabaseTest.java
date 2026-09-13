package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.overview.infrastructure.JdbcOverviewSamplePointRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HistoricalSampleHierarchyDatabaseTest {
    private static final String COUNTY="230299", TOWN="230299901", OTHER="230299902", VILLAGE="230299901001";
    @Test void ledgerAndMapShareDisplayHierarchyRetirementYearAndLatestApprovedRecord() {
        var database=ProtectedTestDatabase.shared(); database.flyway().migrate();
        var ds=database.dataSource(); var jdbc=JdbcClient.create(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
            status.setRollbackOnly();
            GovernedMasterDataFixtures.insertRegion(jdbc,COUNTY,"历史层级隔离县","230200","COUNTY",99990);
            GovernedMasterDataFixtures.insertRegion(jdbc,TOWN,"历史层级隔离乡甲",COUNTY,"TOWNSHIP",99991);
            GovernedMasterDataFixtures.insertRegion(jdbc,OTHER,"历史层级隔离乡乙",COUNTY,"TOWNSHIP",99992);
            GovernedMasterDataFixtures.insertRegion(jdbc,VILLAGE,"历史层级隔离村",TOWN,"VILLAGE",99993);
            boundary(jdbc,COUNTY,123.0,123.6);boundary(jdbc,TOWN,123.0,123.3);
            boundary(jdbc,OTHER,123.3,123.6);boundary(jdbc,VILLAGE,123.0,123.3);
            String actor="history-"+UUID.randomUUID().toString().substring(0,8);
            jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES(:s,:s,99990)").param("s",actor).update();
            jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,:s)").param("s",actor).update();
            jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:s,'SYSTEM_ADMIN')").param("s",actor).update();
            String prefix="历史层级样本"+actor;
            UUID countyPoint=sample(jdbc,COUNTY,123.2,actor,prefix+"甲");
            UUID villagePoint=sample(jdbc,VILLAGE,123.22,actor,prefix+"乙");
            UUID latest=production(jdbc,countyPoint,COUNTY,actor,prefix+"甲",LocalDate.of(2026,8,20),2);
            production(jdbc,countyPoint,COUNTY,actor,prefix+"甲",LocalDate.of(2026,7,20),1);
            production(jdbc,villagePoint,VILLAGE,actor,prefix+"乙",LocalDate.of(2026,8,20),1);
            UUID logistics=UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO logistics.route_event(event_id,product_code,collection_date,reported_at,
                      origin_region_code,destination_region_code,transport_mode_code,direction_code,
                      source_organization,reporter,status_code,version,created_by,last_modified_by,
                      business_region_code,sample_contact,sample_latitude,sample_longitude,survey_year,
                      survey_month,survey_period_precision,survey_period_governance_state,sample_point_id,created_at,updated_at)
                    VALUES(:id,'CORN',DATE '2026-08-20',TIMESTAMPTZ '2026-08-20 09:00:00+08',:region,:region,
                      'ROAD','INFLOW',:name,:actor,'APPROVED',0,:actor,:actor,:region,'13800999001',47.3,123.2,
                      2026,8,'YEAR_MONTH','CONFIRMED',:point,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """).param("id",logistics).param("region",COUNTY).param("name",prefix+"甲")
                    .param("actor",actor).param("point",countyPoint).update();
            for(UUID point:List.of(countyPoint,villagePoint)) {
                assertThat(jdbc.sql("""
                        SELECT registry.retire_formal_sample_point(sample_point_id,version,region_code,:actor,
                          '历史地图隔离验收',CURRENT_DATE) FROM registry.sample_point WHERE sample_point_id=:id
                        """).param("actor",actor).param("id",point).query(String.class).single()).isEqualTo("RETIRED");
            }
            int retirementYear=jdbc.sql("SELECT EXTRACT(YEAR FROM CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::integer").query(Integer.class).single();
            var ledger=new JdbcHistoricalFormalSampleRepository(jdbc);
            var map=new JdbcOverviewSamplePointRepository(jdbc,Clock.systemUTC());
            var page=ledger.findPage("PRODUCTION","CORN",retirementYear,TOWN,prefix,0,1,Set.of("*"));
            assertThat(page.totalElements()).as(jdbc.sql("SELECT canonical_name,region_code,ST_AsText(display_point) display,ST_AsText(governed_point) governed FROM registry.sample_point WHERE sample_point_id IN (:ids)").param("ids",List.of(countyPoint,villagePoint)).query().listOfRows().toString()).isEqualTo(2);assertThat(page.items()).hasSize(1);
            assertThat(ledger.findPage("PRODUCTION","CORN",retirementYear,TOWN,prefix,1,1,Set.of("*")).items()).hasSize(1);
            var all=ledger.findPage("PRODUCTION","CORN",null,VILLAGE,prefix,0,20,Set.of("*"));
            assertThat(all.items()).hasSize(2);
            assertThat(all.items().stream().filter(row->row.samplePointId().equals(countyPoint)).findFirst().orElseThrow().lastObservationId())
                    .isEqualTo(latest.toString());
            assertThat(all.items().stream().filter(row->row.samplePointId().equals(countyPoint)).findFirst().orElseThrow().regionCode()).isEqualTo(COUNTY);
            assertThat(ledger.findPage("PRODUCTION","CORN",retirementYear-1,TOWN,prefix,0,20,Set.of("*")).totalElements()).isZero();
            assertThat(ledger.findPage("MARKET","CORN",null,TOWN,prefix,0,20,Set.of("*")).totalElements()).isZero();
            var logisticsPage=ledger.findPage("LOGISTICS","CORN",null,TOWN,prefix,0,20,Set.of("*"));
            assertThat(logisticsPage.totalElements()).isEqualTo(1);
            assertThat(logisticsPage.items().getFirst().lastObservationId()).isEqualTo(logistics.toString());
            assertThat(logisticsPage.items().getFirst().objectTypeCode()).isEqualTo("ROAD_NODE");
            var countyAggregates=map.historicalAggregates(retirementYear,"CORN",COUNTY,null,null,prefix,Set.of("*"));
            assertThat(countyAggregates.stream().mapToLong(row->row.samplePointCount()).sum()).isEqualTo(2);
            var town=countyAggregates.stream().filter(row->row.regionCode().equals(TOWN)).findFirst().orElseThrow();
            assertThat(town.samplePointCount()).isEqualTo(2);assertThat(town.productionCount()).isEqualTo(2);assertThat(town.logisticsCount()).isEqualTo(1);
            assertThat(map.historicalAggregates(retirementYear,"CORN",TOWN,null,null,prefix,Set.of("*")).stream()
                    .mapToLong(row->row.samplePointCount()).sum()).isEqualTo(2);
            assertThat(map.historicalIcons(retirementYear,"CORN",VILLAGE,null,null,prefix,Set.of("*")))
                    .extracting(row->row.samplePointId()).containsExactlyInAnyOrder(countyPoint,villagePoint);
            assertThat(map.historicalDetail(retirementYear,"CORN",countyPoint,VILLAGE,null,null,Set.of("*"))).isPresent();
            return null;
        });
    }
    private static void boundary(JdbcClient jdbc,String region,double west,double east) {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(region_code,geometry,source_name,source_url,
                  source_revision,source_license,source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:r,ST_Multi(ST_MakeEnvelope(:west,47.0,:east,47.6,4326)),'history fixture',
                  'urn:test:history','history-v1','Test fixture',:r,DATE '2026-01-01',repeat('6',64))
                """).param("r",region).param("west",west).param("east",east).update();
        GovernedMasterDataFixtures.publishBoundary(jdbc,region);
    }
    private static UUID sample(JdbcClient jdbc,String region,double longitude,String actor,String name) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,region_code,
                  approval_state,location_state,governed_point,effective_from,maintainer_subject_id,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE',:name,:r,'APPROVED','VALID',ST_SetSRID(ST_MakePoint(:longitude,47.3),4326),
                  DATE '2026-01-01',:actor,:actor,:actor)
                """).param("id",id).param("name",name).param("r",region).param("longitude",longitude).param("actor",actor).update();
        jdbc.sql("INSERT INTO registry.formal_sample_point_profile(sample_point_id,object_type_code,address,created_by,updated_by) VALUES(:id,'FARMER',:address,:actor,:actor)")
                .param("id",id).param("address","历史层级隔离县历史层级隔离乡甲历史层级隔离村"+name).param("actor",actor).update();
        assertThat(jdbc.sql("SELECT ST_Covers(boundary.geometry,point.display_point) FROM registry.sample_point point JOIN overview.administrative_boundary_render boundary ON boundary.region_code=:village WHERE point.sample_point_id=:id")
                .param("village",VILLAGE).param("id",id).query(Boolean.class).single()).isTrue();
        return id;
    }
    private static UUID production(JdbcClient jdbc,UUID point,String region,String actor,String name,LocalDate date,int version) {
        UUID record=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id,version,updated_at)
                VALUES(:record,'CORN','FARMER',:region,:date,:reported,100,500,'APPROVED',:actor,
                  2026,'YEAR','CONFIRMED',:point,:version,:reported)
                """).param("record",record).param("region",region).param("date",date)
                .param("reported",date.atStartOfDay().atOffset(ZoneOffset.ofHours(8)))
                .param("actor",actor).param("point",point).param("version",version).update();
        jdbc.sql("INSERT INTO production.production_record_submission_metadata(record_id,field_code,value) VALUES(:id,'PROD_SAMPLE_NAME',:name)")
                .param("id",record).param("name",name).update();
        return record;
    }
}

package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointService;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class OverviewDesignMapCatalogIntegrationTest {
    private static final String REGION = "990209917";
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    @Test
    void showsActiveGeneralAndSelectedProductDesignsWithoutExpandingRegionAccess() {
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('DESIGN_MAP_TEST','地图测试单位',99317) ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES('design-map-test','地图测试员','DESIGN_MAP_TEST') ON CONFLICT DO NOTHING").update();
        GovernedMasterDataFixtures.insertRegion(jdbc,REGION,"设计地图测试乡","230202","TOWNSHIP",99317);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(region_code,geometry,source_name,source_url,
                  source_revision,source_license,source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:region,ST_Multi(ST_MakeEnvelope(123,47,124,48,4326)),
                  '测试边界','urn:test:design-map','test-v1','Test fixture',:region,DATE '2026-09-17',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123,47,124,48,4326)))),'hex'))
                """).param("region",REGION).update();
        GovernedMasterDataFixtures.publishBoundary(jdbc,REGION);
        insert("通用有效","GENERAL","ACTIVE");
        insert("通用过期","GENERAL","EXPIRED");
        insert("玉米有效","CORN","ACTIVE");
        insert("玉米过期","CORN","EXPIRED");
        insert("大豆有效","SOYBEAN","ACTIVE");

        var access=mock(AccessControl.class);
        var controller=new OverviewMapCatalogController(dataSource,access,mock(OverviewSamplePointService.class));
        when(access.requireOverviewReadScope()).thenReturn(AuthorizedReadScope.unrestricted());
        assertThat(controller.design("CORN",REGION).data()).extracting(row->row.get("name"))
                .containsExactlyInAnyOrder("通用有效","玉米有效");
        assertThat(controller.design("SOYBEAN",REGION).data()).extracting(row->row.get("name"))
                .containsExactlyInAnyOrder("通用有效","大豆有效");
        when(access.requireOverviewReadScope()).thenReturn(new AuthorizedReadScope("design-map-test",Set.of("230203")));
        assertThat(controller.design("CORN",REGION).data()).isEmpty();
    }

    private void insert(String name,String product,String status) {
        boolean general=product.equals("GENERAL");
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by,lifecycle_status,expired_at)
                VALUES(:id,:contract,:domain,:product,:type,'{}',:name,:region,
                  ST_SetSRID(ST_MakePoint(123.5,47.5),4326),:name,repeat('a',64),
                  'design-map-test','design-map-test',:status,CASE WHEN :status='EXPIRED' THEN now() ELSE NULL END)
                """).param("id",UUID.randomUUID()).param("contract",general?"design-sample-fields-v3":"design-sample-fields-v1")
                .param("domain",general?"REFERENCE":"PRODUCTION").param("product",product)
                .param("type",general?"REFERENCE_POINT":"FARMER").param("name",name)
                .param("region",REGION).param("status",status).update();
    }
}

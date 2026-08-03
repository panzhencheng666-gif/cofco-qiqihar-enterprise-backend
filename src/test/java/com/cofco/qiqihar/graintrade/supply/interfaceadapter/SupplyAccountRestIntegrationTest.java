package com.cofco.qiqihar.graintrade.supply.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes=GrainTradeApplication.class) @AutoConfigureMockMvc @UsesProtectedTestDatabase
class SupplyAccountRestIntegrationTest{
 @Autowired MockMvc mvc;@Autowired DataSource dataSource;JdbcClient jdbc;
 @BeforeEach void clean(){jdbc=JdbcClient.create(dataSource);jdbc.sql("DELETE FROM supply.result_version").update();jdbc.sql("DELETE FROM supply.calculation_run").update();jdbc.sql("DELETE FROM supply.approved_adjustment").update();jdbc.sql("DELETE FROM supply.adoption_decision").update();jdbc.sql("DELETE FROM supply.source_release_value").update();jdbc.sql("DELETE FROM supply.source_release").update();}
 @Test void calculatesAllProductsFromApprovedSourcesWithFormulaProvenanceReasonsSignAndCas()throws Exception{
  mvc.perform(post("/api/v1/supply-accounts/runs").contentType(MediaType.APPLICATION_JSON).content("{"))
    .andExpect(status().isUnauthorized());
  for(String product:List.of("CORN","SOYBEAN","RICE")){sources(product);mvc.perform(post("/api/v1/supply-accounts/runs").principal(()->"supply-reviewer").contentType(MediaType.APPLICATION_JSON).content(runBody(product,0)))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value(product))
    .andExpect(jsonPath("$.data.resultState").value("FORMAL"))
    .andExpect(jsonPath("$.data.inventoryReconciliationDifference").value("-0.250"))
    .andExpect(jsonPath("$.data.formula.differenceCode").value("INVENTORY_RECONCILIATION_DIFFERENCE"))
    .andExpect(jsonPath("$.data.formula.differenceExpression").value("SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY"))
    .andExpect(jsonPath("$.data.sources.length()").value(14))
    .andExpect(jsonPath("$.data.sources[?(@.roleCode == 'EXTERNAL_INFLOW')].drillDownRoute").exists())
    .andExpect(jsonPath("$.data.sources[0].reason").value("采用本期核定来源"));}
  mvc.perform(post("/api/v1/supply-accounts/runs").principal(()->"supply-reviewer").contentType(MediaType.APPLICATION_JSON).content(runBody("CORN",99)))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SUPPLY_DECISION_VERSION_CONFLICT"));
  assertThat(jdbc.sql("SELECT count(*) FROM supply.calculation_run WHERE product_code='CORN'").query(Long.class).single()).isOne();
  mvc.perform(post("/api/v1/supply-accounts/runs").principal(()->"supply-reviewer").contentType(MediaType.APPLICATION_JSON).content(runBody("CORN",0).replace("库存覆盖差异经复核", " ")))
    .andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/supply-accounts").queryParam("productCode","RICE").queryParam("regionCode","230200").queryParam("marketingYear","2026/27"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].formula.expressions.length()").value(5))
    .andExpect(jsonPath("$.data[0].sources[?(@.approvalState != 'APPROVED')]").doesNotExist());
 }
 private void sources(String product){for(String role:List.of("OPENING_INVENTORY","LOCAL_PRODUCTION","EXTERNAL_INFLOW","IMPORTS","OTHER_SUPPLY","FOOD_USE","FEED_USE","SEED_USE","PROCESSING_USE","LOSS","EXTERNAL_OUTFLOW","EXPORTS","OTHER_USE","SURVEYED_ENDING_INVENTORY")){String id=UUID.randomUUID().toString();String domain=role.equals("EXTERNAL_INFLOW")||role.equals("EXTERNAL_OUTFLOW")?"LOGISTICS":role.equals("LOCAL_PRODUCTION")?"PRODUCTION":"MARKET";String value=role.equals("SURVEYED_ENDING_INVENTORY")?"7.750":role.endsWith("SUPPLY")||role.equals("OPENING_INVENTORY")||role.equals("LOCAL_PRODUCTION")||role.equals("EXTERNAL_INFLOW")||role.equals("IMPORTS")?"3.000":"1.000";jdbc.sql("INSERT INTO supply.source_release(source_release_id,source_domain,source_record_id,source_version,approval_state,approved_at,quality_state,product_code,region_code,marketing_year,immutable_digest) VALUES(CAST(:id AS uuid),:domain,:record,1,'APPROVED',now(),'PASSED',:product,'230200','2026/27',:digest)").param("id",id).param("domain",domain).param("record",UUID.randomUUID().toString()).param("product",product).param("digest",UUID.randomUUID().toString()).update();jdbc.sql("INSERT INTO supply.source_release_value(source_release_id,role_code,value,unit_code) VALUES(CAST(:id AS uuid),:role,:value,'万吨')").param("id",id).param("role",role).param("value",new java.math.BigDecimal(value)).update();}}
 private static String runBody(String product,long expected){return """
  {"productCode":"%s","regionCode":"230200","marketingYear":"2026/27","approvedAdjustment":"1.000",
   "adoptionReason":"采用本期核定来源","adjustmentReason":"库存覆盖差异经复核","expectedDecisionVersion":%d,"publish":true}
  """.formatted(product,expected);}
}

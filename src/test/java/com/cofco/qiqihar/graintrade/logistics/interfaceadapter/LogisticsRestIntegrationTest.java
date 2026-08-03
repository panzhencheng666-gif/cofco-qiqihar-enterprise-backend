package com.cofco.qiqihar.graintrade.logistics.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
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
class LogisticsRestIntegrationTest{
 @Autowired MockMvc mvc;@Autowired DataSource dataSource;long rail;long road;
 @BeforeEach void fixture(){JdbcClient j=JdbcClient.create(dataSource);j.sql("DELETE FROM logistics.route_event").update();j.sql("DELETE FROM logistics.logistics_node").update();j.sql("DELETE FROM platform.business_period WHERE code='LOG-2026-08'").update();j.sql("INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order) VALUES('LOG-2026-08','2026年8月物流监测期','2026-08-01','2026-08-31',900)").update();rail=node(j,"TEST_RAIL","测试铁路站","RAIL_NODE");road=node(j,"TEST_ROAD","测试公路节点","ROAD_NODE");}
 @Test void coversThreeProductsBothModesWorkflowAuthStrictQueryAndCas()throws Exception{
  mvc.perform(post("/api/v1/logistics-records/not-disclosed/submit").contentType(MediaType.APPLICATION_JSON).content("{"))
    .andExpect(status().isUnauthorized());
  String corn=create("CORN","RAIL",rail,road);create("SOYBEAN","ROAD",road,rail);create("RICE","RAIL",rail,road);
  mvc.perform(get("/api/v1/logistics-records").queryParam("productCode","SOYBEAN").queryParam("pageNumber","0").queryParam("pageSize","20").queryParam("filter.transportModeCode","ROAD"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.items[0].productCode").value("SOYBEAN"));
  mvc.perform(get("/api/v1/logistics-records").queryParam("productCode","CORN").queryParam("pageNumber","0").queryParam("pageSize","20").queryParam("unknown","x"))
    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
  transition(corn,"submit",0,null).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
  transition(corn,"return",1,"补充运单编号").andExpect(jsonPath("$.data.status").value("RETURNED"));
  mvc.perform(put("/api/v1/logistics-records/{id}",corn).principal(()->"logistics-tester").contentType(MediaType.APPLICATION_JSON).content(body("CORN","RAIL",rail,road,"13.500",2)))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT")).andExpect(jsonPath("$.data.version").value(3));
  transition(corn,"submit",3,null);transition(corn,"approve",4,null).andExpect(jsonPath("$.data.status").value("APPROVED"));
  mvc.perform(put("/api/v1/logistics-records/{id}",corn).principal(()->"logistics-tester").contentType(MediaType.APPLICATION_JSON).content(body("CORN","RAIL",rail,road,"99",3)))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOGISTICS_RECORD_VERSION_CONFLICT"));
  mvc.perform(get("/api/v1/logistics-records/{id}",corn)).andExpect(jsonPath("$.data.values.LOG_ROUTE_VOLUME").value("13.5000 吨"));
 }
 private String create(String product,String mode,long origin,long destination)throws Exception{return mvc.perform(post("/api/v1/logistics-records").principal(()->"logistics-tester").contentType(MediaType.APPLICATION_JSON).content(body(product,mode,origin,destination,"12.500",null))).andExpect(status().isCreated()).andExpect(jsonPath("$.data.values.LOG_TRANSPORT_MODE").exists()).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*","$1");}
 private org.springframework.test.web.servlet.ResultActions transition(String id,String action,long version,String reason)throws Exception{String body=reason==null?"{\"version\":"+version+"}":"{\"version\":"+version+",\"reason\":\""+reason+"\"}";return mvc.perform(post("/api/v1/logistics-records/{id}/"+action,id).principal(()->"logistics-tester").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());}
 private static long node(JdbcClient j,String code,String name,String type){return j.sql("INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code) VALUES(:code,:name,:type,'230200') RETURNING node_id").param("code",code).param("name",name).param("type",type).query(Long.class).single();}
 private static String body(String product,String mode,long origin,long destination,String volume,Integer version){return """
  {"productCode":"%s","monitoringPeriodCode":"LOG-2026-08","collectionDate":"2026-08-01",
   "originNodeId":%d,"destinationNodeId":%d,"transportModeCode":"%s","direction":"INFLOW",
   "routeVolume":"%s","volumeUnit":"吨","freightRate":"80.25","freightUnit":"元/吨",
   "transitTime":"2.50","transitUnit":"小时","sourceOrganization":"测试来源单位","reporter":"测试填报人"%s}
  """.formatted(product,origin,destination,mode,volume,version==null?"":",\"version\":"+version);}
}

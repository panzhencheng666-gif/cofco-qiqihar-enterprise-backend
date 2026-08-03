package com.cofco.qiqihar.graintrade.logistics.infrastructure;

import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRecordView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRepository;
import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLogisticsRepository implements LogisticsRepository {
    private final JdbcClient jdbc;
    public JdbcLogisticsRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public boolean validContext(LogisticsDraft d){
        return Boolean.TRUE.equals(jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM platform.product p, platform.business_period bp,
              logistics.logistics_node o, logistics.logistics_node n, platform.transport_mode tm
              WHERE p.code=:p AND bp.code=:period AND o.node_id=:o AND n.node_id=:n AND tm.code=:mode)
            """).param("p",d.productCode()).param("period",d.monitoringPeriodCode()).param("o",d.originNodeId())
                .param("n",d.destinationNodeId()).param("mode",d.transportModeCode()).query(Boolean.class).single());
    }
    @Override public PagedResult<LogisticsRecordView> findPage(String product,int page,int size,Map<String,String> filters){
        SqlFilter filter=filter(product,filters); long total=jdbc.sql("SELECT count(*) FROM logistics.route_event e "+filter.sql).params(filter.params).query(Long.class).single();
        List<String> ids=jdbc.sql("SELECT e.event_id::text FROM logistics.route_event e "+filter.sql+" ORDER BY e.collection_date DESC,e.event_id LIMIT :limit OFFSET :offset")
                .params(filter.params).param("limit",size).param("offset",Math.multiplyExact((long)page,size)).query(String.class).list();
        List<LogisticsRecordView> items=findAll(ids); return new PagedResult<>(items,page,size,total);
    }
    @Override public LogisticsRecordView find(String id){
        List<LogisticsRecordView> records=findAll(List.of(id));return records.isEmpty()?null:records.getFirst();
    }
    private List<LogisticsRecordView> findAll(List<String> ids){
        if(ids.isEmpty())return List.of();
        List<Header> rows=jdbc.sql("""
          SELECT e.event_id::text id,e.product_code,e.monitoring_period_code,e.collection_date,e.reported_at,
            e.origin_node_id,o.node_name origin,e.destination_node_id,n.node_name destination,e.transport_mode_code,tm.name mode,
            e.direction_code,e.source_organization,e.reporter,e.status_code,e.return_reason,e.version
          FROM logistics.route_event e JOIN logistics.logistics_node o ON o.node_id=e.origin_node_id
          JOIN logistics.logistics_node n ON n.node_id=e.destination_node_id
          JOIN platform.transport_mode tm ON tm.code=e.transport_mode_code WHERE e.event_id::text IN (:ids)
          """).param("ids",ids).query((r,i)->new Header(r.getString("id"),r.getString("product_code"),r.getString("monitoring_period_code"),
                  r.getDate("collection_date").toLocalDate().toString(),r.getObject("reported_at",OffsetDateTime.class).toString(),r.getString("origin"),
                  r.getLong("origin_node_id"),r.getString("destination"),r.getLong("destination_node_id"),r.getString("mode"),r.getString("transport_mode_code"),
                  r.getString("direction_code"),r.getString("source_organization"),r.getString("reporter"),LogisticsStatus.valueOf(r.getString("status_code")),r.getString("return_reason"),r.getLong("version"))).list();
        Map<String,List<Fact>> facts=new LinkedHashMap<>();jdbc.sql("SELECT event_id::text,fact_code,value,unit_code FROM logistics.route_fact WHERE event_id::text IN (:ids) ORDER BY fact_code")
          .param("ids",ids).query((r,i)->new Fact(r.getString("event_id"),r.getString("fact_code"),r.getBigDecimal("value"),r.getString("unit_code"))).list()
          .forEach(f->facts.computeIfAbsent(f.id,k->new java.util.ArrayList<>()).add(f));
        Map<String,Header> byId=new LinkedHashMap<>();rows.forEach(h->byId.put(h.id,h));return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).map(h->{Map<String,String> values=new LinkedHashMap<>();
          values.put("LOG_COLLECTION_DATE",h.date);values.put("LOG_REPORTED_AT",h.reported);values.put("LOG_PERIOD",h.period);
          values.put("LOG_ORIGIN",h.origin);values.put("LOG_DESTINATION",h.destination);values.put("LOG_TRANSPORT_MODE",h.mode);values.put("LOG_DIRECTION",h.direction);
          values.put("LOG_SOURCE_ORGANIZATION",h.organization);values.put("LOG_REPORTER",h.reporter);
          values.put("__monitoringPeriodCode",h.period);values.put("__originNodeId",Long.toString(h.originNode));values.put("__destinationNodeId",Long.toString(h.destinationNode));
          values.put("__transportModeCode",h.modeCode);values.put("__directionCode",h.direction);values.put("__sourceOrganization",h.organization);values.put("__reporter",h.reporter);
          facts.getOrDefault(h.id,List.of()).forEach(f->{String code=switch(f.code){case "ROUTE_VOLUME"->"LOG_ROUTE_VOLUME";case "FREIGHT_RATE"->"LOG_FREIGHT_RATE";default->"LOG_TRANSIT_TIME";};
            String raw=switch(f.code){case "ROUTE_VOLUME"->"__routeVolume";case "FREIGHT_RATE"->"__freightRate";default->"__transitTime";};
            String unit=switch(f.code){case "ROUTE_VOLUME"->"__volumeUnit";case "FREIGHT_RATE"->"__freightUnit";default->"__transitUnit";};
            values.put(code,f.value.toPlainString()+" "+f.unit);values.put(raw,f.value.toPlainString());values.put(unit,f.unit);});
          values.put("LOG_STATUS",label(h.status));return new LogisticsRecordView(h.id,h.product,Map.copyOf(values),h.status,h.reason,actions(h.status),h.version);}).toList();
    }
    @Override public LogisticsRecordView insert(String id,LogisticsDraft d,String actor,Instant now){
        jdbc.sql("""
          INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,reported_at,
          origin_region_code,origin_node_id,destination_region_code,destination_node_id,transport_mode_code,direction_code,
          source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at)
          SELECT CAST(:id AS uuid),:p,:period,:date,:reported,o.region_code,o.node_id,n.region_code,n.node_id,:mode,:direction,
          :organization,:reporter,'DRAFT',:actor,:actor,:now,:now FROM logistics.logistics_node o,logistics.logistics_node n WHERE o.node_id=:o AND n.node_id=:n
          """)
          .param("id",id).param("p",d.productCode()).param("period",d.monitoringPeriodCode()).param("date",d.collectionDate())
          .param("reported",OffsetDateTime.ofInstant(now,ZoneOffset.UTC)).param("mode",d.transportModeCode()).param("direction",d.directionCode())
          .param("organization",d.sourceOrganization()).param("reporter",d.reporter()).param("actor",actor).param("now",OffsetDateTime.ofInstant(now,ZoneOffset.UTC))
          .param("o",d.originNodeId()).param("n",d.destinationNodeId()).update(); facts(id,d);return find(id);
    }
    @Override public LogisticsRecordView update(String id,long version,LogisticsDraft d,String actor,Instant now){
        int count=jdbc.sql("""
          UPDATE logistics.route_event e SET monitoring_period_code=:period,collection_date=:date,reported_at=:reported,
          origin_region_code=o.region_code,origin_node_id=o.node_id,destination_region_code=n.region_code,destination_node_id=n.node_id,
          transport_mode_code=:mode,direction_code=:direction,source_organization=:organization,reporter=:reporter,status_code='DRAFT',return_reason=NULL,
          last_modified_by=:actor,updated_at=:now,version=version+1 FROM logistics.logistics_node o,logistics.logistics_node n
          WHERE e.event_id::text=:id AND e.version=:version AND o.node_id=:o AND n.node_id=:n
          """).param("period",d.monitoringPeriodCode()).param("date",d.collectionDate())
          .param("reported",OffsetDateTime.ofInstant(now,ZoneOffset.UTC)).param("mode",d.transportModeCode()).param("direction",d.directionCode())
          .param("organization",d.sourceOrganization()).param("reporter",d.reporter()).param("actor",actor).param("now",OffsetDateTime.ofInstant(now,ZoneOffset.UTC))
          .param("id",id).param("version",version).param("o",d.originNodeId()).param("n",d.destinationNodeId()).update(); require(count);facts(id,d);return find(id);
    }
    @Override public LogisticsRecordView transition(String id,long version,LogisticsStatus status,String reason,String actor,Instant now){
        int count=jdbc.sql("UPDATE logistics.route_event SET status_code=:status,return_reason=:reason,last_modified_by=:actor,updated_at=:now,version=version+1 WHERE event_id::text=:id AND version=:version")
          .param("status",status.name()).param("reason",reason).param("actor",actor).param("now",OffsetDateTime.ofInstant(now,ZoneOffset.UTC)).param("id",id).param("version",version).update();require(count);return find(id);
    }
    private void facts(String id,LogisticsDraft d){jdbc.sql("DELETE FROM logistics.route_fact WHERE event_id::text=:id").param("id",id).update();fact(id,"ROUTE_VOLUME",d.routeVolume(),d.volumeUnit());fact(id,"FREIGHT_RATE",d.freightRate(),d.freightUnit());fact(id,"TRANSIT_TIME",d.transitTime(),d.transitUnit());}
    private void fact(String id,String code,BigDecimal value,String unit){jdbc.sql("INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code) VALUES(CAST(:id AS uuid),:code,:value,:unit)").param("id",id).param("code",code).param("value",value).param("unit",unit).update();}
    private static void require(int count){if(count==0)throw new ConflictException("LOGISTICS_RECORD_VERSION_CONFLICT","Logistics record has changed");}
    private static List<String> actions(LogisticsStatus s){return switch(s){case DRAFT->List.of("VIEW","SUBMIT");case PENDING_REVIEW->List.of("VIEW","APPROVE","RETURN");case RETURNED->List.of("VIEW");case APPROVED->List.of("VIEW");};}
    private static String label(LogisticsStatus s){return switch(s){case DRAFT->"草稿";case PENDING_REVIEW->"待审核";case APPROVED->"已审核";case RETURNED->"退回补充";};}
    private static SqlFilter filter(String product,Map<String,String> f){StringBuilder sql=new StringBuilder("WHERE e.product_code=:product");Map<String,Object> p=new LinkedHashMap<>();p.put("product",product);f.forEach((k,v)->{switch(k){case"status"->sql.append(" AND e.status_code=:status");case"regionCode"->sql.append(" AND (e.origin_region_code=:regionCode OR e.destination_region_code=:regionCode)");case"periodCode"->sql.append(" AND e.monitoring_period_code=:periodCode");case"transportModeCode"->sql.append(" AND e.transport_mode_code=:transportModeCode");case"nodeTypeCode"->sql.append(" AND EXISTS(SELECT 1 FROM logistics.logistics_node n WHERE n.node_id IN(e.origin_node_id,e.destination_node_id) AND n.node_type_code=:nodeTypeCode)");default->throw new IllegalArgumentException();}p.put(k,v);});return new SqlFilter(sql.toString(),p);}
    private record SqlFilter(String sql,Map<String,Object> params){}
    private record Header(String id,String product,String period,String date,String reported,String origin,long originNode,String destination,long destinationNode,String mode,String modeCode,String direction,String organization,String reporter,LogisticsStatus status,String reason,long version){}
    private record Fact(String id,String code,BigDecimal value,String unit){}
}

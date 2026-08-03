package com.cofco.qiqihar.graintrade.supply.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.supply.application.*;
import com.cofco.qiqihar.graintrade.supply.domain.*;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplyAccountRepository implements SupplyAccountRepository{
 private final JdbcClient jdbc;public JdbcSupplyAccountRepository(JdbcClient jdbc){this.jdbc=jdbc;}
 @Override public List<SupplyAccountView> find(String product,String region,String year,String state,Integer version){
  StringBuilder sql=new StringBuilder("""
    SELECT r.calculation_run_id::text id,r.product_code,r.region_code,r.marketing_year,r.result_state,
      r.validation_codes,round(r.total_supply,f.scale_value) total_supply,round(r.total_use,f.scale_value) total_use,
      round(r.calculated_ending_inventory,f.scale_value) calculated_ending_inventory,
      round(r.approved_adjustment,f.scale_value) approved_adjustment,
      round(r.adopted_ending_inventory,f.scale_value) adopted_ending_inventory,
      round(r.surveyed_ending_inventory,f.scale_value) surveyed_ending_inventory,
      round(r.inventory_reconciliation_difference,f.scale_value) inventory_reconciliation_difference,r.balanced,
      rv.version_no,r.formula_version_id
    FROM supply.calculation_run r JOIN supply.result_version rv ON rv.calculation_run_id=r.calculation_run_id
    JOIN supply.formula_version f ON f.formula_version_id=r.formula_version_id
    WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
    """);Map<String,Object> p=new LinkedHashMap<>();p.put("product",product);p.put("region",region);p.put("year",year);
  if(state!=null){sql.append(" AND r.result_state=:state");p.put("state",state);}if(version!=null){sql.append(" AND rv.version_no=:version");p.put("version",version);}
  sql.append(" ORDER BY rv.version_no DESC");List<Header> headers=jdbc.sql(sql.toString()).params(p).query((r,i)->new Header(r.getString("id"),r.getString("product_code"),r.getString("region_code"),r.getString("marketing_year"),r.getInt("version_no"),r.getString("result_state"),strings(r.getArray("validation_codes")),plain(r.getBigDecimal("total_supply")),plain(r.getBigDecimal("total_use")),plain(r.getBigDecimal("calculated_ending_inventory")),plain(r.getBigDecimal("approved_adjustment")),plain(r.getBigDecimal("adopted_ending_inventory")),plain(r.getBigDecimal("surveyed_ending_inventory")),plain(r.getBigDecimal("inventory_reconciliation_difference")),Boolean.TRUE.equals(r.getObject("balanced",Boolean.class)),r.getLong("formula_version_id"))).list();
  return assemble(headers);
 }
 @Override public SupplyAccountView run(SupplyRunCommand c,String actor,Instant now){
  if(!exists("SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:value)",c.productCode())||!exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:value)",c.regionCode()))throw invalid();
  FormulaRow f=jdbc.sql("SELECT formula_version_id,code,version_no,name,precision_value,scale_value,tolerance,difference_code,difference_label,difference_expression FROM supply.formula_version WHERE active ORDER BY version_no DESC LIMIT 1")
    .query((r,i)->new FormulaRow(r.getLong("formula_version_id"),r.getString("code"),r.getInt("version_no"),r.getString("name"),r.getInt("precision_value"),r.getInt("scale_value"),r.getBigDecimal("tolerance"),r.getString("difference_code"),r.getString("difference_label"),r.getString("difference_expression"))).single();
  List<SourceRow> rows=sourceCandidates(c.productCode(),c.regionCode(),c.marketingYear());List<SupplySource> sources=rows.stream().map(r->new SupplySource(r.role,r.domain,r.record,r.sourceVersion,ApprovalState.APPROVED,QualityState.valueOf(r.quality),r.value,c.adoptionReason(),route(r.domain,r.record))).toList();
  List<String> errors=SupplyAccountCalculator.validate(sources);String runId=UUID.randomUUID().toString();OffsetDateTime timestamp=OffsetDateTime.ofInstant(now,ZoneOffset.UTC);
  SupplyAccountCalculation calculation=null;String state="TRIAL";
  if(errors.isEmpty()){
   calculation=SupplyAccountCalculator.calculate(new SupplyFormula(f.code+"_V"+f.version,f.precision,f.scale,f.tolerance),sources,c.approvedAdjustment());
   state=c.publish()?"FORMAL":"FORMAL_CANDIDATE";for(SourceRow row:rows)upsertDecision(c,row,actor,timestamp);upsertAdjustment(c,actor,timestamp);
  }
  insertRun(runId,c,f.id,state,errors,calculation,actor,timestamp);int resultVersion=nextVersion(c);jdbc.sql("""
    INSERT INTO supply.result_version(result_version_id,calculation_run_id,version_no,published_by,published_at)
    VALUES(CAST(:result AS uuid),CAST(:run AS uuid),:version,:publisher,:publishedAt)
    """).param("result",UUID.randomUUID().toString()).param("run",runId).param("version",resultVersion)
      .param("publisher",c.publish()&&errors.isEmpty()?actor:null).param("publishedAt",c.publish()&&errors.isEmpty()?timestamp:null).update();
  if(errors.isEmpty())for(SourceRow row:rows)jdbc.sql("""
    INSERT INTO supply.calculation_source_reference(calculation_run_id,role_code,source_release_id,source_record_id,source_version,adopted_value,reason,drill_down_route)
    VALUES(CAST(:run AS uuid),:role,CAST(:release AS uuid),:record,:version,:value,:reason,:route)
    """).param("run",runId).param("role",row.role).param("release",row.releaseId).param("record",row.record).param("version",row.sourceVersion)
      .param("value",row.value).param("reason",c.adoptionReason()).param("route",route(row.domain,row.record)).update();
  return find(c.productCode(),c.regionCode(),c.marketingYear(),null,resultVersion).getFirst();
 }
 private List<SourceRow> sourceCandidates(String product,String region,String year){return jdbc.sql("""
   SELECT release.source_release_id::text,release.source_domain,release.source_record_id,release.source_version,
     release.approved_at,release.quality_state,value.role_code,role.label,role.group_code,value.value
   FROM supply.source_release release JOIN supply.source_release_value value ON value.source_release_id=release.source_release_id
   JOIN supply.account_input_role role ON role.role_code=value.role_code
   WHERE release.product_code=:product AND release.region_code=:region AND release.marketing_year=:year
     AND release.approval_state='APPROVED' ORDER BY role.sort_order,release.source_release_id
   """).param("product",product).param("region",region).param("year",year).query((r,i)->new SourceRow(r.getString("source_release_id"),r.getString("source_domain"),r.getString("source_record_id"),r.getLong("source_version"),r.getObject("approved_at",OffsetDateTime.class).toString(),r.getString("quality_state"),r.getString("role_code"),r.getString("label"),r.getString("group_code"),r.getBigDecimal("value"))).list();}
 private void upsertDecision(SupplyRunCommand c,SourceRow r,String actor,OffsetDateTime now){int n=jdbc.sql("""
   INSERT INTO supply.adoption_decision(adoption_decision_id,product_code,region_code,marketing_year,role_code,source_release_id,adopted_value,reason,decided_by,decided_at,version)
   VALUES(CAST(:id AS uuid),:product,:region,:year,:role,CAST(:release AS uuid),:value,:reason,:actor,:now,0)
   ON CONFLICT(product_code,region_code,marketing_year,role_code) DO UPDATE SET source_release_id=excluded.source_release_id,
     adopted_value=excluded.adopted_value,reason=excluded.reason,decided_by=excluded.decided_by,decided_at=excluded.decided_at,version=supply.adoption_decision.version+1
   WHERE supply.adoption_decision.version=:expected
   """).param("id",UUID.randomUUID().toString()).param("product",c.productCode()).param("region",c.regionCode()).param("year",c.marketingYear()).param("role",r.role).param("release",r.releaseId).param("value",r.value).param("reason",c.adoptionReason()).param("actor",actor).param("now",now).param("expected",c.expectedDecisionVersion()).update();require(n);}
 private void upsertAdjustment(SupplyRunCommand c,String actor,OffsetDateTime now){int n=jdbc.sql("""
   INSERT INTO supply.approved_adjustment(adjustment_id,product_code,region_code,marketing_year,value,reason,decided_by,decided_at,version)
   VALUES(CAST(:id AS uuid),:product,:region,:year,:value,:reason,:actor,:now,0)
   ON CONFLICT(product_code,region_code,marketing_year) DO UPDATE SET value=excluded.value,reason=excluded.reason,
     decided_by=excluded.decided_by,decided_at=excluded.decided_at,version=supply.approved_adjustment.version+1
   WHERE supply.approved_adjustment.version=:expected
   """).param("id",UUID.randomUUID().toString()).param("product",c.productCode()).param("region",c.regionCode()).param("year",c.marketingYear()).param("value",c.approvedAdjustment()).param("reason",c.adjustmentReason()).param("actor",actor).param("now",now).param("expected",c.expectedDecisionVersion()).update();require(n);}
 private void insertRun(String id,SupplyRunCommand c,long formula,String state,List<String> errors,SupplyAccountCalculation a,String actor,OffsetDateTime now){jdbc.sql("""
   INSERT INTO supply.calculation_run(calculation_run_id,product_code,region_code,marketing_year,formula_version_id,result_state,validation_codes,
    total_supply,total_use,calculated_ending_inventory,approved_adjustment,adopted_ending_inventory,surveyed_ending_inventory,
    inventory_reconciliation_difference,balanced,created_by,created_at)
   VALUES(CAST(:id AS uuid),:product,:region,:year,:formula,:state,CAST(:errors AS text[]),:supply,:use,:calculated,:adjustment,:adopted,:surveyed,:difference,:balanced,:actor,:now)
   """).param("id",id).param("product",c.productCode()).param("region",c.regionCode()).param("year",c.marketingYear()).param("formula",formula).param("state",state)
   .param("errors",errors.toArray(String[]::new)).param("supply",a==null?null:a.totalSupply()).param("use",a==null?null:a.totalUse()).param("calculated",a==null?null:a.calculatedEndingInventory()).param("adjustment",a==null?null:a.approvedAdjustment()).param("adopted",a==null?null:a.adoptedEndingInventory()).param("surveyed",a==null?null:a.surveyedEndingInventory()).param("difference",a==null?null:a.inventoryReconciliationDifference()).param("balanced",a==null?null:a.balanced()).param("actor",actor).param("now",now).update();}
 private int nextVersion(SupplyRunCommand c){return jdbc.sql("""
   SELECT COALESCE(max(rv.version_no),0)+1 FROM supply.result_version rv
   JOIN supply.calculation_run r ON r.calculation_run_id=rv.calculation_run_id
   WHERE r.product_code=:product AND r.region_code=:region AND r.marketing_year=:year
   """).param("product",c.productCode()).param("region",c.regionCode()).param("year",c.marketingYear()).query(Integer.class).single();}
 private List<SupplyAccountView> assemble(List<Header> headers){if(headers.isEmpty())return List.of();List<String> ids=headers.stream().map(h->h.id).toList();Map<String,List<SupplySourceView>> sources=new LinkedHashMap<>();jdbc.sql("""
   SELECT ref.calculation_run_id::text run_id,ref.role_code,role.label,role.group_code,release.source_domain,
    ref.source_record_id,ref.source_version,release.approval_state,release.approved_at,release.quality_state,
    value.value source_value,ref.adopted_value,ref.reason,ref.drill_down_route
   FROM supply.calculation_source_reference ref JOIN supply.source_release release ON release.source_release_id=ref.source_release_id
   JOIN supply.source_release_value value ON value.source_release_id=release.source_release_id AND value.role_code=ref.role_code
   JOIN supply.account_input_role role ON role.role_code=ref.role_code WHERE ref.calculation_run_id::text IN (:ids) ORDER BY role.sort_order
   """).param("ids",ids).query((r,i)->new AbstractMap.SimpleImmutableEntry<>(r.getString("run_id"),new SupplySourceView(r.getString("role_code"),r.getString("label"),r.getString("group_code"),r.getString("source_domain"),r.getString("source_record_id"),r.getLong("source_version"),r.getString("approval_state"),r.getObject("approved_at",OffsetDateTime.class).toString(),r.getString("quality_state"),plain(r.getBigDecimal("source_value")),plain(r.getBigDecimal("adopted_value")),r.getString("reason"),r.getString("drill_down_route")))).list().forEach(e->sources.computeIfAbsent(e.getKey(),k->new ArrayList<>()).add(e.getValue()));
  Map<Long,SupplyFormulaView> formulas=new LinkedHashMap<>();for(Long formulaId:headers.stream().map(h->h.formula).distinct().toList())formulas.put(formulaId,formula(formulaId));
  return headers.stream().map(h->new SupplyAccountView(h.id,h.product,h.region,h.year,h.version,h.state,h.errors,h.totalSupply,h.totalUse,h.calculated,h.adjustment,h.adopted,h.surveyed,h.difference,h.balanced,formulas.get(h.formula),List.copyOf(sources.getOrDefault(h.id,List.of())))).toList();}
 private SupplyFormulaView formula(long id){FormulaRow f=jdbc.sql("SELECT formula_version_id,code,version_no,name,precision_value,scale_value,tolerance,difference_code,difference_label,difference_expression FROM supply.formula_version WHERE formula_version_id=:id").param("id",id).query((r,i)->new FormulaRow(r.getLong("formula_version_id"),r.getString("code"),r.getInt("version_no"),r.getString("name"),r.getInt("precision_value"),r.getInt("scale_value"),r.getBigDecimal("tolerance"),r.getString("difference_code"),r.getString("difference_label"),r.getString("difference_expression"))).single();List<SupplyFormulaView.Expression> expressions=jdbc.sql("SELECT result_code,label,expression,sort_order FROM supply.formula_expression WHERE formula_version_id=:id ORDER BY sort_order").param("id",id).query((r,i)->new SupplyFormulaView.Expression(r.getString("result_code"),r.getString("label"),r.getString("expression"),r.getInt("sort_order"))).list();return new SupplyFormulaView(f.code,f.version,f.name,f.precision,f.scale,f.tolerance.setScale(f.scale).toPlainString(),f.differenceCode,f.differenceLabel,f.differenceExpression,expressions);}
 private boolean exists(String sql,String value){return Boolean.TRUE.equals(jdbc.sql(sql).param("value",value).query(Boolean.class).single());}
 private static void require(int n){if(n==0)throw new ConflictException("SUPPLY_DECISION_VERSION_CONFLICT","Supply decision has changed");}
 private static ClientRequestException invalid(){return new ClientRequestException("INVALID_SUPPLY_ACCOUNT_REQUEST","Supply account request is invalid");}
 private static String route(String domain,String id){return "/api/v1/"+domain.toLowerCase(Locale.ROOT)+"-records/"+id;}
 private static String plain(BigDecimal v){return v==null?null:v.toPlainString();}
 private static List<String> strings(Array a){if(a==null)return List.of();try{return List.of((String[])a.getArray());}catch(SQLException e){throw new IllegalStateException(e);}}
 private record FormulaRow(long id,String code,int version,String name,int precision,int scale,BigDecimal tolerance,String differenceCode,String differenceLabel,String differenceExpression){}
 private record SourceRow(String releaseId,String domain,String record,long sourceVersion,String approvedAt,String quality,String role,String label,String group,BigDecimal value){}
 private record Header(String id,String product,String region,String year,int version,String state,List<String> errors,String totalSupply,String totalUse,String calculated,String adjustment,String adopted,String surveyed,String difference,boolean balanced,long formula){}
}

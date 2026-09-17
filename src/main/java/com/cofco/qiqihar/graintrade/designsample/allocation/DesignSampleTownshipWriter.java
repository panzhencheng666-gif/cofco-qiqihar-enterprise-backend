package com.cofco.qiqihar.graintrade.designsample.allocation;

import com.cofco.qiqihar.graintrade.identity.application.SmsChallengeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignSampleTownshipWriter {
    private final JdbcClient jdbc;private final DesignSampleAllocationPlanner planner;
    public DesignSampleTownshipWriter(JdbcClient jdbc,DesignSampleAllocationPlanner planner){this.jdbc=jdbc;this.planner=planner;}

    @Transactional
    public TownshipWriteResult apply(String townshipCode,String actor,UUID runId) {
        return apply(townshipCode,actor,runId,Instant.now());
    }

    @Transactional
    public StageResult stageSurplus(String townshipCode,String actor,UUID runId,Instant candidateCutoff) {
        jdbc.sql("SELECT pg_advisory_xact_lock(209,0)").query(Object.class).single();
        jdbc.sql("LOCK TABLE platform.design_sample_point IN SHARE ROW EXCLUSIVE MODE").update();
        String name=jdbc.sql("SELECT name FROM platform.region WHERE code=:code AND administrative_level='TOWNSHIP'")
                .param("code",townshipCode).query(String.class).optional().orElseThrow();
        var plan=planner.planTownship(townshipCode,name);
        if(!plan.ready())return new StageResult(townshipCode,0,plan.blockers());
        List<BatchRow> active=jdbc.sql("""
                WITH RECURSIVE ancestors(code,parent_code) AS (
                  SELECT code,parent_code FROM platform.region WHERE code=:township
                  UNION ALL SELECT parent.code,parent.parent_code
                  FROM platform.region parent JOIN ancestors child ON child.parent_code=parent.code)
                SELECT point.design_sample_point_id,point.region_code,point.lifecycle_status,
                       region.administrative_level,region.parent_code,point.idempotency_key
                FROM platform.design_sample_point point JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE' AND point.created_at<=:cutoff AND (
                     point.region_code=:township
                  OR (region.parent_code=:township AND region.administrative_level='VILLAGE')
                  OR point.region_code IN (SELECT code FROM ancestors WHERE code<>:township))
                ORDER BY point.created_at,point.design_sample_point_id
                FOR UPDATE OF point
                """).param("township",townshipCode).param("cutoff",Timestamp.from(candidateCutoff))
                .query((r,n)->new BatchRow(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
                        r.getString(4),r.getString(5),r.getString(6))).list();
        Set<String> keptVillages=new HashSet<>();int expired=0;
        for(BatchRow row:active){
            boolean createdByRun=row.idempotencyKey()!=null
                    &&row.idempotencyKey().startsWith("allocation:"+runId+":");
            if(!createdByRun&&plan.selectedVillageCodes().contains(row.region())
                    &&keptVillages.add(row.region()))continue;
            expire(row.id(),actor,runId);expired++;
        }
        return new StageResult(townshipCode,expired,List.of());
    }

    @Transactional
    public TownshipWriteResult apply(String townshipCode,String actor,UUID runId,Instant candidateCutoff) {
        jdbc.sql("SELECT pg_advisory_xact_lock(209,0)").query(Object.class).single();
        jdbc.sql("LOCK TABLE platform.design_sample_point IN SHARE ROW EXCLUSIVE MODE").update();
        String name=jdbc.sql("SELECT name FROM platform.region WHERE code=:code AND administrative_level='TOWNSHIP'")
                .param("code",townshipCode).query(String.class).optional().orElseThrow();
        var plan=planner.planTownship(townshipCode,name);
        if(!plan.ready())return new TownshipWriteResult(townshipCode,0,0,0,0,plan.blockers());
        List<BatchRow> rows=jdbc.sql("""
                WITH RECURSIVE ancestors(code,parent_code) AS (
                  SELECT code,parent_code FROM platform.region WHERE code=:township
                  UNION ALL SELECT parent.code,parent.parent_code
                  FROM platform.region parent JOIN ancestors child ON child.parent_code=parent.code)
                SELECT point.design_sample_point_id,point.region_code,point.lifecycle_status,
                       region.administrative_level,region.parent_code,point.idempotency_key
                FROM platform.design_sample_point point JOIN platform.region region ON region.code=point.region_code
                WHERE (point.lifecycle_status='ACTIVE' AND point.created_at<=:cutoff AND (
                         point.region_code=:township
                      OR (region.parent_code=:township AND region.administrative_level='VILLAGE')
                      OR point.region_code IN (SELECT code FROM ancestors WHERE code<>:township)))
                   OR (point.lifecycle_status='EXPIRED' AND point.assignment_run_id=:run)
                ORDER BY CASE point.lifecycle_status WHEN 'EXPIRED' THEN 0 ELSE 1 END,
                         point.created_at,point.design_sample_point_id
                FOR UPDATE OF point
                """).param("township",townshipCode).param("run",runId)
                .param("cutoff",Timestamp.from(candidateCutoff))
                .query((r,n)->new BatchRow(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
                        r.getString(4),r.getString(5),r.getString(6))).list();
        Map<String,BatchRow> keep=new HashMap<>();List<BatchRow> reusable=new ArrayList<>();
        for(BatchRow row:rows){
            if("ACTIVE".equals(row.status())&&plan.selectedVillageCodes().contains(row.region())
                    &&!keep.containsKey(row.region()))keep.put(row.region(),row);
            else reusable.add(row);
        }
        int newlyExpired=0;
        for(BatchRow row:reusable)if("ACTIVE".equals(row.status())){expire(row.id(),actor,runId);newlyExpired++;}
        List<String> missing=plan.selectedVillageCodes().stream().filter(code->!keep.containsKey(code)).toList();
        int moved=Math.min(reusable.size(),missing.size());
        for(int i=0;i<moved;i++){BatchRow row=reusable.get(i);String target=missing.get(i);assign(row.id(),target,actor,runId);}
        int created=0;for(int i=moved;i<missing.size();i++){create(missing.get(i),actor,runId);created++;}
        for(var entry:keep.entrySet())if(plan.selectedVillageCodes().contains(entry.getKey()))
            assign(entry.getValue().id(),entry.getKey(),actor,runId);
        return new TownshipWriteResult(townshipCode,created,moved,reusable.size()-moved,newlyExpired,List.of());
    }

    private void assign(UUID id,String village,String actor,UUID run) {
        Location location=location(village);
        jdbc.sql("""
                UPDATE platform.design_sample_point SET contract_version='design-sample-fields-v3',
                  domain_code='REFERENCE',product_code='GENERAL',object_type_code='REFERENCE_POINT',
                  sample_name=:name,region_code=:region,governed_point=ST_SetSRID(ST_MakePoint(:lon,:lat),4326),
                  values_json=values_json || jsonb_build_object('DSP_NAME',:name,'DSP_REGION_CODE',:region,
                    'DSP_ADDRESS',:address,'DSP_LONGITUDE',:lon,'DSP_LATITUDE',:lat),
                  detailed_address=:address,assignment_run_id=:run,coordinate_seed=:seed,
                  lifecycle_status='ACTIVE',expired_at=NULL,version=version+1,updated_by=:actor,updated_at=now()
                WHERE design_sample_point_id=:id
                """).param("name",location.name()).param("region",village).param("lon",location.longitude())
                .param("lat",location.latitude()).param("address",location.address()).param("run",run)
                .param("seed",location.seed()).param("actor",actor).param("id",id).update();
    }
    private void create(String village,String actor,UUID run) {
        Location location=location(village);UUID id=UUID.randomUUID();String key="allocation:"+run+":"+village;
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by,detailed_address,assignment_run_id,coordinate_seed)
                VALUES(:id,'design-sample-fields-v3','REFERENCE','GENERAL','REFERENCE_POINT',
                  jsonb_build_object('DSP_NAME',:name,'DSP_REGION_CODE',:region,'DSP_ADDRESS',:address,
                    'DSP_LONGITUDE',:lon,'DSP_LATITUDE',:lat),:name,:region,
                  ST_SetSRID(ST_MakePoint(:lon,:lat),4326),:key,:digest,:actor,:actor,:address,:run,:seed)
                """).param("id",id).param("name",location.name()).param("region",village)
                .param("address",location.address()).param("lon",location.longitude()).param("lat",location.latitude())
                .param("key",key).param("digest",SmsChallengeService.hash(key)).param("actor",actor)
                .param("run",run).param("seed",location.seed()).update();
    }
    private void expire(UUID id,String actor,UUID run) {
        jdbc.sql("""
                UPDATE platform.design_sample_point SET lifecycle_status='EXPIRED',expired_at=now(),
                  assignment_run_id=:run,version=version+1,updated_by=:actor,updated_at=now()
                WHERE design_sample_point_id=:id
                """).param("run",run).param("actor",actor).param("id",id).update();
    }
    private Location location(String village) {
        var metadata=jdbc.sql("""
                WITH RECURSIVE path(code,parent_code,name,names) AS (
                  SELECT code,parent_code,name,ARRAY[name]::text[] FROM platform.region WHERE parent_code IS NULL
                  UNION ALL SELECT child.code,child.parent_code,child.name,path.names||child.name
                  FROM platform.region child JOIN path ON path.code=child.parent_code)
                SELECT region.name,array_to_string(path.names,' / '),boundary.geometry_sha256
                FROM platform.region region JOIN path ON path.code=region.code
                JOIN overview.administrative_boundary boundary ON boundary.region_code=region.code
                WHERE region.code=:village
                """).param("village",village).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)}).single();
        String seedText="v1:"+village+":"+metadata[2];int seed=Math.floorMod(seedText.hashCode(),Integer.MAX_VALUE-1)+1;
        var point=jdbc.sql("""
                SELECT ST_X(g.geom)::numeric,ST_Y(g.geom)::numeric
                FROM overview.administrative_boundary boundary
                CROSS JOIN LATERAL (SELECT CASE
                  WHEN ST_IsEmpty(ST_CollectionExtract(ST_Buffer(boundary.geometry,-0.0001),3))
                    THEN boundary.geometry
                  ELSE ST_Multi(ST_CollectionExtract(ST_Buffer(boundary.geometry,-0.0001),3))
                  END AS geometry) safe
                CROSS JOIN LATERAL ST_Dump(ST_GeneratePoints(safe.geometry,1,:seed)) g
                WHERE boundary.region_code=:village
                """).param("seed",seed).param("village",village)
                .query((r,n)->new BigDecimal[]{r.getBigDecimal(1),r.getBigDecimal(2)}).single();
        return new Location(metadata[0]+"设计样本点",metadata[1]+" / 村内设计样本点",point[0],point[1],seedText);
    }
    private record BatchRow(UUID id,String region,String status,String level,String parent,String idempotencyKey) {}
    private record Location(String name,String address,BigDecimal longitude,BigDecimal latitude,String seed) {}
    public record StageResult(String townshipCode,int expired,List<String> blockers) {}
    public record TownshipWriteResult(String townshipCode,int created,int moved,int expired,int newlyExpired,List<String> blockers) {}
    public record ApplyResult(String townshipCode,int created,int moved,int expired,List<String> blockers) {}
}

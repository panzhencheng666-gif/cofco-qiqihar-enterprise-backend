package com.cofco.qiqihar.graintrade.designsample.allocation;

import com.cofco.qiqihar.graintrade.identity.application.SmsChallengeService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignSampleTownshipWriter {
    private final JdbcClient jdbc;private final DesignSampleAllocationPlanner planner;
    public DesignSampleTownshipWriter(JdbcClient jdbc,DesignSampleAllocationPlanner planner){this.jdbc=jdbc;this.planner=planner;}

    @Transactional
    public ApplyResult apply(String townshipCode,String actor,UUID runId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(209,hashtext(:township))").param("township",townshipCode)
                .query(Object.class).single();
        String name=jdbc.sql("SELECT name FROM platform.region WHERE code=:code AND administrative_level='TOWNSHIP'")
                .param("code",townshipCode).query(String.class).optional().orElseThrow();
        var plan=planner.planTownship(townshipCode,name);
        if(!plan.ready())return new ApplyResult(townshipCode,0,0,0,plan.blockers());
        List<Row> active=jdbc.sql("""
                SELECT point.design_sample_point_id,point.region_code
                FROM platform.design_sample_point point JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE' AND (point.region_code=:township OR region.parent_code=:township)
                ORDER BY point.created_at,point.design_sample_point_id
                FOR UPDATE OF point
                """).param("township",townshipCode).query((r,n)->new Row(r.getObject(1,UUID.class),r.getString(2))).list();
        Map<String,Row> keep=new HashMap<>();List<Row> reusable=new ArrayList<>();
        for(Row row:active){if(plan.selectedVillageCodes().contains(row.region())&&!keep.containsKey(row.region()))keep.put(row.region(),row);else reusable.add(row);}
        List<String> missing=plan.selectedVillageCodes().stream().filter(code->!keep.containsKey(code)).toList();
        int moved=Math.min(reusable.size(),missing.size());
        for(int i=0;i<moved;i++){Row row=reusable.get(i);String target=missing.get(i);assign(row.id(),target,actor,runId);keep.put(target,new Row(row.id(),target));}
        int expired=0;for(int i=moved;i<reusable.size();i++){jdbc.sql("""
                UPDATE platform.design_sample_point SET lifecycle_status='EXPIRED',expired_at=now(),
                  assignment_run_id=:run,version=version+1,updated_by=:actor,updated_at=now()
                WHERE design_sample_point_id=:id
                """).param("run",runId).param("actor",actor).param("id",reusable.get(i).id()).update();expired++;}
        int created=0;for(int i=moved;i<missing.size();i++){create(missing.get(i),actor,runId);created++;}
        for(var entry:keep.entrySet())if(plan.selectedVillageCodes().contains(entry.getKey()))
            assign(entry.getValue().id(),entry.getKey(),actor,runId);
        return new ApplyResult(townshipCode,created,moved,expired,List.of());
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
    private record Row(UUID id,String region) {}
    private record Location(String name,String address,BigDecimal longitude,BigDecimal latitude,String seed) {}
    public record ApplyResult(String townshipCode,int created,int moved,int expired,List<String> blockers) {}
}

package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.RegionResponsibility;
import com.cofco.qiqihar.graintrade.identity.application.RegionResponsibilityRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionResponsibilityRepository implements RegionResponsibilityRepository {
    private final JdbcClient jdbc;
    public JdbcRegionResponsibilityRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public void lockChange(){jdbc.sql("SELECT platform.lock_region_responsibility_change(),1").query((r,i)->r.getInt(2)).single();}
    public List<String> ownedRegions(String subject){
        return jdbc.sql("SELECT region_code FROM platform.region_responsibility WHERE subject_id=:subject ORDER BY region_code")
            .param("subject",subject).query(String.class).list();
    }
    public List<RegionResponsibility.Region> regions(List<String> codes){
        if(codes.isEmpty())return List.of();
        return jdbc.sql("""
            SELECT region.code,region.name,responsibility.subject_id,employee.display_name,coalesce(responsibility.version,-1)
            FROM platform.region region LEFT JOIN platform.region_responsibility responsibility ON responsibility.region_code=region.code
            LEFT JOIN platform.security_user employee ON employee.subject_id=responsibility.subject_id
            WHERE region.code IN (:codes) ORDER BY region.code
            """).param("codes",codes).query((r,i)->new RegionResponsibility.Region(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5))).list();
    }
    public List<RegionResponsibility.Sample> samples(List<String> affected,List<String> selected,String subject,String name){
        if(affected.isEmpty())return List.of();
        return jdbc.sql("""
            WITH RECURSIVE covered(code,root) AS (
                SELECT code,code FROM platform.region WHERE code IN (:affected)
                UNION ALL SELECT child.code,parent.root FROM platform.region child JOIN covered parent ON child.parent_code=parent.code
            )
            SELECT point.sample_point_id,point.canonical_name,point.region_code,region.name,
                point.maintainer_subject_id,employee.display_name,point.version,covered.root
            FROM registry.sample_point point JOIN covered ON covered.code=point.region_code
            JOIN platform.region region ON region.code=point.region_code
            LEFT JOIN platform.security_user employee ON employee.subject_id=point.maintainer_subject_id
            WHERE point.deletion_state='ACTIVE' ORDER BY point.sample_point_id
            """).param("affected",affected).query((r,i)->{
                boolean chosen=selected.contains(r.getString(8));
                String previous=r.getString(5);
                String next=chosen?subject:(subject.equals(previous)?null:previous);
                String nextName=chosen?name:(next==null?null:r.getString(6));
                return new RegionResponsibility.Sample(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),previous,r.getString(6),next,nextName,r.getLong(7));
            }).list();
    }
    public void save(String subject,List<String> selected,List<String> affected,String actor,String reason){
        for(String code:affected){
            boolean chosen=selected.contains(code);
            jdbc.sql("""
                INSERT INTO platform.region_responsibility(region_code,subject_id,updated_by,reason)
                VALUES(:code,:subject,:actor,:reason)
                ON CONFLICT(region_code) DO UPDATE SET subject_id=EXCLUDED.subject_id,version=region_responsibility.version+1,
                    updated_by=EXCLUDED.updated_by,updated_at=clock_timestamp(),reason=EXCLUDED.reason
                """).param("code",code).param("subject",chosen?subject:null,java.sql.Types.VARCHAR).param("actor",actor).param("reason",reason).update();
            if(chosen)jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from,granted_by,granted_at,review_due_at)
                SELECT :subject,:code,now(),:actor,now(),now()+interval '1 year'
                WHERE NOT EXISTS(SELECT 1 FROM platform.security_user_region_scope WHERE subject_id=:subject AND region_code=:code
                    AND CURRENT_TIMESTAMP>=valid_from AND (valid_until IS NULL OR CURRENT_TIMESTAMP<valid_until)
                    AND (review_due_at IS NULL OR CURRENT_TIMESTAMP<review_due_at))
                """).param("subject",subject).param("code",code).param("actor",actor).update();
        }
        // The service captured and locked the complete sample set before this single transaction.
        for(var point:samples(affected,selected,subject,"")){
            if(java.util.Objects.equals(point.previousSubjectId(),point.nextSubjectId()))continue;
            jdbc.sql("""
                UPDATE registry.sample_point SET maintainer_subject_id=:subject,version=version+1,updated_by=:actor,updated_at=clock_timestamp()
                WHERE sample_point_id=:id
                """).param("subject",point.nextSubjectId(),java.sql.Types.VARCHAR).param("actor",actor).param("id",point.id()).update();
        }
    }
}

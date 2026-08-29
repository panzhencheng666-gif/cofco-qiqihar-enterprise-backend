package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.EmployeeAssignment;
import com.cofco.qiqihar.graintrade.identity.application.EmployeeProfile;
import com.cofco.qiqihar.graintrade.identity.application.IdentityGovernanceRepository;
import com.cofco.qiqihar.graintrade.identity.application.AssignmentOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityGovernanceRepository implements IdentityGovernanceRepository {
    private final JdbcClient jdbc;

    public JdbcIdentityGovernanceRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    @Override
    public boolean validAssignment(EmployeeAssignment value) {
        if(value.roleCodes().isEmpty()||value.regionCodes().isEmpty())return false;
        boolean privileged=value.roleCodes().stream().anyMatch(
                role->role.equals("SYSTEM_ADMIN")||role.equals("IDENTITY_ADMIN"));
        return count("SELECT count(*) FROM platform.work_unit WHERE code=:code AND active","code",value.workUnitCode())==1
                && countIn("SELECT count(*) FROM platform.access_role WHERE code IN (:codes) AND active",value.roleCodes())==value.roleCodes().size()
                && (value.positionCodes().isEmpty()
                    || countIn("SELECT count(*) FROM platform.position WHERE code IN (:codes) AND active",value.positionCodes())==value.positionCodes().size())
                && jdbc.sql("""
                        WITH RECURSIVE authorized_region(code) AS (
                            SELECT scope.region_code
                            FROM platform.work_unit_region_scope scope
                            WHERE scope.work_unit_code=:unit
                            UNION
                            SELECT child.code
                            FROM platform.region child
                            JOIN authorized_region parent ON child.parent_code=parent.code
                        )
                        SELECT count(*) FROM authorized_region
                        WHERE code IN (:regions)
                        """).param("unit",value.workUnitCode()).param("regions",value.regionCodes())
                        .query(Long.class).single()==value.regionCodes().size()
                && (privileged || jdbc.sql("""
                        SELECT count(*) FROM platform.region
                        WHERE code IN (:regions) AND administrative_level='TOWNSHIP'
                        """).param("regions",value.regionCodes()).query(Long.class).single()==value.regionCodes().size());
    }

    @Override public boolean exists(String subjectId){return count(
            "SELECT count(*) FROM platform.security_user WHERE subject_id=:subject","subject",subjectId)>0;}

    @Override
    public EmployeeProfile create(String subjectId,EmployeeAssignment value,String actor) {
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled,
                    account_status,employment_status,activated_at,version,updated_at)
                VALUES(:subject,:name,:unit,false,'INVITED','ACTIVE',NULL,0,now())
                """).param("subject",subjectId).param("name",value.displayName())
                .param("unit",value.workUnitCode()).update();
        replaceAssignments(subjectId,value,actor);
        return find(subjectId,null).orElseThrow();
    }

    @Override
    public Optional<EmployeeProfile> update(String subjectId,long expectedVersion,EmployeeAssignment value,String actor) {
        boolean enabled=value.accountStatus().equals("ACTIVE")&&value.employmentStatus().equals("ACTIVE");
        int updated=jdbc.sql("""
                UPDATE platform.security_user
                SET display_name=:name,work_unit_code=:unit,account_status=:accountStatus,
                    employment_status=:employmentStatus,enabled=:enabled,
                    activated_at=CASE WHEN :accountStatus='ACTIVE' AND activated_at IS NULL THEN now() ELSE activated_at END,
                    suspended_at=CASE WHEN :accountStatus IN ('LOCKED','SUSPENDED','REVOKED') THEN now() ELSE NULL END,
                    termination_effective_at=CASE WHEN :employmentStatus='TERMINATED' THEN now() ELSE NULL END,
                    version=version+1,updated_at=now()
                WHERE subject_id=:subject AND version=:version
                """).param("name",value.displayName()).param("unit",value.workUnitCode())
                .param("accountStatus",value.accountStatus()).param("employmentStatus",value.employmentStatus())
                .param("enabled",enabled).param("subject",subjectId).param("version",expectedVersion).update();
        if(updated==0)return Optional.empty();
        replaceAssignments(subjectId,value,actor);
        return find(subjectId,null);
    }

    @Override public Optional<EmployeeProfile> find(String subjectId,String workUnitCode){
        String unitFilter=workUnitCode==null ? "" : " AND security_user.work_unit_code=:unit";
        JdbcClient.StatementSpec query=jdbc.sql("""
                SELECT security_user.subject_id,security_user.display_name,security_user.work_unit_code,
                       work_unit.name,security_user.account_status,security_user.employment_status,
                       security_user.version
                FROM platform.security_user security_user
                JOIN platform.work_unit work_unit ON work_unit.code=security_user.work_unit_code
                WHERE security_user.subject_id=:subject
                %s
                """.formatted(unitFilter)).param("subject",subjectId);
        if(workUnitCode!=null)query=query.param("unit",workUnitCode);
        return query.query((row,index)->profile(
                        row.getString(1),row.getString(2),row.getString(3),row.getString(4),
                        row.getString(5),row.getString(6),row.getLong(7))).optional();
    }

    @Override public List<EmployeeProfile> findAll(String workUnitCode){
        String unitFilter=workUnitCode==null ? "" : " WHERE security_user.work_unit_code=:unit";
        JdbcClient.StatementSpec query=jdbc.sql("""
                SELECT security_user.subject_id,security_user.display_name,security_user.work_unit_code,
                       work_unit.name,security_user.account_status,security_user.employment_status,
                       security_user.version
                FROM platform.security_user security_user
                JOIN platform.work_unit work_unit ON work_unit.code=security_user.work_unit_code
                %s
                ORDER BY work_unit.sort_order,security_user.display_name,security_user.subject_id
                """.formatted(unitFilter));
        if(workUnitCode!=null)query=query.param("unit",workUnitCode);
        return query.query((row,index)->profile(row.getString(1),row.getString(2),row.getString(3),row.getString(4),
                        row.getString(5),row.getString(6),row.getLong(7))).list();
    }

    @Override
    public AssignmentOptions assignmentOptions(String workUnitCode) {
        List<AssignmentOptions.Option> workUnits=jdbc.sql("""
                SELECT code,name FROM platform.work_unit WHERE active ORDER BY sort_order,code
                """).query((row,index)->new AssignmentOptions.Option(row.getString(1),row.getString(2))).list();
        List<AssignmentOptions.Option> roles=jdbc.sql("""
                SELECT code,name FROM platform.access_role WHERE active ORDER BY sort_order,code
                """).query((row,index)->new AssignmentOptions.Option(row.getString(1),row.getString(2))).list();
        List<AssignmentOptions.Option> positions=List.of();
        List<String> regions=jdbc.sql("""
                WITH RECURSIVE authorized_region(code) AS (
                    SELECT scope.region_code
                    FROM platform.work_unit_region_scope scope
                    WHERE scope.work_unit_code=:unit
                    UNION
                    SELECT child.code
                    FROM platform.region child
                    JOIN authorized_region parent ON child.parent_code=parent.code
                )
                SELECT region.code
                FROM platform.region region
                JOIN authorized_region authorized ON authorized.code=region.code
                WHERE region.administrative_level='TOWNSHIP'
                ORDER BY region.code
                """).param("unit",workUnitCode).query(String.class).list();
        if(workUnits.stream().noneMatch(unit->unit.code().equals(workUnitCode)))return new AssignmentOptions(
                workUnits,roles,positions,List.of());
        return new AssignmentOptions(workUnits,roles,positions,regions);
    }

    private EmployeeProfile profile(String subject,String name,String unit,String unitName,
            String accountStatus,String employmentStatus,long version) {
        List<EmployeeProfile.Grant> roles=jdbc.sql("""
                SELECT role.code,role.name FROM platform.security_user_role assignment
                JOIN platform.access_role role ON role.code=assignment.role_code
                WHERE assignment.subject_id=:subject
                  AND CURRENT_TIMESTAMP>=assignment.valid_from
                  AND (assignment.valid_until IS NULL OR CURRENT_TIMESTAMP<assignment.valid_until)
                  AND (assignment.review_due_at IS NULL OR CURRENT_TIMESTAMP<assignment.review_due_at)
                ORDER BY role.sort_order,role.code
                """).param("subject",subject).query((row,index)->new EmployeeProfile.Grant(
                        row.getString(1),row.getString(2))).list();
        List<EmployeeProfile.Position> positions=jdbc.sql("""
                SELECT position.code,position.name,assignment.primary_position
                FROM platform.security_user_position assignment
                JOIN platform.position position ON position.code=assignment.position_code
                WHERE assignment.subject_id=:subject
                  AND CURRENT_TIMESTAMP>=assignment.valid_from
                  AND (assignment.valid_until IS NULL OR CURRENT_TIMESTAMP<assignment.valid_until)
                ORDER BY assignment.primary_position DESC,position.sort_order,position.code
                """).param("subject",subject).query((row,index)->new EmployeeProfile.Position(
                        row.getString(1),row.getString(2),row.getBoolean(3))).list();
        List<String> regions=jdbc.sql("""
                SELECT region_code FROM platform.security_user_region_scope
                WHERE subject_id=:subject
                  AND CURRENT_TIMESTAMP>=valid_from
                  AND (valid_until IS NULL OR CURRENT_TIMESTAMP<valid_until)
                  AND (review_due_at IS NULL OR CURRENT_TIMESTAMP<review_due_at)
                ORDER BY region_code
                """).param("subject",subject).query(String.class).list();
        return new EmployeeProfile(subject,name,unit,unitName,accountStatus,employmentStatus,
                roles,positions,regions,version);
    }

    private void replaceAssignments(String subject,EmployeeAssignment value,String actor) {
        jdbc.sql("""
                UPDATE platform.security_user_role SET valid_until=now()
                WHERE subject_id=:subject AND valid_until IS NULL
                """)
                .param("subject",subject).update();
        jdbc.sql("""
                UPDATE platform.security_user_region_scope SET valid_until=now()
                WHERE subject_id=:subject AND valid_until IS NULL
                """).param("subject",subject).update();
        jdbc.sql("""
                UPDATE platform.security_user_position SET valid_until=now()
                WHERE subject_id=:subject AND valid_until IS NULL
                """).param("subject",subject).update();
        if(value.employmentStatus().equals("TERMINATED")||value.accountStatus().equals("REVOKED"))return;
        value.roleCodes().forEach(code->jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code,valid_from,granted_by,granted_at,review_due_at)
                VALUES(:subject,:code,now(),:actor,now(),now()+interval '1 year')
                """).param("subject",subject).param("code",code).param("actor",actor).update());
        value.regionCodes().forEach(code->jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from,granted_by,granted_at,review_due_at)
                VALUES(:subject,:code,now(),:actor,now(),now()+interval '1 year')
                """).param("subject",subject).param("code",code).param("actor",actor).update());
        for(int index=0;index<value.positionCodes().size();index++){
            jdbc.sql("""
                    INSERT INTO platform.security_user_position(subject_id,position_code,valid_from,
                        primary_position,assigned_by,assigned_at)
                    VALUES(:subject,:code,now(),:primary,:actor,now())
                    """).param("subject",subject).param("code",value.positionCodes().get(index))
                    .param("primary",index==0).param("actor",actor).update();
        }
    }

    private long count(String sql,String parameter,String value){return jdbc.sql(sql).param(parameter,value).query(Long.class).single();}
    private long countIn(String sql,List<String> values){return jdbc.sql(sql).param("codes",values).query(Long.class).single();}
}

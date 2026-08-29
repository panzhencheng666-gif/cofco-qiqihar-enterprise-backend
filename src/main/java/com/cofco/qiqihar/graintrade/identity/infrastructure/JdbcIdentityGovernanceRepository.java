package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.EmployeeAssignment;
import com.cofco.qiqihar.graintrade.identity.application.EmployeeProfile;
import com.cofco.qiqihar.graintrade.identity.application.IdentityGovernanceRepository;
import com.cofco.qiqihar.graintrade.identity.application.AssignmentOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.cofco.qiqihar.graintrade.identity.application.IdentityInvitation;
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
                        SELECT count(*)
                        FROM platform.region region
                        WHERE region.code IN (:regions)
                          AND (region.administrative_level='TOWNSHIP'
                            OR (region.administrative_level='COUNTY'
                              AND EXISTS (
                                SELECT 1 FROM platform.monitoring_scope_region governed
                                WHERE governed.scope_code='FORMAL_BUSINESS'
                                  AND governed.region_code=region.code AND governed.included)
                              AND NOT EXISTS (
                                WITH RECURSIVE descendant(code,administrative_level) AS (
                                  SELECT child.code,child.administrative_level
                                  FROM platform.region child WHERE child.parent_code=region.code
                                  UNION ALL
                                  SELECT child.code,child.administrative_level
                                  FROM platform.region child
                                  JOIN descendant parent ON child.parent_code=parent.code)
                                SELECT 1 FROM descendant
                                WHERE administrative_level='TOWNSHIP')))
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
        List<AssignmentOptions.RegionOption> regions=jdbc.sql("""
                WITH RECURSIVE authorized_region(code) AS (
                    SELECT scope.region_code
                    FROM platform.work_unit_region_scope scope
                    WHERE scope.work_unit_code=:unit
                    UNION
                    SELECT child.code
                    FROM platform.region child
                    JOIN authorized_region parent ON child.parent_code=parent.code
                )
                SELECT region.code,region.name,region.administrative_level,region.parent_code
                FROM platform.region region
                JOIN authorized_region authorized ON authorized.code=region.code
                WHERE region.administrative_level='TOWNSHIP'
                   OR (region.administrative_level='COUNTY'
                     AND EXISTS (
                       SELECT 1 FROM platform.monitoring_scope_region governed
                       WHERE governed.scope_code='FORMAL_BUSINESS'
                         AND governed.region_code=region.code AND governed.included)
                     AND NOT EXISTS (
                       WITH RECURSIVE descendant(code,administrative_level) AS (
                         SELECT child.code,child.administrative_level
                         FROM platform.region child WHERE child.parent_code=region.code
                         UNION ALL
                         SELECT child.code,child.administrative_level
                         FROM platform.region child
                         JOIN descendant parent ON child.parent_code=parent.code)
                       SELECT 1 FROM descendant WHERE administrative_level='TOWNSHIP'))
                ORDER BY region.code
                """).param("unit",workUnitCode).query((row,index)->new AssignmentOptions.RegionOption(
                        row.getString(1),row.getString(2),row.getString(3),row.getString(4))).list();
        if(workUnits.stream().noneMatch(unit->unit.code().equals(workUnitCode)))return new AssignmentOptions(
                workUnits,roles,positions,List.of(),List.of());
        return new AssignmentOptions(workUnits,roles,positions,
                regions.stream().map(AssignmentOptions.RegionOption::code).toList(),regions);
    }

    @Override
    public Optional<IdentityInvitation> findInvitationByIdempotency(
            String actorSubjectId,String idempotencyKey) {
        return jdbc.sql("""
                SELECT invitation_id,security_subject_id,state,delivery_status,
                       expires_at,request_fingerprint
                FROM platform.identity_invitation
                WHERE created_by=:actor AND idempotency_key=:key
                """).param("actor",actorSubjectId).param("key",idempotencyKey)
                .query((row,index)->new IdentityInvitation(
                        row.getObject(1,UUID.class),row.getString(2),row.getString(3),
                        row.getString(4),row.getTimestamp(5).toInstant(),row.getString(6)))
                .optional();
    }

    @Override
    public Optional<IdentityInvitation> findInvitation(UUID invitationId) {
        return jdbc.sql("""
                SELECT invitation_id,security_subject_id,state,delivery_status,
                       expires_at,request_fingerprint
                FROM platform.identity_invitation WHERE invitation_id=:id
                """).param("id",invitationId).query((row,index)->new IdentityInvitation(
                        row.getObject(1,UUID.class),row.getString(2),row.getString(3),
                        row.getString(4),row.getTimestamp(5).toInstant(),row.getString(6))).optional();
    }

    @Override
    public Optional<IdentityInvitation> findCurrentInvitation(String subjectId) {
        return jdbc.sql("""
                SELECT invitation_id,security_subject_id,state,delivery_status,
                       expires_at,request_fingerprint
                FROM platform.identity_invitation
                WHERE security_subject_id=:subject
                ORDER BY created_at DESC,invitation_id DESC
                LIMIT 1
                """).param("subject",subjectId).query((row,index)->new IdentityInvitation(
                        row.getObject(1,UUID.class),row.getString(2),row.getString(3),
                        row.getString(4),row.getTimestamp(5).toInstant(),row.getString(6))).optional();
    }

    @Override
    public Optional<IdentityInvitation> revokeInvitation(UUID invitationId,Instant revokedAt) {
        IdentityInvitation existing=findInvitation(invitationId).orElse(null);
        if(existing==null||existing.invitationStatus().equals("ACTIVATED"))return Optional.empty();
        if(existing.invitationStatus().equals("PENDING"))jdbc.sql("""
                UPDATE platform.identity_invitation
                SET state='REVOKED',revoked_at=:revokedAt,version=version+1
                WHERE invitation_id=:id AND state='PENDING'
                """).param("revokedAt",Timestamp.from(revokedAt)).param("id",invitationId).update();
        return findInvitation(invitationId);
    }

    @Override
    public void revokePendingInvitations(String subjectId,Instant revokedAt) {
        jdbc.sql("""
                UPDATE platform.identity_invitation
                SET state='REVOKED',revoked_at=:revokedAt,version=version+1
                WHERE security_subject_id=:subject AND state='PENDING'
                """).param("revokedAt",Timestamp.from(revokedAt)).param("subject",subjectId).update();
    }

    @Override
    public IdentityInvitation createInvitation(
            UUID invitationId,String subjectId,String tokenSha256,
            String encryptedDeliveryPayload,String deliveryAddressSha256,Instant expiresAt,
            String actorSubjectId,String idempotencyKey,String requestSha256) {
        jdbc.sql("""
                INSERT INTO platform.identity_invitation(
                    invitation_id,security_subject_id,token_hash,encrypted_delivery_payload,
                    delivery_address_sha256,expires_at,created_by,idempotency_key,request_fingerprint)
                VALUES(:id,:subject,:tokenHash,:payload,:addressHash,:expiresAt,:actor,:key,:requestHash)
                """).param("id",invitationId).param("subject",subjectId)
                .param("tokenHash",tokenSha256).param("payload",encryptedDeliveryPayload)
                .param("addressHash",deliveryAddressSha256).param("expiresAt",Timestamp.from(expiresAt))
                .param("actor",actorSubjectId).param("key",idempotencyKey)
                .param("requestHash",requestSha256).update();
        jdbc.sql("""
                INSERT INTO platform.identity_delivery_outbox(
                    event_id,invitation_id,security_subject_id,event_type)
                VALUES(:eventId,:invitationId,:subject,'INVITATION_DELIVERY')
                """).param("eventId",UUID.randomUUID()).param("invitationId",invitationId)
                .param("subject",subjectId).update();
        return findInvitationByIdempotency(actorSubjectId,idempotencyKey).orElseThrow();
    }

    @Override
    public Optional<EmployeeProfile> activateInvitation(
            String tokenSha256,String issuerUri,String providerSubject,Instant activatedAt) {
        record Candidate(UUID invitationId,String subjectId,String status,Instant expiresAt,String createdBy) {}
        Candidate candidate=jdbc.sql("""
                SELECT invitation_id,security_subject_id,state,expires_at,created_by
                FROM platform.identity_invitation
                WHERE token_hash=:tokenHash
                FOR UPDATE
                """).param("tokenHash",tokenSha256)
                .query((row,index)->new Candidate(row.getObject(1,UUID.class),row.getString(2),
                        row.getString(3),row.getTimestamp(4).toInstant(),row.getString(5)))
                .optional().orElse(null);
        if(candidate==null||!candidate.status().equals("PENDING")
                ||!activatedAt.isBefore(candidate.expiresAt()))return Optional.empty();
        long providerBinding=countInBinding(issuerUri,providerSubject,candidate.subjectId());
        if(providerBinding>0)return Optional.empty();
        jdbc.sql("""
                INSERT INTO platform.identity_provider_binding(
                    binding_id,provider_code,issuer_uri,provider_subject,security_subject_id,
                    state,valid_from,approved_by,approved_at)
                VALUES(:id,'ENTERPRISE_OIDC',:issuer,:providerSubject,:subject,
                       'ACTIVE',:activatedAt,:approvedBy,:activatedAt)
                """).param("id",UUID.randomUUID()).param("issuer",issuerUri)
                .param("providerSubject",providerSubject).param("subject",candidate.subjectId())
                .param("approvedBy",candidate.createdBy())
                .param("activatedAt",Timestamp.from(activatedAt)).update();
        int activated=jdbc.sql("""
                UPDATE platform.security_user
                SET account_status='ACTIVE',enabled=true,activated_at=COALESCE(activated_at,:activatedAt),
                    version=version+1,updated_at=:activatedAt
                WHERE subject_id=:subject AND account_status='INVITED'
                  AND employment_status='ACTIVE'
                """).param("activatedAt",Timestamp.from(activatedAt))
                .param("subject",candidate.subjectId()).update();
        if(activated!=1)return Optional.empty();
        jdbc.sql("""
                UPDATE platform.identity_invitation
                SET state='ACTIVATED',activated_at=:activatedAt,version=version+1
                WHERE invitation_id=:id AND state='PENDING'
                """).param("activatedAt",Timestamp.from(activatedAt))
                .param("id",candidate.invitationId()).update();
        return find(candidate.subjectId(),null);
    }

    private long countInBinding(String issuerUri,String providerSubject,String subjectId) {
        return jdbc.sql("""
                SELECT count(*) FROM platform.identity_provider_binding
                WHERE state='ACTIVE' AND valid_until IS NULL
                  AND ((issuer_uri=:issuer AND provider_subject=:providerSubject)
                    OR security_subject_id=:subject)
                """).param("issuer",issuerUri).param("providerSubject",providerSubject)
                .param("subject",subjectId).query(Long.class).single();
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

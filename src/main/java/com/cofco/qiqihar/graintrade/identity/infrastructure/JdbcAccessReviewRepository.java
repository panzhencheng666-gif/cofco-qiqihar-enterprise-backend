package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.AccessReviewCampaign;
import com.cofco.qiqihar.graintrade.identity.application.AccessReviewDecision;
import com.cofco.qiqihar.graintrade.identity.application.AccessReviewRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessReviewRepository implements AccessReviewRepository {
    private final JdbcClient jdbc;

    public JdbcAccessReviewRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override
    public boolean workUnitExists(String code) {
        return jdbc.sql("SELECT count(*) FROM platform.work_unit WHERE code=:code AND active")
                .param("code",code).query(Long.class).single()==1;
    }

    @Override
    public AccessReviewCampaign create(UUID id,String name,String workUnitCode,
            Instant dueAt,String actor,Instant now) {
        jdbc.sql("""
                INSERT INTO platform.access_review_campaign(
                    review_id,name,scope_work_unit_code,status_code,due_at,created_by,created_at)
                VALUES(:id,:name,:unit,'OPEN',:dueAt,:actor,:now)
                """).param("id",id).param("name",name).param("unit",workUnitCode)
                .param("dueAt",dbTime(dueAt)).param("actor",actor).param("now",dbTime(now)).update();
        jdbc.sql("""
                INSERT INTO platform.access_review_item(review_id,subject_id,grant_type,grant_key)
                SELECT :id,security_user.subject_id,entitlement.grant_type,entitlement.grant_key
                FROM platform.security_user security_user
                JOIN LATERAL (
                    SELECT 'ROLE' AS grant_type,assignment.role_code AS grant_key
                    FROM platform.security_user_role assignment
                    WHERE assignment.subject_id=security_user.subject_id
                      AND :now>=assignment.valid_from
                      AND (assignment.valid_until IS NULL OR :now<assignment.valid_until)
                    UNION ALL
                    SELECT 'REGION',assignment.region_code
                    FROM platform.security_user_region_scope assignment
                    WHERE assignment.subject_id=security_user.subject_id
                      AND :now>=assignment.valid_from
                      AND (assignment.valid_until IS NULL OR :now<assignment.valid_until)
                    UNION ALL
                    SELECT 'POSITION',assignment.position_code
                    FROM platform.security_user_position assignment
                    WHERE assignment.subject_id=security_user.subject_id
                      AND :now>=assignment.valid_from
                      AND (assignment.valid_until IS NULL OR :now<assignment.valid_until)
                ) entitlement ON true
                WHERE security_user.work_unit_code=:unit
                  AND security_user.subject_id<>:actor
                ORDER BY security_user.subject_id,entitlement.grant_type,entitlement.grant_key
                """).param("id",id).param("now",dbTime(now)).param("unit",workUnitCode)
                .param("actor",actor).update();
        return find(id).orElseThrow();
    }

    @Override
    public Optional<AccessReviewCampaign> find(UUID reviewId) {
        return jdbc.sql("""
                SELECT review_id,name,scope_work_unit_code,status_code,due_at,created_by,created_at
                FROM platform.access_review_campaign WHERE review_id=:id
                """).param("id",reviewId).query((row,index)->new AccessReviewCampaign(
                        row.getObject("review_id",UUID.class),row.getString("name"),
                        row.getString("scope_work_unit_code"),row.getString("status_code"),
                        instant(row.getObject("due_at",OffsetDateTime.class)),row.getString("created_by"),
                        instant(row.getObject("created_at",OffsetDateTime.class)),items(reviewId))).optional();
    }

    @Override
    public List<AccessReviewCampaign> findByWorkUnit(String workUnitCode) {
        return jdbc.sql("""
                SELECT review_id,name,scope_work_unit_code,status_code,due_at,created_by,created_at
                FROM platform.access_review_campaign
                WHERE scope_work_unit_code=:unit
                ORDER BY created_at DESC,review_id
                """).param("unit",workUnitCode).query((row,index)->{
                    UUID reviewId=row.getObject("review_id",UUID.class);
                    return new AccessReviewCampaign(reviewId,row.getString("name"),
                            row.getString("scope_work_unit_code"),row.getString("status_code"),
                            instant(row.getObject("due_at",OffsetDateTime.class)),row.getString("created_by"),
                            instant(row.getObject("created_at",OffsetDateTime.class)),items(reviewId));
                }).list();
    }

    @Override
    public boolean decide(UUID reviewId,List<AccessReviewDecision> decisions,String actor,Instant now) {
        for(AccessReviewDecision decision:decisions) {
            int updated=jdbc.sql("""
                    UPDATE platform.access_review_item
                    SET decision_code=:decision,decided_by=:actor,decided_at=:now,reason=:reason
                    WHERE review_id=:review AND subject_id=:subject AND grant_type=:type
                      AND grant_key=:key AND decision_code='PENDING'
                    """).param("decision",decision.decisionCode()).param("actor",actor).param("now",dbTime(now))
                    .param("reason",decision.reason()).param("review",reviewId)
                    .param("subject",decision.subjectId()).param("type",decision.grantType())
                    .param("key",decision.grantKey()).update();
            if(updated!=1)return false;
            if("REVOKE".equals(decision.decisionCode()))revoke(decision,now);
            else markReviewed(decision,now);
        }
        jdbc.sql("""
                UPDATE platform.access_review_campaign
                SET status_code='COMPLETED',completed_by=:actor,completed_at=:now
                WHERE review_id=:review AND status_code='OPEN'
                  AND NOT EXISTS(SELECT 1 FROM platform.access_review_item
                      WHERE review_id=:review AND decision_code='PENDING')
                """).param("actor",actor).param("now",dbTime(now)).param("review",reviewId).update();
        return true;
    }

    private List<AccessReviewCampaign.Item> items(UUID reviewId) {
        return jdbc.sql("""
                SELECT subject_id,grant_type,grant_key,decision_code,decided_by,decided_at,reason
                FROM platform.access_review_item WHERE review_id=:id
                ORDER BY subject_id,grant_type,grant_key
                """).param("id",reviewId).query((row,index)->new AccessReviewCampaign.Item(
                        row.getString("subject_id"),row.getString("grant_type"),row.getString("grant_key"),
                        row.getString("decision_code"),row.getString("decided_by"),
                        nullableInstant(row.getObject("decided_at",OffsetDateTime.class)),row.getString("reason"))).list();
    }

    private void revoke(AccessReviewDecision decision,Instant now) {
        String table=switch(decision.grantType()){
            case "ROLE" -> "platform.security_user_role";
            case "REGION" -> "platform.security_user_region_scope";
            case "POSITION" -> "platform.security_user_position";
            default -> throw new IllegalArgumentException("Unsupported grant type");
        };
        String column=switch(decision.grantType()){
            case "ROLE" -> "role_code";
            case "REGION" -> "region_code";
            case "POSITION" -> "position_code";
            default -> throw new IllegalArgumentException("Unsupported grant type");
        };
        jdbc.sql("UPDATE "+table+" SET valid_until=:now WHERE subject_id=:subject AND "+column+"=:key "
                        +"AND :now>=valid_from AND (valid_until IS NULL OR :now<valid_until)")
                .param("now",dbTime(now)).param("subject",decision.subjectId()).param("key",decision.grantKey()).update();
    }

    private void markReviewed(AccessReviewDecision decision,Instant now) {
        if("ROLE".equals(decision.grantType()))jdbc.sql("""
                UPDATE platform.security_user_role
                SET last_reviewed_at=:now,review_due_at=:now+interval '1 year'
                WHERE subject_id=:subject AND role_code=:key
                  AND :now>=valid_from AND (valid_until IS NULL OR :now<valid_until)
                """).param("now",dbTime(now)).param("subject",decision.subjectId()).param("key",decision.grantKey()).update();
        if("REGION".equals(decision.grantType()))jdbc.sql("""
                UPDATE platform.security_user_region_scope
                SET last_reviewed_at=:now,review_due_at=:now+interval '1 year'
                WHERE subject_id=:subject AND region_code=:key
                  AND :now>=valid_from AND (valid_until IS NULL OR :now<valid_until)
                """).param("now",dbTime(now)).param("subject",decision.subjectId()).param("key",decision.grantKey()).update();
    }

    private static Instant instant(OffsetDateTime value){return value.withOffsetSameInstant(ZoneOffset.UTC).toInstant();}
    private static Instant nullableInstant(OffsetDateTime value){return value==null?null:instant(value);}
    private static OffsetDateTime dbTime(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
}

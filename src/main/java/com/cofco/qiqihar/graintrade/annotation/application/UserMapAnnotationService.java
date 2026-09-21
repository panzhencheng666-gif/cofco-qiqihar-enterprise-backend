package com.cofco.qiqihar.graintrade.annotation.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserMapAnnotationService {
    private final JdbcClient jdbc;
    private final SecurityPrincipalRepository principals;
    private final BusinessAuditRecorder audit;

    public UserMapAnnotationService(JdbcClient jdbc,SecurityPrincipalRepository principals,
            BusinessAuditRecorder audit) {
        this.jdbc=jdbc;this.principals=principals;this.audit=audit;
    }

    @Transactional(readOnly = true)
    public Optional<UserMapAnnotationView> current(String subject) {
        return jdbc.sql("""
                SELECT annotation_type,
                  ST_XMin(Box2D(geometry))::numeric AS min_lon,
                  ST_YMin(Box2D(geometry))::numeric AS min_lat,
                  ST_XMax(Box2D(geometry))::numeric AS max_lon,
                  ST_YMax(Box2D(geometry))::numeric AS max_lat,
                  region_code,administrative_level,version,updated_at
                FROM platform.user_map_annotation WHERE subject_id=:subject
                """).param("subject", requiredSubject(subject)).query(this::map).optional();
    }

    @Transactional
    public UserMapAnnotationView save(String subject, UserMapAnnotationCommand command) {
        subject = requiredSubject(subject);
        validate(command);
        jdbc.sql("SELECT pg_advisory_xact_lock(209,hashtext(:subject))")
                .param("subject",subject).query(Object.class).single();
        boolean existed=current(subject).isPresent();
        if (command.regionCode() != null && !jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:code)")
                .param("code",command.regionCode()).query(Boolean.class).single()) {
            throw invalid("ANNOTATION_REGION_INVALID","标注所选行政区不存在");
        }
        boolean point = "POINT".equals(command.type());
        jdbc.sql("""
                INSERT INTO platform.user_map_annotation(
                  subject_id,annotation_type,geometry,region_code,administrative_level)
                VALUES(:subject,:type,
                  CASE WHEN :point THEN ST_SetSRID(ST_MakePoint(:minLon,:minLat),4326)
                       ELSE ST_MakeEnvelope(:minLon,:minLat,:maxLon,:maxLat,4326) END,
                  :region,:level)
                ON CONFLICT(subject_id) DO UPDATE SET
                  annotation_type=EXCLUDED.annotation_type,
                  geometry=EXCLUDED.geometry,
                  region_code=EXCLUDED.region_code,
                  administrative_level=EXCLUDED.administrative_level,
                  version=platform.user_map_annotation.version+1,
                  updated_at=now()
                """).param("subject",subject).param("type",command.type()).param("point",point)
                .param("minLon",command.minLongitude()).param("minLat",command.minLatitude())
                .param("maxLon",point ? command.minLongitude() : command.maxLongitude())
                .param("maxLat",point ? command.minLatitude() : command.maxLatitude())
                .param("region",command.regionCode()).param("level",command.administrativeLevel())
                .update();
        var saved=current(subject).orElseThrow();
        audit(subject,existed?"MAP_ANNOTATION_REPLACED":"MAP_ANNOTATION_CREATED",saved);
        return saved;
    }

    @Transactional
    public boolean delete(String subject) {
        subject=requiredSubject(subject);
        jdbc.sql("SELECT pg_advisory_xact_lock(209,hashtext(:subject))")
                .param("subject",subject).query(Object.class).single();
        var existing=current(subject);
        boolean deleted=jdbc.sql("DELETE FROM platform.user_map_annotation WHERE subject_id=:subject")
                .param("subject",subject).update() == 1;
        if(deleted)audit(subject,"MAP_ANNOTATION_DELETED",existing.orElseThrow());
        return deleted;
    }

    private UserMapAnnotationView map(ResultSet row, int index) throws SQLException {
        return new UserMapAnnotationView(row.getString("annotation_type"),
                row.getBigDecimal("min_lon"),row.getBigDecimal("min_lat"),
                row.getBigDecimal("max_lon"),row.getBigDecimal("max_lat"),
                row.getString("region_code"),row.getString("administrative_level"),
                row.getLong("version"),row.getTimestamp("updated_at").toInstant());
    }

    private static void validate(UserMapAnnotationCommand value) {
        if (value == null || (!"POINT".equals(value.type())&&!"RECTANGLE".equals(value.type())))
            throw invalid("ANNOTATION_TYPE_INVALID","请选择点标注或矩形标注");
        coordinate(value.minLongitude(),-180,180,"经度");
        coordinate(value.minLatitude(),-90,90,"纬度");
        if ("POINT".equals(value.type())) {
            if (value.maxLongitude()!=null || value.maxLatitude()!=null)
                throw invalid("ANNOTATION_POINT_INVALID","点标注只能包含一个经纬度");
        } else {
            coordinate(value.maxLongitude(),-180,180,"经度");
            coordinate(value.maxLatitude(),-90,90,"纬度");
            if (value.minLongitude().compareTo(value.maxLongitude())>=0
                    || value.minLatitude().compareTo(value.maxLatitude())>=0)
                throw invalid("ANNOTATION_RECTANGLE_INVALID","矩形经纬度范围无效");
        }
        if (value.administrativeLevel()!=null
                && !java.util.Set.of("CITY","COUNTY","TOWNSHIP","VILLAGE").contains(value.administrativeLevel()))
            throw invalid("ANNOTATION_LEVEL_INVALID","标注行政层级无效");
    }

    private static void coordinate(BigDecimal value,int minimum,int maximum,String label) {
        if (value==null || value.scale()>8 || value.compareTo(BigDecimal.valueOf(minimum))<0
                || value.compareTo(BigDecimal.valueOf(maximum))>0)
            throw invalid("ANNOTATION_COORDINATE_INVALID",label+"格式无效");
    }
    private static String requiredSubject(String subject) {
        if(subject==null||subject.isBlank())throw invalid("ANNOTATION_SUBJECT_REQUIRED","请先登录");
        return subject;
    }
    private void audit(String subject,String action,UserMapAnnotationView value) {
        var principal=principals.findEnabled(subject).orElseThrow(
                ()->invalid("ANNOTATION_SUBJECT_REQUIRED","账号不可用"));
        String detail="{\"type\":\""+value.type()+"\",\"regionCode\":"
                +(value.regionCode()==null?"null":"\""+value.regionCode()+"\"")+"}";
        audit.record(principal,"USER_MAP_ANNOTATION",subject,action,Instant.now(),detail);
    }
    private static ClientRequestException invalid(String code,String message) {
        return new ClientRequestException(code,message);
    }
}

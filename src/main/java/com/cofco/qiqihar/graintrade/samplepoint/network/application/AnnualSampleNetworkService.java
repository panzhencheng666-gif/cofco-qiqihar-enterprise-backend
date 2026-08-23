package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnualSampleNetworkService {
    private static final String AGGREGATE = "SAMPLE_NETWORK_YEAR";
    private static final String SUBMITTED = "SAMPLE_NETWORK_SUBMITTED";
    private final AnnualSampleNetworkRepository repository;
    private final AccessControl access;
    private final SeparationOfDutiesPolicy duties;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public AnnualSampleNetworkService(
            AnnualSampleNetworkRepository repository,
            AccessControl access,
            SeparationOfDutiesPolicy duties,
            BusinessAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.duties = duties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DesignSamplePointView> designPoints(String regionCode) {
        SecurityPrincipal principal = access.require("BUSINESS_READ", regionCode);
        return repository.designPoints(regionCode, principal.regionCodes());
    }

    @Transactional(readOnly = true)
    public AnnualSampleNetworkView find(int year) {
        validateYear(year);
        SecurityPrincipal principal = access.require("BUSINESS_READ", null);
        return required(year, principal.regionCodes());
    }

    @Transactional(readOnly = true)
    public SampleNetworkComparisonView comparison(int year, String regionCode) {
        validateYear(year);
        SecurityPrincipal principal = access.require("BUSINESS_READ", regionCode);
        return repository.comparison(year, regionCode, principal.regionCodes());
    }

    @Transactional
    public AnnualSampleNetworkView create(int year, Integer carriedFromYear) {
        validateYear(year);
        SecurityPrincipal principal = access.require("BUSINESS_CREATE", null);
        if (repository.exists(year)) {
            throw conflict("SAMPLE_NETWORK_ALREADY_EXISTS", "该年度样本网络已经存在");
        }
        if (carriedFromYear != null) {
            validateYear(carriedFromYear);
            if (carriedFromYear >= year || !repository.isPublished(carriedFromYear)) {
                throw invalid("SAMPLE_NETWORK_CARRY_SOURCE_INVALID", "只能引用较早年度已发布的样本网络");
            }
        }
        Instant now = clock.instant();
        repository.create(year, carriedFromYear, principal.subjectId(), now);
        audit.record(principal, AGGREGATE, Integer.toString(year), "SAMPLE_NETWORK_CREATED", now, "{}");
        return required(year, principal.regionCodes());
    }

    @Transactional
    public AnnualSampleNetworkView decideMembership(
            int year, UUID samplePointId, String designVillageRegionCode,
            String relationType, String evidenceReference, String statusCode,
            String sourceCode, String reason, long version) {
        validateYear(year);
        validateMembership(statusCode, sourceCode, reason, version);
        validateRelation(designVillageRegionCode, relationType, evidenceReference);
        AnnualSampleNetworkRepository.SamplePointLocation location =
                repository.samplePointLocation(samplePointId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "SAMPLE_POINT_NOT_FOUND", "真实样本点不存在或尚未通过主数据审核"));
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", location.regionCode());
        if (designVillageRegionCode != null) {
            access.require("BUSINESS_UPDATE", designVillageRegionCode);
        }
        if ("EXACT_VILLAGE".equals(relationType)
                && (!"VILLAGE".equals(location.regionLevel())
                        || !location.regionCode().equals(designVillageRegionCode))) {
            throw invalid("SAMPLE_NETWORK_RELATION_INVALID", "精确关系必须连接同村的村级真实样本");
        }
        AnnualSampleNetworkView existing = required(year, principal.regionCodes());
        if (!"DRAFT".equals(existing.statusCode()) || !repository.lockDraft(year)) {
            throw conflict("SAMPLE_NETWORK_NOT_EDITABLE", "只有草稿年度网络可以修改样本名单");
        }
        Instant now = clock.instant();
        AnnualSampleNetworkRepository.MembershipWriteResult changed =
                repository.upsertMembership(year, samplePointId, designVillageRegionCode,
                        relationType, normalized(evidenceReference), statusCode, sourceCode,
                        reason.trim(), version, principal.subjectId(), now);
        int expectedRelations = designVillageRegionCode == null ? 0 : 1;
        if (changed.membershipChanges() != 1 || changed.relationChanges() != expectedRelations) {
            throw conflict("SAMPLE_NETWORK_MEMBER_VERSION_CONFLICT", "年度样本成员已被其他操作更新");
        }
        audit.record(principal, AGGREGATE, Integer.toString(year),
                "SAMPLE_NETWORK_MEMBER_DECIDED", now, "{}");
        return required(year, principal.regionCodes());
    }

    @Transactional
    public AnnualSampleNetworkView submit(int year, long version) {
        validateYear(year);
        SecurityPrincipal principal = access.require("BUSINESS_SUBMIT", null);
        Instant now = clock.instant();
        if (repository.submit(year, version, principal.subjectId(), now) != 1) {
            throw conflict("SAMPLE_NETWORK_SUBMIT_CONFLICT", "年度样本网络状态或版本已经变化");
        }
        audit.record(principal, AGGREGATE, Integer.toString(year), SUBMITTED, now, "{}");
        return required(year, principal.regionCodes());
    }

    @Transactional
    public AnnualSampleNetworkView review(
            int year, long version, String decision, String reason) {
        validateYear(year);
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw invalid("SAMPLE_NETWORK_REVIEW_REASON_INVALID", "审核理由不能为空且不得超过500字");
        }
        SecurityPrincipal principal;
        Instant now = clock.instant();
        int changed;
        String action;
        if ("APPROVE".equals(decision)) {
            principal = access.require("BUSINESS_APPROVE", null);
            duties.requireIndependentApprover(AGGREGATE, Integer.toString(year), SUBMITTED, principal);
            changed = repository.approve(year, version, principal.subjectId(), reason.trim(), now);
            action = "SAMPLE_NETWORK_PUBLISHED";
        } else if ("RETURN".equals(decision)) {
            principal = access.require("BUSINESS_RETURN", null);
            duties.requireIndependentReturner(AGGREGATE, Integer.toString(year), SUBMITTED, principal);
            changed = repository.returnToDraft(year, version, principal.subjectId(), reason.trim(), now);
            action = "SAMPLE_NETWORK_RETURNED";
        } else {
            throw invalid("SAMPLE_NETWORK_REVIEW_DECISION_INVALID", "审核决定必须为通过或退回");
        }
        if (changed != 1) {
            throw conflict("SAMPLE_NETWORK_REVIEW_CONFLICT", "年度样本网络状态或版本已经变化");
        }
        audit.record(principal, AGGREGATE, Integer.toString(year), action, now, "{}");
        return required(year, principal.regionCodes());
    }

    private AnnualSampleNetworkView required(int year, Set<String> regions) {
        return repository.find(year, regions).orElseThrow(() -> new ResourceNotFoundException(
                "SAMPLE_NETWORK_NOT_FOUND", "该年度样本网络尚未创建"));
    }

    private static void validateYear(int year) {
        if (year < 2000 || year > 2200) {
            throw invalid("SAMPLE_NETWORK_YEAR_INVALID", "样本网络年度无效");
        }
    }

    private static void validateMembership(
            String statusCode, String sourceCode, String reason, long version) {
        if (statusCode == null || sourceCode == null
                || !Set.of("CANDIDATE", "ACTIVE", "PAUSED", "REMOVED").contains(statusCode)
                || !Set.of("CARRIED_FORWARD", "NEW", "MANUAL").contains(sourceCode)
                || reason == null || reason.isBlank() || reason.length() > 500 || version < 0) {
            throw invalid("SAMPLE_NETWORK_MEMBER_INVALID", "年度样本成员决定不完整或无效");
        }
    }

    private static void validateRelation(
            String designVillageRegionCode, String relationType, String evidenceReference) {
        if (designVillageRegionCode == null) {
            if (relationType != null || evidenceReference != null) {
                throw invalid("SAMPLE_NETWORK_RELATION_INVALID", "未指定设计村时不得填写对照关系");
            }
            return;
        }
        if (designVillageRegionCode.isBlank()
                || relationType == null
                || !Set.of("EXACT_VILLAGE", "EXPLICIT_REPRESENTATION").contains(relationType)
                || ("EXPLICIT_REPRESENTATION".equals(relationType)
                        && (evidenceReference == null || evidenceReference.isBlank()))
                || (evidenceReference != null && evidenceReference.length() > 500)) {
            throw invalid("SAMPLE_NETWORK_RELATION_INVALID", "年度样本对照关系不完整或无效");
        }
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim();
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }

    private static ConflictException conflict(String code, String message) {
        return new ConflictException(code, message);
    }
}

package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.regionalproduction.api.RegionalRailwayCatalogue;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OperationalFacilityService {
    private final OperationalStorageFacilityRepository storage;
    private final RegionalRailwayCatalogue railways;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    public OperationalFacilityService(
            OperationalStorageFacilityRepository storage,
            RegionalRailwayCatalogue railways,
            AccessControl access,
            BusinessAuditRecorder audit,
            ObjectMapper json,
            Clock clock) {
        this.storage = storage;
        this.railways = railways;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperationalFacilityCatalogue find(String regionCode, String productCode, LocalDate asOf) {
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;
        var storageFacilities = storage.find(regionCode, productCode, effectiveAsOf);
        var railwayRegionCodes = storage.railwayRegionCodes(regionCode);
        var railwayResults = railwayRegionCodes.stream()
                .map(railways::findFacilities).filter(value -> value.boundaryAvailable()).toList();
        var categories = List.of(
                category("OWNED", "自有库点", storageFacilities),
                category("LEASED", "租赁库点", storageFacilities),
                category("HISTORICAL_LEASED", "历史租赁库点", storageFacilities));
        var facilityById = new LinkedHashMap<String, OperationalFacilityCatalogue.RailwayFacility>();
        railwayResults.forEach(railway -> railway.facilities().forEach(value -> facilityById.putIfAbsent(
                value.sourceId(), new OperationalFacilityCatalogue.RailwayFacility(
                        value.sourceId(), value.name(), value.kind(), value.longitude(), value.latitude(),
                        value.operator(), value.reference(), value.status(), value.service(), value.locationRelation(),
                        value.distanceKm(), value.nearbyLines(), value.sourceUrl()))));
        var facilities = facilityById.values().stream()
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayFacility::locationRelation)
                        .reversed().thenComparing(OperationalFacilityCatalogue.RailwayFacility::name)
                        .thenComparing(OperationalFacilityCatalogue.RailwayFacility::sourceId))
                .toList();
        var lineByName = new LinkedHashMap<String, OperationalFacilityCatalogue.RailwayLine>();
        railwayResults.forEach(railway -> railway.lines().forEach(value -> lineByName.putIfAbsent(
                value.name(), new OperationalFacilityCatalogue.RailwayLine(
                        value.name(), value.mappedTrackKm(), value.usage(), value.electrification(),
                        value.gauge(), value.operator(), value.sourceUrl()))));
        var lines = lineByName.values().stream()
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayLine::name)).toList();
        var routes = railwayRegionCodes.stream().flatMap(code -> railways.findRoutes(code).stream())
                .map(value -> new OperationalFacilityCatalogue.RailwayRoute(
                        value.id(), value.name(), value.geometryGeoJson(), value.usage(), value.operator(),
                        value.sourceUrl()))
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayRoute::name)
                        .thenComparing(OperationalFacilityCatalogue.RailwayRoute::id))
                .toList();
        String railwaySourceAsOf = railwayResults.stream().map(value -> value.sourceAsOf())
                .filter(value -> value != null && !value.isBlank()).max(String::compareTo).orElse(null);
        String storageAsOf = storage.latestSourceAsOf(regionCode);
        return new OperationalFacilityCatalogue(
                regionCode, productCode, effectiveAsOf, categories, storageFacilities, facilities, lines, routes,
                List.of(
                        new OperationalFacilityCatalogue.SourceStatus(
                                "STORAGE", "关联库点", sourceStatus(storageAsOf, 90), storageAsOf, null,
                                "库点关系和仓容来自登录用户正式填报；价格为空表示尚未核定，系统不会自动生成库点。"),
                        new OperationalFacilityCatalogue.SourceStatus(
                                "RAILWAY", "铁路站点", sourceStatus(railwaySourceAsOf, 30), railwaySourceAsOf,
                                "https://www.openstreetmap.org/copyright",
                                "OpenStreetMap 地理参考不等于铁路货运营业资质，业务能力需另行核验。")));
    }

    @Transactional
    public OperationalFacilityCatalogue.StorageFacility create(OperationalStorageFacilityDraft requested) {
        OperationalStorageFacilityDraft draft = validate(requested, false);
        SecurityPrincipal actor = access.require("BUSINESS_CREATE", draft.regionCode());
        String code = "FAC-" + UUID.randomUUID();
        var saved = storage.create(code, draft, actor.workUnitCode(), actor.subjectId(), clock.instant())
                .orElseThrow(() -> new IllegalStateException("Saved facility cannot be read"));
        audit(actor, saved, "OPERATIONAL_FACILITY_CREATED");
        return saved;
    }

    OperationalStorageFacilityDraft validateForImport(OperationalStorageFacilityDraft requested) {
        return validate(requested, false);
    }

    @Transactional
    public List<OperationalFacilityCatalogue.StorageFacility> importValidated(
            List<OperationalStorageFacilityDraft> requested) {
        if (requested == null || requested.isEmpty()) throw invalid("导入内容不能为空");
        List<OperationalStorageFacilityDraft> drafts = requested.stream()
                .map(value -> validate(value, false)).toList();
        List<SecurityPrincipal> actors = drafts.stream()
                .map(value -> access.require("BUSINESS_CREATE", value.regionCode())).toList();
        List<OperationalFacilityCatalogue.StorageFacility> saved = new java.util.ArrayList<>();
        Instant now = clock.instant();
        for (int index = 0; index < drafts.size(); index++) {
            OperationalStorageFacilityDraft draft = drafts.get(index);
            SecurityPrincipal actor = actors.get(index);
            var facility = storage.create("FAC-" + UUID.randomUUID(), draft,
                            actor.workUnitCode(), actor.subjectId(), now)
                    .orElseThrow(() -> new IllegalStateException("Imported facility cannot be read"));
            audit(actor, facility, "OPERATIONAL_FACILITY_IMPORTED");
            saved.add(facility);
        }
        SecurityPrincipal actor = actors.getFirst();
        try {
            audit.record(actor, "OPERATIONAL_FACILITY_IMPORT", UUID.randomUUID().toString(),
                    "OPERATIONAL_FACILITY_IMPORT_COMPLETED", now,
                    json.writeValueAsString(new ImportEvent(saved.size(), saved.stream()
                            .map(OperationalFacilityCatalogue.StorageFacility::regionCode).distinct().sorted().toList())));
        } catch (Exception exception) {
            throw new IllegalStateException("Facility import event cannot be serialized", exception);
        }
        return List.copyOf(saved);
    }

    @Transactional
    public OperationalFacilityCatalogue.StorageFacility update(
            String facilityCode, OperationalStorageFacilityDraft requested) {
        OperationalStorageFacilityDraft draft = validate(requested, true);
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", draft.regionCode());
        var saved = storage.update(requiredCode(facilityCode), draft, actor.workUnitCode(),
                        actor.subjectId(), clock.instant())
                .orElseThrow(() -> new ConflictException(
                        "OPERATIONAL_FACILITY_VERSION_CONFLICT",
                        "库点已更新、已归档或不属于当前单位，请刷新后重试"));
        audit(actor, saved, "OPERATIONAL_FACILITY_UPDATED");
        return saved;
    }

    @Transactional
    public void archive(String facilityCode, long expectedVersion) {
        if (expectedVersion < 0) throw invalid("库点版本无效");
        SecurityPrincipal actor = access.require("BUSINESS_UPDATE", null);
        String code = requiredCode(facilityCode);
        String regionCode = storage.archive(code, expectedVersion, actor.workUnitCode(), actor.subjectId(), clock.instant())
                .orElseThrow(() -> new ConflictException("OPERATIONAL_FACILITY_VERSION_CONFLICT",
                        "库点已更新、已归档或不属于当前单位，请刷新后重试"));
        audit.record(actor, "OPERATIONAL_FACILITY", code, "OPERATIONAL_FACILITY_ARCHIVED",
                clock.instant(), "{\"regionCode\":\"" + regionCode
                        + "\",\"regionCodes\":[\"" + regionCode + "\"]}");
    }

    private OperationalStorageFacilityDraft validate(
            OperationalStorageFacilityDraft requested, boolean update) {
        if (requested == null) throw invalid("库点信息不能为空");
        String name = text(requested.name(), 2, 200, "库点名称");
        String relation = requested.relationType() == null ? "" : requested.relationType().trim();
        if (!Set.of("OWNED", "LEASED", "HISTORICAL_LEASED").contains(relation))
            throw invalid("库点类型无效");
        String region = text(requested.regionCode(), 6, 12, "所在地区");
        if (!storage.supportedRegion(region)) throw invalid("所在地区不属于四个运营区域");
        String address = text(requested.address(), 3, 1000, "详细地址");
        BigDecimal longitude = requested.longitude();
        BigDecimal latitude = requested.latitude();
        if ((longitude == null) != (latitude == null)) throw invalid("经度和纬度必须同时填写");
        if (longitude != null && (longitude.compareTo(new BigDecimal("-180")) < 0
                || longitude.compareTo(new BigDecimal("180")) > 0
                || latitude.compareTo(new BigDecimal("-90")) < 0
                || latitude.compareTo(new BigDecimal("90")) > 0)) throw invalid("经纬度超出有效范围");
        String status = requested.operationalStatus() == null
                ? "ACTIVE" : requested.operationalStatus().trim();
        if (!Set.of("ACTIVE", "INACTIVE", "UNKNOWN").contains(status)) throw invalid("运营状态无效");
        BigDecimal capacity = requested.capacityTonnes();
        if (capacity != null && (capacity.signum() < 0 || capacity.scale() > 3
                || capacity.precision() > 18)) throw invalid("仓容必须为非负数且最多三位小数");
        if (requested.validFrom() != null && requested.validTo() != null
                && requested.validTo().isBefore(requested.validFrom())) throw invalid("有效期结束日期不能早于开始日期");
        if (update && requested.expectedVersion() < 0) throw invalid("库点版本无效");
        return new OperationalStorageFacilityDraft(name, relation, region, address,
                longitude, latitude, status, capacity, requested.capacityAsOf(),
                requested.validFrom(), requested.validTo(), requested.expectedVersion());
    }

    private void audit(SecurityPrincipal actor, OperationalFacilityCatalogue.StorageFacility facility,
            String action) {
        try {
            audit.record(actor, "OPERATIONAL_FACILITY", facility.code(), action, clock.instant(),
                    json.writeValueAsString(new FacilityEvent(
                            facility.regionCode(), List.of(facility.regionCode()), facility.relationType())));
        } catch (Exception exception) {
            throw new IllegalStateException("Facility event cannot be serialized", exception);
        }
    }

    private static String requiredCode(String value) {
        return text(value, 5, 80, "库点编号");
    }

    private static String text(String value, int min, int max, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max)
            throw invalid(label + "长度无效");
        return normalized;
    }

    private static ClientRequestException invalid(String message) {
        return new ClientRequestException("INVALID_OPERATIONAL_FACILITY", message);
    }

    private record FacilityEvent(String regionCode, List<String> regionCodes, String relationType) {}

    private record ImportEvent(int importedRows, List<String> regionCodes) {}

    private static OperationalFacilityCatalogue.Category category(
            String code, String label, List<OperationalFacilityCatalogue.StorageFacility> facilities) {
        long count = facilities.stream().filter(value -> code.equals(value.relationType())).count();
        return new OperationalFacilityCatalogue.Category(code, label, count);
    }

    private static String sourceStatus(String value, long readyDays) {
        if (value == null || value.isBlank()) return "UNAVAILABLE";
        try {
            Instant instant = value.length() == 10
                    ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : Instant.parse(value);
            return Duration.between(instant, Instant.now()).toDays() <= readyDays ? "READY" : "STALE";
        } catch (RuntimeException exception) {
            return "STALE";
        }
    }
}

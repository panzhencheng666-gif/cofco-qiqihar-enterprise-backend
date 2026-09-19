package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OperationalFacilityCatalogue;
import com.cofco.qiqihar.graintrade.overview.application.OperationalFacilityService;
import com.cofco.qiqihar.graintrade.overview.application.OperationalStorageFacilityDraft;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalFacilityController {
    private static final Set<String> PARAMETERS = Set.of("regionCode", "productCode", "asOf");
    private final OperationalFacilityService service;

    public OperationalFacilityController(OperationalFacilityService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/overview/operational-facilities")
    ApiResponse<OperationalFacilityCatalogue> facilities(
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters query = StrictQueryParameters.parse(
                parameters, PARAMETERS::contains, OperationalFacilityController::invalid);
        String rawAsOf = query.optional("asOf");
        LocalDate asOf;
        try {
            asOf = rawAsOf == null ? null : LocalDate.parse(rawAsOf);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
        return new ApiResponse<>(service.find(
                query.optional("regionCode"), query.optional("productCode"), asOf));
    }

    @PostMapping("/api/v1/overview/operational-facilities")
    ApiResponse<OperationalFacilityCatalogue.StorageFacility> create(
            @RequestBody FacilityRequest request) {
        return new ApiResponse<>(service.create(request.toDraft()));
    }

    @PutMapping("/api/v1/overview/operational-facilities/{facilityCode}")
    ApiResponse<OperationalFacilityCatalogue.StorageFacility> update(
            @PathVariable String facilityCode, @RequestBody FacilityRequest request) {
        return new ApiResponse<>(service.update(facilityCode, request.toDraft()));
    }

    @DeleteMapping("/api/v1/overview/operational-facilities/{facilityCode}")
    ApiResponse<Boolean> archive(@PathVariable String facilityCode,
            @RequestParam long expectedVersion) {
        service.archive(facilityCode, expectedVersion);
        return new ApiResponse<>(true);
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_OPERATIONAL_FACILITY_QUERY", "运营设施查询条件无效");
    }

    record FacilityRequest(
            String name,
            String relationType,
            String regionCode,
            String address,
            BigDecimal longitude,
            BigDecimal latitude,
            String operationalStatus,
            BigDecimal capacityTonnes,
            LocalDate capacityAsOf,
            LocalDate validFrom,
            LocalDate validTo,
            long expectedVersion) {
        OperationalStorageFacilityDraft toDraft() {
            return new OperationalStorageFacilityDraft(name, relationType, regionCode, address,
                    longitude, latitude, operationalStatus, capacityTonnes, capacityAsOf,
                    validFrom, validTo, expectedVersion);
        }
    }
}

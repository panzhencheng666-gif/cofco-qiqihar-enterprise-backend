package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.market.application.MarketObjectDraft;
import com.cofco.qiqihar.graintrade.market.application.MarketObjectRoleDraft;
import com.cofco.qiqihar.graintrade.market.application.MarketObjectService;
import com.cofco.qiqihar.graintrade.market.application.MarketObjectView;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-objects")
public class MarketObjectController {
    private final MarketObjectService service;

    public MarketObjectController(MarketObjectService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<MarketObjectView>> list() {
        return new ApiResponse<>(service.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<MarketObjectView> create(@RequestBody Request request) {
        return new ApiResponse<>(service.create(request.draft()));
    }

    @PutMapping("/{objectId}")
    ApiResponse<MarketObjectView> update(@PathVariable String objectId, @RequestBody Request request) {
        if (request == null || request.version() == null || request.version() < 0 || !uuid(objectId)) throw invalid();
        return new ApiResponse<>(service.update(objectId, request.version(), request.draft()));
    }

    record Request(
            String objectName,
            String objectTypeId,
            String regionCode,
            List<String> productIds,
            List<String> cultivarIds,
            String sourceChannelId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String validityStatus,
            List<RoleRequest> roles,
            Long version) {
        MarketObjectDraft draft() {
            if (this == null) throw invalid();
            BoundedInput.requireAggregateSize("INVALID_MARKET_OBJECT", productIds, cultivarIds, roles);
            BoundedInput.requireText(
                    "INVALID_MARKET_OBJECT", objectName, objectTypeId, regionCode,
                    sourceChannelId, validityStatus);
            if (roles != null && roles.stream().anyMatch(role -> role == null)) throw invalid();
            List<MarketObjectRoleDraft> roleDrafts = roles == null
                    ? List.of()
                    : roles.stream().map(RoleRequest::draft).toList();
            return new MarketObjectDraft(
                    objectName, objectTypeId, regionCode, productIds, cultivarIds,
                    sourceChannelId, effectiveFrom, effectiveTo, validityStatus, roleDrafts);
        }
    }

    record RoleRequest(
            String roleId,
            String label,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String capabilityTemplateVersionId) {
        MarketObjectRoleDraft draft() {
            BoundedInput.requireText(
                    "INVALID_MARKET_OBJECT", roleId, label, capabilityTemplateVersionId);
            return new MarketObjectRoleDraft(
                    roleId, effectiveFrom, effectiveTo, capabilityTemplateVersionId);
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException("INVALID_MARKET_OBJECT", "市场监测对象资料不完整或不适用");
    }

    private static boolean uuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}

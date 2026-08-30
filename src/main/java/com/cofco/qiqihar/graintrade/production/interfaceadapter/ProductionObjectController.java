package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import com.cofco.qiqihar.graintrade.production.application.ProductionObjectDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectRoleDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectService;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectView;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
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
@RequestMapping("/api/v1/production-objects")
public class ProductionObjectController {
    private final ProductionObjectService service;

    public ProductionObjectController(ProductionObjectService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ProductionObjectView>> list() {
        return new ApiResponse<>(service.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ProductionObjectView> create(@RequestBody Request request) {
        if (request == null) throw invalid();
        return new ApiResponse<>(service.create(request.draft()));
    }

    @PutMapping("/{objectId}")
    ApiResponse<ProductionObjectView> update(@PathVariable String objectId, @RequestBody Request request) {
        if (request == null || request.version() == null || request.version() < 0 || !uuid(objectId)) {
            throw invalid();
        }
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
        ProductionObjectDraft draft() {
            BoundedInput.requireAggregateSize("INVALID_PRODUCTION_OBJECT", productIds, cultivarIds, roles);
            BoundedInput.requireText(
                    "INVALID_PRODUCTION_OBJECT", objectName, objectTypeId, regionCode,
                    sourceChannelId, validityStatus);
            if (roles != null && roles.stream().anyMatch(role -> role == null)) throw invalid();
            List<ProductionObjectRoleDraft> roleDrafts = roles == null
                    ? List.of()
                    : roles.stream().map(RoleRequest::draft).toList();
            return new ProductionObjectDraft(
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
        ProductionObjectRoleDraft draft() {
            BoundedInput.requireText(
                    "INVALID_PRODUCTION_OBJECT", roleId, label, capabilityTemplateVersionId);
            return new ProductionObjectRoleDraft(
                    roleId, effectiveFrom, effectiveTo, capabilityTemplateVersionId);
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_PRODUCTION_OBJECT", "产情调查对象资料不完整或不适用");
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

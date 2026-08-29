package com.cofco.qiqihar.graintrade.supply.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import com.cofco.qiqihar.graintrade.supply.application.ManualInputDecisionCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountService;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetCommand;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputSetView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyInputWorkspaceView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyReleaseView;
import com.cofco.qiqihar.graintrade.supply.application.SupplyRunCommand;
import com.cofco.qiqihar.graintrade.supply.application.UpstreamSourceReleaseCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupplyAccountController {
    private static final Set<String> ACCOUNT_QUERY_PARAMETERS =
            Set.of("productCode", "regionCode", "periodCode", "marketingYear", "resultState", "version",
                    "pageNumber", "pageSize");
    private static final Set<String> WORKSPACE_QUERY_PARAMETERS =
            Set.of("productCode", "regionCode", "periodCode", "marketingYear");

    private final SupplyAccountService service;

    public SupplyAccountController(SupplyAccountService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/supply-accounts")
    ApiResponse<PageResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters, ACCOUNT_QUERY_PARAMETERS::contains, SupplyAccountController::invalid);
        String rawVersion = parsed.optional("version");
        Integer version = rawVersion == null ? null : integer(rawVersion);
        return new ApiResponse<>(PageResponse.from(service.list(
                parsed.required("productCode"),
                parsed.required("regionCode"),
                canonicalPeriod(parsed.optional("periodCode"), parsed.optional("marketingYear")),
                parsed.optional("resultState"),
                version,
                parsed.integer("pageNumber", 0),
                parsed.integer("pageSize", 20))));
    }

    @GetMapping("/api/v1/supply-input-workspaces")
    ApiResponse<SupplyInputWorkspaceView> inputWorkspace(
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters, WORKSPACE_QUERY_PARAMETERS::contains, SupplyAccountController::invalid);
        return new ApiResponse<>(service.inputWorkspace(
                parsed.required("productCode"),
                parsed.required("regionCode"),
                canonicalPeriod(parsed.optional("periodCode"), parsed.optional("marketingYear"))));
    }

    @PostMapping("/api/v1/supply-accounts/runs")
    ApiResponse<SupplyAccountView> run(@RequestBody RunRequest request) {
        return new ApiResponse<>(service.run(request.command(
                canonicalPeriod(request.periodCode(), request.marketingYear()))));
    }

    @PostMapping("/api/v1/supply-sources/releases")
    ApiResponse<SupplyReleaseView> release(@RequestBody ReleaseRequest request) {
        return new ApiResponse<>(service.release(request.command(
                canonicalPeriod(request.periodCode(), request.marketingYear()))));
    }

    @PostMapping("/api/v1/supply-inputs/manual-decisions")
    ApiResponse<SupplyReleaseView> manual(@RequestBody ManualRequest request) {
        return new ApiResponse<>(service.approveManual(request.command(
                canonicalPeriod(request.periodCode(), request.marketingYear()))));
    }

    @PostMapping("/api/v1/supply-input-sets")
    ApiResponse<SupplyInputSetView> inputSet(@RequestBody InputSetRequest request) {
        return new ApiResponse<>(service.createInputSet(request.command(
                canonicalPeriod(request.periodCode(), request.marketingYear()))));
    }

    record RunRequest(
            String productCode,
            String regionCode,
            String periodCode,
            String marketingYear,
            String inputSetId,
            String adjustmentProposalValue,
            String adjustmentProposalReason,
            Long expectedDecisionVersion,
            Boolean publish) {
        SupplyRunCommand command(String canonicalPeriodCode) {
            if (expectedDecisionVersion == null || expectedDecisionVersion < 0 || publish == null
                    || adjustmentProposalValue == null) {
                throw invalid();
            }
            BoundedInput.requireText("INVALID_SUPPLY_ACCOUNT_REQUEST", productCode, regionCode, canonicalPeriodCode,
                    inputSetId, adjustmentProposalReason);
            return new SupplyRunCommand(
                    productCode, regionCode, canonicalPeriodCode, inputSetId,
                    PlainDecimal.parse(adjustmentProposalValue, 14, 4, "INVALID_SUPPLY_ACCOUNT_REQUEST"),
                    adjustmentProposalReason,
                    expectedDecisionVersion, publish);
        }
    }

    record ReleaseRequest(
            String sourceDomain,
            String sourceRecordId,
            Long sourceVersion,
            String productCode,
            String regionCode,
            String periodCode,
            String marketingYear,
            String roleCode,
            String sourceFieldCode,
            String qualityState) {
        UpstreamSourceReleaseCommand command(String canonicalPeriodCode) {
            if (sourceVersion == null) throw invalid();
            BoundedInput.requireText("INVALID_SUPPLY_ACCOUNT_REQUEST", sourceDomain, sourceRecordId,
                    productCode, regionCode, canonicalPeriodCode, roleCode, sourceFieldCode, qualityState);
            return new UpstreamSourceReleaseCommand(
                    sourceDomain, sourceRecordId, sourceVersion, productCode, regionCode,
                    canonicalPeriodCode, roleCode, sourceFieldCode, qualityState);
        }
    }

    record ManualRequest(
            String productCode,
            String regionCode,
            String periodCode,
            String marketingYear,
            String roleCode,
            String value,
            String reason,
            Long expectedVersion) {
        ManualInputDecisionCommand command(String canonicalPeriodCode) {
            if (expectedVersion == null || value == null) {
                throw invalid();
            }
            BoundedInput.requireText("INVALID_SUPPLY_ACCOUNT_REQUEST", productCode, regionCode, canonicalPeriodCode,
                    roleCode, reason);
            return new ManualInputDecisionCommand(
                    productCode, regionCode, canonicalPeriodCode, roleCode,
                    PlainDecimal.parse(value, 14, 4, "INVALID_SUPPLY_ACCOUNT_REQUEST"), reason, expectedVersion);
        }
    }

    record InputSetRequest(
            String productCode,
            String regionCode,
            String periodCode,
            String marketingYear,
            String reason,
            Long expectedVersion,
            List<InputSetItemRequest> items) {
        SupplyInputSetCommand command(String canonicalPeriodCode) {
            if (expectedVersion == null) throw invalid();
            BoundedInput.requireAggregateSize("INVALID_SUPPLY_ACCOUNT_REQUEST", items);
            BoundedInput.requireText(
                    "INVALID_SUPPLY_ACCOUNT_REQUEST", productCode, regionCode, canonicalPeriodCode, reason);
            if (items != null) items.forEach(InputSetItemRequest::validate);
            return new SupplyInputSetCommand(
                    productCode, regionCode, canonicalPeriodCode, reason, expectedVersion,
                    items == null ? null : items.stream().map(InputSetItemRequest::item).toList());
        }
    }

    record InputSetItemRequest(String roleCode, String sourceReleaseId) {
        void validate() {
            BoundedInput.requireText("INVALID_SUPPLY_ACCOUNT_REQUEST", roleCode, sourceReleaseId);
        }

        SupplyInputSetCommand.Item item() {
            return new SupplyInputSetCommand.Item(roleCode, sourceReleaseId);
        }
    }

    record PageResponse(
            List<SupplyAccountView> items,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages) {
        static PageResponse from(PagedResult<SupplyAccountView> page) {
            return new PageResponse(page.items(), page.pageNumber(), page.pageSize(),
                    page.totalElements(), page.totalPages());
        }
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private String canonicalPeriod(String periodCode, String marketingYear) {
        boolean hasPeriodCode = periodCode != null && !periodCode.isBlank();
        boolean hasMarketingYear = marketingYear != null && !marketingYear.isBlank();
        if (hasPeriodCode == hasMarketingYear) throw invalid();
        return service.resolvePeriodCode(hasPeriodCode ? periodCode : marketingYear);
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_SUPPLY_ACCOUNT_REQUEST", "Supply account request is invalid");
    }
}

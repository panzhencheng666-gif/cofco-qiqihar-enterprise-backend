package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportDraftPromotionService;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportDraftController {
    private final ImportDraftPromotionService promotion;

    public ImportDraftController(ImportDraftPromotionService promotion) {
        this.promotion = promotion;
    }

    @GetMapping("/api/v1/import-drafts")
    ApiResponse<List<Response>> list(
            @RequestParam(required = false) UUID importJobId,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String productCode,
            @RequestParam(defaultValue = "DRAFT") String stateCode) {
        List<ImportDraft> drafts = importJobId == null
                ? promotion.listOwned(normalized(domainCode), normalized(productCode), normalized(stateCode))
                : promotion.listByJob(importJobId);
        return new ApiResponse<>(drafts.stream().map(Response::from).toList());
    }

    @PostMapping("/api/v1/import-drafts/{id}/submit")
    ApiResponse<Response> submit(@PathVariable UUID id) {
        return new ApiResponse<>(Response.from(promotion.submit(id)));
    }

    @PostMapping("/api/v1/import-drafts/jobs/{id}/submit")
    ApiResponse<ImportDraftPromotionService.BatchSubmission> submitJob(@PathVariable UUID id) {
        return new ApiResponse<>(promotion.submitJob(id));
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Import draft scope is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    record Response(UUID id, String domainCode, String productCode, String sampleName,
            String regionCode, String surveyPeriod, List<String> missingFields,
            int completenessPercent, String stateCode, String canonicalRecordId,
            UUID importJobId, int sourceRowNumber, int version) {
        static Response from(ImportDraft draft) {
            return new Response(draft.id(), draft.domainCode(), draft.productCode(), draft.sampleName(),
                    draft.regionCode(), draft.surveyPeriod(), draft.missingFields(),
                    draft.completenessPercent(), draft.stateCode(), draft.canonicalRecordId(),
                    draft.importJobId(), draft.sourceRowNumber(), draft.version());
        }
    }
}

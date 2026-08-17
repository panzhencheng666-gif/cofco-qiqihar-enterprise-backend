package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import com.cofco.qiqihar.graintrade.importing.application.ImportDraftPromotionService;
import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportDraftController {
    private final ImportDraftPromotionService promotion;

    public ImportDraftController(ImportDraftPromotionService promotion) {
        this.promotion = promotion;
    }

    @PostMapping("/api/v1/import-drafts/{id}/submit")
    ApiResponse<Response> submit(@PathVariable UUID id) {
        return new ApiResponse<>(Response.from(promotion.submit(id)));
    }

    record Response(UUID id, String domainCode, String stateCode,
            String canonicalRecordId, int version) {
        static Response from(ImportDraft draft) {
            return new Response(draft.id(), draft.domainCode(), draft.stateCode(),
                    draft.canonicalRecordId(), draft.version());
        }
    }
}

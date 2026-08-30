package com.cofco.qiqihar.graintrade.designsample.metadata.interfaceadapter;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleMetadataDefinition;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleMetadataService;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleValidationResult;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/design-sample-field-definitions")
public class DesignSampleMetadataController {
    private static final CacheControl METADATA_CACHE = CacheControl.maxAge(Duration.ofMinutes(5))
            .cachePublic()
            .mustRevalidate();

    private final DesignSampleMetadataService service;

    public DesignSampleMetadataController(DesignSampleMetadataService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<DesignSampleMetadataDefinition> definition(
            @RequestParam String domainCode,
            @RequestParam String productCode,
            @RequestParam String objectTypeCode,
            WebRequest request) {
        DesignSampleMetadataDefinition definition = service.definition(
                context(domainCode, productCode, objectTypeCode));
        String responseEtag = responseEtag(definition);
        if (request.checkNotModified(responseEtag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(METADATA_CACHE)
                    .eTag(responseEtag)
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(METADATA_CACHE)
                .eTag(responseEtag)
                .body(definition);
    }

    @PostMapping("/validate")
    ResponseEntity<DesignSampleValidationResult> validate(@RequestBody ValidationRequest request) {
        if (request == null || request.context() == null) {
            throw invalidContext();
        }
        DesignSampleValidationResult result = service.validate(
                request.contractVersion(),
                request.contractDigest(),
                request.context(),
                request.values());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    private static DesignSampleContext context(
            String domainCode,
            String productCode,
            String objectTypeCode) {
        try {
            return new DesignSampleContext(domainCode, productCode, objectTypeCode);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidContext();
        }
    }

    private static ClientRequestException invalidContext() {
        return new ClientRequestException(
                "INVALID_DESIGN_SAMPLE_CONTEXT",
                "设计样本点业务域、产品和对象组合不合法");
    }

    private static String responseEtag(DesignSampleMetadataDefinition definition) {
        DesignSampleContext context = definition.context();
        return String.join(
                ":",
                definition.contractDigest(),
                context.domainCode(),
                context.productCode(),
                context.objectTypeCode());
    }

    record ValidationRequest(
            String contractVersion,
            String contractDigest,
            DesignSampleContext context,
            Map<String, JsonNode> values) {}
}

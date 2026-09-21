package com.cofco.qiqihar.riskintelligence.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk-intelligence/source-facts")
class SourceFactController {
    private final SourceFactIngestionService ingestion;
    private final byte[] ingestionKey;

    SourceFactController(
            SourceFactIngestionService ingestion,
            @Value("${qiqihar.risk.ingestion-key:}") String ingestionKey) {
        this.ingestion = ingestion;
        this.ingestionKey = ingestionKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    ResponseEntity<SourceFactReceipt> ingest(
            @RequestHeader(value = "X-Risk-Ingestion-Key", required = false) String suppliedKey,
            @RequestBody SourceFact sourceFact) {
        if (ingestionKey.length == 0) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        byte[] supplied = suppliedKey == null
                ? new byte[0]
                : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(ingestionKey, supplied)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SourceFactReceipt receipt = ingestion.ingest(sourceFact);
        return ResponseEntity.status(receipt.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(receipt);
    }
}

package com.cofco.qiqihar.graintrade.samplepoint.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeService;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.JobView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.ReviewView;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/sample-point-identities")
public class SampleIdentityMergeController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SampleIdentityMergeService service;

    public SampleIdentityMergeController(SampleIdentityMergeService service) {
        this.service = service;
    }

    @GetMapping("/merge-export")
    ResponseEntity<byte[]> export() {
        var file = service.export();
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Export-Batch-Id", file.batchId().toString())
                .header("X-Export-Row-Count", Integer.toString(file.rowCount()))
                .body(file.bytes());
    }

    @PostMapping("/merge-jobs")
    ResponseEntity<ApiResponse<JobView>> upload(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        JobView job = service.upload(idempotencyKey, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return ResponseEntity.status(201).body(new ApiResponse<>(job));
    }

    @GetMapping("/merge-jobs")
    ApiResponse<List<JobView>> history() {
        return new ApiResponse<>(service.history());
    }

    @GetMapping("/merge-jobs/{jobId}")
    ApiResponse<JobView> status(@PathVariable UUID jobId) {
        return new ApiResponse<>(service.status(jobId));
    }

    @GetMapping("/merge-requests")
    ApiResponse<List<ReviewView>> reviewQueue() {
        return new ApiResponse<>(service.reviewQueue());
    }

    @PostMapping("/merge-requests/{requestId}/review")
    ApiResponse<ReviewView> review(
            @PathVariable UUID requestId, @RequestBody ReviewRequest request) {
        return new ApiResponse<>(service.review(requestId, request.decision(), request.reason()));
    }

    record ReviewRequest(String decision, String reason) {}
}

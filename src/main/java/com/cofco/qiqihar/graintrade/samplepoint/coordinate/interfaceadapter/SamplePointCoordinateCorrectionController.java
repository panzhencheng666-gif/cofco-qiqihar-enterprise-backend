package com.cofco.qiqihar.graintrade.samplepoint.coordinate.interfaceadapter;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.FormalSampleCoordinateChangeCommand;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionService;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.JobView;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.ReviewView;
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
@RequestMapping("/api/v1/sample-point-coordinate-corrections")
public class SamplePointCoordinateCorrectionController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SamplePointCoordinateCorrectionService service;

    public SamplePointCoordinateCorrectionController(
            SamplePointCoordinateCorrectionService service) {
        this.service = service;
    }

    @GetMapping("/export")
    ResponseEntity<byte[]> export() {
        var file = service.export();
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Export-Batch-Id", file.batchId().toString())
                .header("X-Export-Row-Count", Integer.toString(file.rowCount()))
                .body(file.bytes());
    }

    @PostMapping
    ResponseEntity<ApiResponse<JobView>> upload(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        JobView job = service.upload(idempotencyKey, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return ResponseEntity.status(201).body(new ApiResponse<>(job));
    }

    @GetMapping("/history")
    ApiResponse<List<JobView>> history() {
        return new ApiResponse<>(service.history());
    }

    @GetMapping("/jobs/{jobId}")
    ApiResponse<JobView> status(@PathVariable UUID jobId) {
        return new ApiResponse<>(service.status(jobId));
    }

    @GetMapping("/jobs/{jobId}/errors")
    ResponseEntity<byte[]> errors(@PathVariable UUID jobId) {
        var file = service.errors(jobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    @PostMapping("/jobs/{jobId}/retry")
    ApiResponse<JobView> retry(
            @PathVariable UUID jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return new ApiResponse<>(service.retry(jobId, idempotencyKey));
    }

    @GetMapping("/requests")
    ApiResponse<List<ReviewView>> reviewQueue() {
        return new ApiResponse<>(service.reviewQueue());
    }

    @PostMapping("/requests")
    ResponseEntity<ApiResponse<ReviewView>> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody FormalSampleCoordinateChangeCommand request) {
        return ResponseEntity.status(201)
                .body(new ApiResponse<>(service.submit(idempotencyKey, request)));
    }

    @PostMapping("/requests/{requestId}/review")
    ApiResponse<ReviewView> review(
            @PathVariable UUID requestId, @RequestBody ReviewRequest request) {
        return new ApiResponse<>(service.review(
                requestId, request.decision(), request.reason()));
    }

    record ReviewRequest(String decision, String reason) {}
}

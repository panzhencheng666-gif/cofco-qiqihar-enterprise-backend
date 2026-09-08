package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleRetirementBatch;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleRetirementBatchService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import org.springframework.dao.PessimisticLockingFailureException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/formal-sample-points/retirement-previews")
public class FormalSampleRetirementBatchController {
    private final FormalSampleRetirementBatchService service;
    public FormalSampleRetirementBatchController(FormalSampleRetirementBatchService service) {
        this.service = service;
    }
    @PostMapping
    ApiResponse<View> preview() { return new ApiResponse<>(View.from(service.preview())); }
    @GetMapping("/{id}")
    ApiResponse<View> get(@PathVariable UUID id) { return new ApiResponse<>(View.from(service.get(id))); }
    @PutMapping("/{id}/execution")
    ApiResponse<View> execute(@PathVariable UUID id, @RequestBody Request request) {
        // Each invocation enters a new transaction; concurrent duplicate confirmations can then read the receipt.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ApiResponse<>(View.from(service.execute(id, request == null ? null : request.reason())));
            } catch (PessimisticLockingFailureException exception) {
                if (attempt == 2) throw new ConflictException("RETIREMENT_RETRY_REQUIRED",
                        "样本正在并发更新，请使用同一预览重试并查询结果");
            }
        }
        throw new IllegalStateException("Unreachable retirement retry state");
    }
    record Request(String reason) {}
    record View(UUID id, LocalDate businessDate, Instant expiresAt, int candidateCount,
            List<FormalSampleRetirementBatch.Candidate> candidates, String reason, Integer retiredCount) {
        static View from(FormalSampleRetirementBatch batch) {
            return new View(batch.id(), batch.businessDate(), batch.expiresAt(), batch.candidateCount(),
                    batch.candidates(), batch.reason(), batch.retiredCount());
        }
    }
}

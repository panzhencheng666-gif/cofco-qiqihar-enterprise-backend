package com.cofco.qiqihar.graintrade.formalsampleobservation.interfaceadapter;

import com.cofco.qiqihar.graintrade.formalsampleobservation.application.EligibleFormalSample;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationDomain;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationCommand;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationResult;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationHistoryPage;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.FormalSampleObservationService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/formal-sample-observations")
public class FormalSampleObservationController {
    private final FormalSampleObservationService service;

    public FormalSampleObservationController(FormalSampleObservationService service) {
        this.service = service;
    }

    @GetMapping("/eligible-samples")
    ApiResponse<List<EligibleFormalSample>> eligibleSamples(
            @RequestParam FormalSampleObservationDomain domain,
            @RequestParam String productCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String objectTypeCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam OffsetDateTime observedAt) {
        return new ApiResponse<>(service.eligibleSamples(
                domain, productCode, regionCode, objectTypeCode, keyword, year, observedAt));
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<FormalSampleObservationResult> save(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody FormalSampleObservationCommand command) {
        return new ApiResponse<>(service.save(idempotencyKey, command));
    }

    @GetMapping("/observations")
    ApiResponse<FormalSampleObservationHistoryPage> history(
            @RequestParam FormalSampleObservationDomain domain,
            @RequestParam UUID samplePointId,
            @RequestParam String productCode,
            @RequestParam Integer year,
            @RequestParam(required = false) Integer pageNumber,
            @RequestParam(required = false) Integer pageSize) {
        return new ApiResponse<>(service.history(
                domain, samplePointId, productCode, year, pageNumber, pageSize));
    }
}

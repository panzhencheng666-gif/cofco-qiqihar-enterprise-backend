package com.cofco.qiqihar.riskintelligence.operations;

import com.cofco.qiqihar.riskintelligence.configuration.RiskDatabaseBoundary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk-intelligence/operations")
public class BoundaryStatusController {
    private final RiskDatabaseBoundary boundary;

    public BoundaryStatusController(RiskDatabaseBoundary boundary) {
        this.boundary = boundary;
    }

    @GetMapping("/boundary")
    RiskDatabaseBoundary boundary() {
        return boundary;
    }
}

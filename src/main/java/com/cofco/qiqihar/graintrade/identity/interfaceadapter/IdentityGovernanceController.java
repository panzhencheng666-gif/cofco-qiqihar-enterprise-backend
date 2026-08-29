package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import com.cofco.qiqihar.graintrade.identity.application.EmployeeAssignment;
import com.cofco.qiqihar.graintrade.identity.application.EmployeeProfile;
import com.cofco.qiqihar.graintrade.identity.application.IdentityGovernanceService;
import com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationReceipt;
import com.cofco.qiqihar.graintrade.identity.application.AssignmentOptions;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/identity/employees")
public class IdentityGovernanceController {
    private final IdentityGovernanceService service;

    public IdentityGovernanceController(IdentityGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<EmployeeProfile>> employees() {
        return new ApiResponse<>(service.employees());
    }

    @GetMapping("/{subjectId}")
    ApiResponse<EmployeeProfile> employee(@PathVariable String subjectId) {
        return new ApiResponse<>(service.employee(subjectId));
    }

    @GetMapping("/assignment-options")
    ApiResponse<AssignmentOptions> assignmentOptions(@RequestParam String workUnitCode) {
        return new ApiResponse<>(service.assignmentOptions(workUnitCode));
    }

    @PostMapping
    ResponseEntity<ApiResponse<IdentityInvitationReceipt>> invite(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InviteRequest request) {
        if (request == null) throw invalid();
        IdentityInvitationReceipt receipt=service.invite(
                idempotencyKey,request.subjectId(),request.deliveryAddress(),request.assignment());
        return ResponseEntity.status(receipt.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                .body(new ApiResponse<>(receipt));
    }

    @PutMapping("/{subjectId}")
    ApiResponse<EmployeeProfile> update(@PathVariable String subjectId, @RequestBody UpdateRequest request) {
        if (request == null || request.version() == null || request.version() < 0) throw invalid();
        return new ApiResponse<>(service.update(subjectId, request.version(), request.assignment()));
    }

    @PostMapping("/{subjectId}/invitations")
    ResponseEntity<ApiResponse<IdentityInvitationReceipt>> reissue(
            @PathVariable String subjectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReissueRequest request) {
        if(request==null)throw invalid();
        IdentityInvitationReceipt receipt=service.reissueInvitation(
                idempotencyKey,subjectId,request.deliveryAddress());
        return ResponseEntity.status(receipt.replayed()?HttpStatus.OK:HttpStatus.CREATED)
                .body(new ApiResponse<>(receipt));
    }

    record InviteRequest(
            String subjectId,
            String displayName,
            String deliveryAddress,
            String workUnitCode,
            List<String> positionCodes,
            List<String> roleCodes,
            List<String> regionCodes) {
        EmployeeAssignment assignment() {
            return new EmployeeAssignment(displayName, workUnitCode, "INVITED", "ACTIVE",
                    safe(roleCodes), safe(positionCodes), safe(regionCodes));
        }
    }

    record UpdateRequest(
            Long version,
            String displayName,
            String workUnitCode,
            String accountStatus,
            String employmentStatus,
            List<String> positionCodes,
            List<String> roleCodes,
            List<String> regionCodes) {
        EmployeeAssignment assignment() {
            return new EmployeeAssignment(displayName, workUnitCode, accountStatus, employmentStatus,
                    safe(roleCodes), safe(positionCodes), safe(regionCodes));
        }
    }

    record ReissueRequest(String deliveryAddress) {}

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_IDENTITY_ASSIGNMENT", "员工账号或授权信息不完整");
    }
}

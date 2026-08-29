package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IdentityInvitationReceipt(
        String contractVersion,
        String subjectId,
        String displayName,
        String workUnitCode,
        String workUnitName,
        String accountStatus,
        String employmentStatus,
        List<EmployeeProfile.Grant> roles,
        List<EmployeeProfile.Position> positions,
        List<String> regionCodes,
        long version,
        UUID invitationId,
        String invitationStatus,
        String deliveryStatus,
        Instant expiresAt,
        boolean replayed) {
    public static final String CONTRACT_VERSION = IdentityLifecycleContract.VERSION;

    public static IdentityInvitationReceipt from(
            EmployeeProfile employee, IdentityInvitation invitation, boolean replayed) {
        return new IdentityInvitationReceipt(
                CONTRACT_VERSION, employee.subjectId(), employee.displayName(),
                employee.workUnitCode(), employee.workUnitName(), employee.accountStatus(),
                employee.employmentStatus(), employee.roles(), employee.positions(),
                employee.regionCodes(), employee.version(), invitation.invitationId(),
                invitation.invitationStatus(), invitation.deliveryStatus(),
                invitation.expiresAt(), replayed);
    }
}

package com.cofco.qiqihar.graintrade.identity.application;

public record IdentityActivationResult(
        String contractVersion,
        String subjectId,
        String accountStatus,
        String bindingStatus) {
    public static IdentityActivationResult active(String subjectId) {
        return new IdentityActivationResult(
                IdentityInvitationReceipt.CONTRACT_VERSION, subjectId, "ACTIVE", "ACTIVE");
    }
}

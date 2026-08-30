package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;

public final class IdentityLifecycleContract {
    public static final String VERSION = "2026-08-30";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String ERROR_INVITATION_INVALID = "IDENTITY_INVITATION_INVALID";
    public static final String ERROR_INVITATION_NOT_FOUND = "IDENTITY_INVITATION_NOT_FOUND";
    public static final String ERROR_INVITATION_STATE_CONFLICT = "IDENTITY_INVITATION_STATE_CONFLICT";
    public static final String ERROR_IDEMPOTENCY_CONFLICT = "IDENTITY_INVITATION_IDEMPOTENCY_CONFLICT";
    public static final String ERROR_INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String ERROR_INVALID_DELIVERY_ADDRESS = "INVALID_INVITATION_DELIVERY_ADDRESS";
    public static final String ERROR_SUBJECT_NOT_FOUND = "IDENTITY_SUBJECT_NOT_FOUND";
    public static final String AUDIT_INVITED = "SECURITY_USER_INVITED";
    public static final String AUDIT_ACTIVATED = "SECURITY_USER_ACTIVATED";
    public static final String AUDIT_REVOKED = "SECURITY_INVITATION_REVOKED";
    public static final String AUDIT_REINVITED = "SECURITY_USER_REINVITED";

    public static final List<String> INVITATION_STATUSES =
            List.of("PENDING", "ACTIVATED", "REVOKED", "EXPIRED");
    public static final List<String> DELIVERY_RESULTS =
            List.of("QUEUED", "DELIVERED", "FAILED");
    public static final List<String> ERROR_CODES = List.of(
            ERROR_INVITATION_INVALID,
            ERROR_INVITATION_NOT_FOUND,
            ERROR_INVITATION_STATE_CONFLICT,
            ERROR_IDEMPOTENCY_CONFLICT,
            ERROR_INVALID_IDEMPOTENCY_KEY,
            ERROR_INVALID_DELIVERY_ADDRESS,
            ERROR_SUBJECT_NOT_FOUND);
    public static final List<String> AUDIT_EVENTS = List.of(
            AUDIT_INVITED, AUDIT_ACTIVATED, AUDIT_REVOKED, AUDIT_REINVITED);

    private IdentityLifecycleContract() {}
}

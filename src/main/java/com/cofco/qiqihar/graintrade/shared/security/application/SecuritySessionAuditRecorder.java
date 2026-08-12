package com.cofco.qiqihar.graintrade.shared.security.application;

/** Append-only security-session audit seam. Session identifiers must be hashed by the implementation. */
@FunctionalInterface
public interface SecuritySessionAuditRecorder {
    void record(String subjectId,String sessionId,String actionCode,String detailJson);
}

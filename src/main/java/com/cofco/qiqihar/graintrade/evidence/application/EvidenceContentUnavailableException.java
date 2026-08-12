package com.cofco.qiqihar.graintrade.evidence.application;

import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;

public final class EvidenceContentUnavailableException extends ServiceUnavailableException {
    private static final String CODE = "EVIDENCE_CONTENT_UNAVAILABLE";
    private static final String MESSAGE = "Private evidence content is temporarily unavailable";

    public EvidenceContentUnavailableException() {
        super(CODE, MESSAGE);
    }

    public EvidenceContentUnavailableException(Throwable cause) {
        super(CODE, MESSAGE, cause);
    }
}

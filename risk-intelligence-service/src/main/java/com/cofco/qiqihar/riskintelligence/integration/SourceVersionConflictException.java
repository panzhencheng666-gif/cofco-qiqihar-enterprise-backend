package com.cofco.qiqihar.riskintelligence.integration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class SourceVersionConflictException extends RuntimeException {
    SourceVersionConflictException(SourceFactKey key) {
        super("Source version already exists with different content: " + key.displayName());
    }
}

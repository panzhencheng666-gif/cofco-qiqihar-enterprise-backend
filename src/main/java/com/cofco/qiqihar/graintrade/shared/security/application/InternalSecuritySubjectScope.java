package com.cofco.qiqihar.graintrade.shared.security.application;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Restores only a server-owned, database-backed subject while durable background work is executing. */
@Component
public final class InternalSecuritySubjectScope {
    private final ThreadLocal<String> subject = new ThreadLocal<>();

    public Optional<String> subjectId() { return Optional.ofNullable(subject.get()); }

    public <T> T callAs(String subjectId, Supplier<T> work) {
        if (subjectId == null || subjectId.isBlank() || subject.get() != null) {
            throw new IllegalStateException("Internal security subject scope is invalid");
        }
        subject.set(subjectId);
        try { return work.get(); }
        finally { subject.remove(); }
    }
}

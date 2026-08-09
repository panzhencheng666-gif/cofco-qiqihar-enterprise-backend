package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.UUID;

public interface QueuedImportProcessor {
    String domainCode();
    void processQueued(UUID jobId, SecurityPrincipal principal);
}

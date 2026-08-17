package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface ImportDraftRepository {
    ImportDraft insert(ImportDraft draft);
    int bindEvidence(UUID draftId, List<UUID> evidenceIds, Instant now);
    Optional<ImportDraft> findByIdForUpdate(UUID draftId);
    List<UUID> evidenceIds(UUID draftId);
    ImportDraft markPromoted(UUID draftId, int expectedVersion, String canonicalRecordId, Instant now);
}

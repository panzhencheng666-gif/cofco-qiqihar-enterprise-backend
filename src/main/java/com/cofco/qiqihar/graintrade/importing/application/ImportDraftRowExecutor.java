package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportDraftRowExecutor {
    private final ImportDraftRepository repository;

    public ImportDraftRowExecutor(ImportDraftRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreationResult create(ImportDraft draft, List<UUID> evidenceIds) {
        ImportDraft stored = repository.insert(draft);
        List<UUID> requested = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        int bound = repository.bindEvidence(stored.id(), requested, stored.createdAt());
        if (bound == requested.size()) return new CreationResult(stored, null, null);
        return new CreationResult(stored, "IMPORT_PHOTO_ALREADY_USED",
                "部分照片已由其他样本点使用，本行数据已正常导入");
    }

    public record CreationResult(ImportDraft draft, String warningCode, String warningMessage) {}
}

package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies every governed workbook row in one isolated business-write transaction. */
@Service
public class ImportDraftBatchExecutor {
    private final TransactionTemplate transactions;

    public ImportDraftBatchExecutor(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<ImportRowOutcome> execute(Supplier<List<ImportRowOutcome>> work) {
        return transactions.execute(status -> {
            List<ImportRowOutcome> outcomes = List.copyOf(work.get());
            if (outcomes.stream().anyMatch(row -> "ERROR".equals(row.outcomeCode()))) {
                status.setRollbackOnly();
            }
            return outcomes;
        });
    }
}

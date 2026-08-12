package com.cofco.qiqihar.graintrade.workflow.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public interface WorkObligationReportRepository {
    WorkObligationWeeklyReport findWeekly(Query query, Instant now);

    String employeeWorkUnit(String subjectId);

    void persistExport(Export export);

    ExportContent findExport(String exportId);

    record Query(
            LocalDate weekStart,
            String subjectId,
            String workUnitCode,
            String businessDomain,
            String regionCode,
            Set<String> authorizedRegionCodes) {
        public Query {
            authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
        }
    }

    record Export(
            String id,
            Query query,
            String generatedBy,
            Instant generatedAt,
            String filename,
            String contentType,
            String checksum,
            byte[] content) {
        public Export {
            content = content.clone();
        }
    }

    record ExportContent(
            String id,
            String generatedBy,
            String filename,
            String contentType,
            Set<String> authorizedRegionCodes,
            byte[] content) {
        public ExportContent {
            authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
            content = content.clone();
        }
    }
}

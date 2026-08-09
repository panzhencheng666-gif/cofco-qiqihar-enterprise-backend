package com.cofco.qiqihar.graintrade.reporting.application;

import com.cofco.qiqihar.graintrade.reporting.domain.ReportExportContent;
import java.time.Instant;

public interface ReportingRepository {
    ReportParameterOptionsView options();
    ReportPreviewMaterial loadPreviewMaterial(ReportPreviewCommand command);
    ReportPreviewView persistPreview(ReportPreviewPersistence preview);
    ReportPreviewView findPreview(String previewId);
    String findPreviewRegion(String previewId);
    ReportExportView persistExport(ReportExportPersistence export);
    ReportExportContent findExportContent(String exportTaskId);
    String findExportRegion(String exportTaskId);
    ReportPublicationView persistPublication(ReportPublicationPersistence publication);

    record ReportPreviewMaterial(
            ReportDefinitionView definition,
            String productLabel,
            String regionLabel,
            String periodLabel,
            String approvedSummaryJson,
            long approvedRecordCount) {}
    record ReportPreviewPersistence(
            ReportPreviewCommand command, ReportPreviewMaterial material, String actor, Instant now, Instant expiresAt,
            String datasetId, String datasetDigest, String contentJson, String contentDigest) {}
    record ReportExportPersistence(
            String previewId, String formatCode, String actor, Instant now, String filename, String contentType,
            String contentDigest, byte[] content) {}
    record ReportPublicationPersistence(
            String previewId, String exportTaskId, String actor, Instant now, long expectedVersion) {}
}

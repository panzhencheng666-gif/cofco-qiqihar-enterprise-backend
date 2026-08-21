package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoView;
import com.cofco.qiqihar.graintrade.importing.application.GovernedDraftImportService.DraftSource;
import com.cofco.qiqihar.graintrade.importing.application.ImportPhotoTargetReader.Target;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportPhotoSupplementService {
    private final ImportJobRepository jobs;
    private final GovernedDraftImportService governedImports;
    private final BusinessImportPhotoPackage photoPackage;
    private final ImportPhotoTargetReader targets;
    private final EvidencePhotoService evidencePhotos;
    private final AccessControl access;

    public ImportPhotoSupplementService(ImportJobRepository jobs, GovernedDraftImportService governedImports,
            BusinessImportPhotoPackage photoPackage, ImportPhotoTargetReader targets,
            EvidencePhotoService evidencePhotos, AccessControl access) {
        this.jobs = jobs;
        this.governedImports = governedImports;
        this.photoPackage = photoPackage;
        this.targets = targets;
        this.evidencePhotos = evidencePhotos;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Manifest manifest(UUID importJobId) {
        return manifest(context(importJobId));
    }

    @Transactional
    public SupplementResult supplement(
            UUID importJobId, String filename, String mediaType, byte[] bytes) {
        Context context = context(importJobId);
        String normalizedFilename = BusinessImportPhotoPackage.normalizeName(filename);
        ManifestEntry entry = manifest(context).files().stream()
                .filter(candidate -> candidate.filename().equals(normalizedFilename))
                .findFirst().orElseThrow(() -> new ClientRequestException(
                        "IMPORT_PHOTO_NOT_REFERENCED", "该照片未在本次导入文件中引用"));
        if (entry.targetRecords().isEmpty()) {
            return new SupplementResult(normalizedFilename, "DEFERRED_NO_RECORD",
                    entry.referencedRows().size(), 0, entry.failedRows().size(), 0, 0);
        }
        Map<String, Target> targetByRecord = new LinkedHashMap<>();
        context.targets().forEach(target -> targetByRecord.putIfAbsent(target.recordId(), target));
        List<EvidencePhotoService.SupplementTarget> attachmentTargets = entry.targetRecords().stream()
                .map(targetByRecord::get)
                .map(target -> new EvidencePhotoService.SupplementTarget(
                        target.recordId(), target.regionCode()))
                .toList();
        var attached = evidencePhotos.supplementProductionImport(
                importJobId, normalizedFilename, mediaType, bytes, attachmentTargets);
        String statusCode = attached.newAttachments() > 0 ? "ATTACHED" : "ALREADY_ATTACHED";
        return new SupplementResult(normalizedFilename, statusCode, entry.referencedRows().size(),
                attachmentTargets.size(), entry.failedRows().size(),
                attached.newAttachments(), attached.alreadyAttached());
    }

    private Context context(UUID importJobId) {
        var principal = access.require("BUSINESS_IMPORT", null);
        ImportJobRepository.StoredImportJob stored = jobs.findById(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "IMPORT_JOB_NOT_FOUND", "导入任务不存在"));
        if (!"PRODUCTION".equals(stored.job().domainCode())
                || !stored.job().requestedBy().equals(principal.subjectId())
                || !stored.job().workUnitCode().equals(principal.workUnitCode())
                || stored.job().completedAt() == null
                || !governedImports.supports(stored.sourceContent())) {
            throw new ConflictException("IMPORT_PHOTO_SUPPLEMENT_NOT_ALLOWED",
                    "该导入任务不能由当前账号补充照片");
        }
        DraftSource source;
        try {
            source = governedImports.durableSource(stored.sourceContent());
        } catch (IllegalArgumentException exception) {
            throw new ConflictException("IMPORT_PHOTO_SUPPLEMENT_NOT_ALLOWED", "导入任务照片来源无效");
        }
        return new Context(source, targets.productionTargets(importJobId));
    }

    private Manifest manifest(Context context) {
        Map<Integer, Target> targetByRow = new LinkedHashMap<>();
        context.targets().forEach(target -> targetByRow.put(target.rowNumber(), target));
        Map<String, MutableEntry> grouped = new LinkedHashMap<>();
        context.source().rows().forEach(row -> {
            List<String> filenames;
            try {
                filenames = photoPackage.parseNames(row.photoNames());
            } catch (ClientRequestException exception) {
                filenames = List.of();
            }
            for (String filename : filenames) {
                MutableEntry entry = grouped.computeIfAbsent(filename, MutableEntry::new);
                entry.referencedRows.add(row.rowNumber());
                Target target = targetByRow.get(row.rowNumber());
                if (target == null) entry.failedRows.add(row.rowNumber());
                else entry.targets.putIfAbsent(target.recordId(), target);
            }
        });

        Map<String, List<EvidencePhotoView>> attachedByRecord = new LinkedHashMap<>();
        List<ManifestEntry> files = new ArrayList<>();
        int totalTargetAttachments = 0;
        int attachedTargetAttachments = 0;
        for (MutableEntry groupedEntry : grouped.values()) {
            List<String> attachedRecords = groupedEntry.targets.values().stream()
                    .filter(target -> attachedByRecord.computeIfAbsent(target.recordId(),
                                    evidencePhotos::productionPhotos).stream()
                            .anyMatch(photo -> BusinessImportPhotoPackage.normalizeName(
                                    photo.originalFilename()).equals(groupedEntry.filename)))
                    .map(Target::recordId)
                    .toList();
            ManifestEntry entry = new ManifestEntry(groupedEntry.filename,
                    List.copyOf(groupedEntry.referencedRows),
                    List.copyOf(groupedEntry.targets.keySet()),
                    List.copyOf(groupedEntry.failedRows), attachedRecords);
            files.add(entry);
            totalTargetAttachments += entry.targetRecords().size();
            attachedTargetAttachments += entry.attachedRecords().size();
        }
        int eligibleFileCount = (int) files.stream().filter(file -> !file.targetRecords().isEmpty()).count();
        return new Manifest(files.size(), eligibleFileCount, files.size() - eligibleFileCount,
                totalTargetAttachments, attachedTargetAttachments, files);
    }

    public record Manifest(int totalFileCount, int eligibleFileCount, int deferredFileCount,
            int totalTargetAttachments, int attachedTargetAttachments, List<ManifestEntry> files) {
        public Manifest { files = List.copyOf(files); }
    }

    public record ManifestEntry(String filename, List<Integer> referencedRows,
            List<String> targetRecords, List<Integer> failedRows, List<String> attachedRecords) {
        public ManifestEntry {
            referencedRows = List.copyOf(referencedRows);
            targetRecords = List.copyOf(targetRecords);
            failedRows = List.copyOf(failedRows);
            attachedRecords = List.copyOf(attachedRecords);
        }
    }

    public record SupplementResult(String filename, String statusCode, int referencedRows,
            int targetRecords, int failedRows, int newAttachments, int alreadyAttached) {}

    private record Context(DraftSource source, List<Target> targets) {
        private Context { targets = List.copyOf(targets); }
    }

    private static final class MutableEntry {
        private final String filename;
        private final LinkedHashSet<Integer> referencedRows = new LinkedHashSet<>();
        private final LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        private final LinkedHashSet<Integer> failedRows = new LinkedHashSet<>();

        private MutableEntry(String filename) {
            this.filename = filename;
        }
    }
}

package com.cofco.qiqihar.graintrade.evidence.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidencePhotoRepository {
    EvidencePhotoView insert(EvidencePhotoUpload upload);
    Optional<StoredEvidencePhoto> find(UUID id);
    List<EvidencePhotoView> findAttached(String domain, String recordId);
    boolean attach(UUID id, String domain, String recordId, String regionCode, String subjectId);
    void linkToImportJob(UUID jobId, UUID photoId, String originalFilename, String normalizedFilename,
            java.time.OffsetDateTime capturedAt, String latitude, String longitude,
            java.time.OffsetDateTime createdAt);
    Optional<UUID> findImportJobPhoto(UUID jobId, String normalizedFilename);

    record EvidencePhotoUpload(
            UUID id, String filename, String mediaType, byte[] originalBytes, byte[] watermarkedBytes,
            long byteLength, String sha256, String watermarkedSha256, java.time.OffsetDateTime capturedAt, String latitude,
            String longitude, String watermarkText, String uploadedBy, java.time.OffsetDateTime uploadedAt,
            String storageCode, String objectKey) {}

    record StoredEvidencePhoto(
            EvidencePhotoView view, byte[] originalBytes, byte[] watermarkedBytes, String attachedRegionCode,
            String storageCode, String objectKey, String watermarkedSha256) {}
}

package com.cofco.qiqihar.graintrade.evidence.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EvidencePhotoView(
        UUID id,
        String state,
        String originalFilename,
        String mediaType,
        long byteLength,
        String sha256,
        OffsetDateTime capturedAt,
        String latitude,
        String longitude,
        String watermarkText,
        String uploadedBy,
        OffsetDateTime uploadedAt,
        String attachedDomain,
        String attachedRecordId) {}

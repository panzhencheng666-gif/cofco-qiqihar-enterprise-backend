package com.cofco.qiqihar.graintrade.evidence.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidencePhotoService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidencePhotoService.class);
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png");
    private final EvidencePhotoRepository repository;
    private final AccessControl accessControl;
    private final Clock clock;
    private final EvidenceContentStorage contentStorage;
    private final BusinessAuditRecorder audit;

    public EvidencePhotoService(EvidencePhotoRepository repository, AccessControl accessControl, Clock clock,
            EvidenceContentStorage contentStorage, BusinessAuditRecorder audit) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.clock = clock;
        this.contentStorage = contentStorage;
        this.audit = audit;
    }

    @Transactional
    public EvidencePhotoView upload(String filename, String mediaType, byte[] bytes, OffsetDateTime capturedAt,
            String latitude, String longitude, String watermarkText) {
        String subjectId = accessControl.require("BUSINESS_CREATE", null).subjectId();
        validateMetadata(filename, mediaType, bytes, capturedAt, latitude, longitude, watermarkText);
        BufferedImage image = readImage(bytes, mediaType);
        byte[] watermarked = watermark(image, mediaType, watermarkText, capturedAt, latitude, longitude);
        OffsetDateTime uploadedAt = OffsetDateTime.ofInstant(clock.instant(), ZONE);
        UUID id = UUID.randomUUID();
        if (!contentStorage.external()) {
            return repository.insert(new EvidencePhotoRepository.EvidencePhotoUpload(id, filename.trim(),
                    mediaType, bytes.clone(), watermarked, bytes.length, sha256(bytes), sha256(watermarked), capturedAt, latitude,
                    longitude, watermarkText.trim(), subjectId, uploadedAt, "DATABASE", null));
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Private evidence upload requires transaction synchronization");
        }
        String objectKey = contentStorage.key(id);
        contentStorage.put(objectKey, EvidenceContentEnvelope.encode(mediaType, bytes, watermarked));
        registerRollbackCleanup(objectKey);
        return repository.insert(new EvidencePhotoRepository.EvidencePhotoUpload(id, filename.trim(), mediaType,
                null, null, bytes.length, sha256(bytes), sha256(watermarked), capturedAt, latitude, longitude, watermarkText.trim(),
                subjectId, uploadedAt, "EXTERNAL", objectKey));
    }

    @Transactional
    public EvidenceContent content(UUID id) {
        var principal = accessControl.require("BUSINESS_READ", null);
        var stored = repository.find(id).orElseThrow(() -> new ResourceNotFoundException(
                "EVIDENCE_PHOTO_NOT_FOUND", "Evidence photo does not exist"));
        if (stored.view().state().equals("ATTACHED")) {
            accessControl.require("BUSINESS_READ", stored.attachedRegionCode());
        } else if (!stored.view().uploadedBy().equals(principal.subjectId())) {
            throw new AccessDeniedException("EVIDENCE_PHOTO_ACCESS_DENIED", "Evidence photo access is denied");
        }
        EvidenceContent content;
        if ("DATABASE".equals(stored.storageCode())) {
            content = new EvidenceContent(stored.view().mediaType(), stored.watermarkedBytes().clone());
        } else {
            EvidenceContentEnvelope.Content decoded;
            try {
                decoded = EvidenceContentEnvelope.decode(contentStorage.get(stored.objectKey()));
            } catch (IllegalArgumentException exception) {
                throw new EvidenceContentUnavailableException(exception);
            }
            if (!decoded.mediaType().equals(stored.view().mediaType())
                    || !sha256(decoded.original()).equals(stored.view().sha256())
                    || !sha256(decoded.watermarked()).equals(stored.watermarkedSha256())) {
                throw new EvidenceContentUnavailableException();
            }
            content = new EvidenceContent(decoded.mediaType(), decoded.watermarked());
        }
        audit.record(principal, "EVIDENCE_PHOTO", id.toString(), "EVIDENCE_PHOTO_CONTENT_READ",
                clock.instant(), "{}");
        return content;
    }

    @Transactional(readOnly = true, noRollbackFor = {
            ClientRequestException.class, ConflictException.class, ResourceNotFoundException.class
    })
    public List<EvidencePhotoView> validateAvailable(List<UUID> ids, String subjectId) {
        if (ids == null || ids.isEmpty() || ids.size() > 5 || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw invalid();
        }
        return ids.stream().map(id -> {
            var photo = repository.find(id).orElseThrow(() -> new ResourceNotFoundException(
                    "EVIDENCE_PHOTO_NOT_FOUND", "Evidence photo does not exist"));
            if (!photo.view().state().equals("STAGED") || !photo.view().uploadedBy().equals(subjectId)) {
                throw new ConflictException("EVIDENCE_PHOTO_NOT_AVAILABLE", "Evidence photo is not available");
            }
            return photo.view();
        }).toList();
    }

    @Transactional
    public List<EvidencePhotoView> attachToProduction(
            List<UUID> ids, String recordId, String regionCode, String subjectId) {
        validateAvailable(ids, subjectId);
        for (UUID id : ids) {
            if (!repository.attach(id, "PRODUCTION", recordId, regionCode, subjectId)) {
                throw new ConflictException("EVIDENCE_PHOTO_NOT_AVAILABLE", "Evidence photo is not available");
            }
        }
        return repository.findAttached("PRODUCTION", recordId);
    }

    @Transactional(readOnly = true)
    public List<EvidencePhotoView> productionPhotos(String recordId) {
        return repository.findAttached("PRODUCTION", recordId);
    }

    @Transactional
    public List<EvidencePhotoView> attachToMarket(
            List<UUID> ids, String recordId, String regionCode, String subjectId) {
        validateAvailable(ids, subjectId);
        for (UUID id : ids) {
            if (!repository.attach(id, "MARKET", recordId, regionCode, subjectId)) {
                throw new ConflictException("EVIDENCE_PHOTO_NOT_AVAILABLE", "Evidence photo is not available");
            }
        }
        return repository.findAttached("MARKET", recordId);
    }

    @Transactional(readOnly = true)
    public List<EvidencePhotoView> marketPhotos(String recordId) {
        return repository.findAttached("MARKET", recordId);
    }

    private static void validateMetadata(String filename, String mediaType, byte[] bytes, OffsetDateTime capturedAt,
            String latitude, String longitude, String watermarkText) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || mediaType == null
                || !MEDIA_TYPES.contains(mediaType) || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES
                || capturedAt == null || watermarkText == null || watermarkText.isBlank()) throw invalid();
        BoundedInput.requireText("INVALID_EVIDENCE_PHOTO", watermarkText);
        var parsedLatitude = PlainDecimal.parse(latitude, 3, 7, "INVALID_EVIDENCE_PHOTO");
        var parsedLongitude = PlainDecimal.parse(longitude, 3, 7, "INVALID_EVIDENCE_PHOTO");
        if (parsedLatitude.compareTo(new java.math.BigDecimal("-90")) < 0
                || parsedLatitude.compareTo(new java.math.BigDecimal("90")) > 0
                || parsedLongitude.compareTo(new java.math.BigDecimal("-180")) < 0
                || parsedLongitude.compareTo(new java.math.BigDecimal("180")) > 0) throw invalid();
    }

    private static BufferedImage readImage(byte[] bytes, String mediaType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                boolean expected = mediaType.equals("image/png") ? format.equals("png")
                        : format.equals("jpeg") || format.equals("jpg");
                if (!expected || width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw invalid();
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ClientRequestException client) throw client;
            throw invalid();
        }
    }

    private static byte[] watermark(BufferedImage source, String mediaType, String text, OffsetDateTime capturedAt,
            String latitude, String longitude) {
        int type = mediaType.equals("image/jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            int fontSize = Math.max(12, Math.min(32, source.getWidth() / 20));
            int bandHeight = Math.min(source.getHeight(), fontSize * 3);
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.65f));
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, source.getHeight() - bandHeight, source.getWidth(), bandHeight);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString(text, 10, source.getHeight() - fontSize - 8);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 4)));
            graphics.drawString(capturedAt + "  " + latitude + "," + longitude, 10, source.getHeight() - 8);
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(target, mediaType.equals("image/png") ? "png" : "jpg", output)) throw invalid();
            return output.toByteArray();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void registerRollbackCleanup(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    contentStorage.delete(objectKey);
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to compensate rolled-back private evidence content", exception);
                }
            }
        });
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException("INVALID_EVIDENCE_PHOTO", "Evidence photo is invalid");
    }

    public record EvidenceContent(String mediaType, byte[] bytes) {
        public EvidenceContent {
            bytes = bytes.clone();
        }
        public byte[] bytes() { return bytes.clone(); }
    }
}

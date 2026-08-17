package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoRepository;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BusinessImportPhotoPackage {
    private static final int MAX_PHOTOS_PER_ROW = 5;
    private final EvidencePhotoService photos;
    private final EvidencePhotoRepository repository;

    public BusinessImportPhotoPackage(EvidencePhotoService photos, EvidencePhotoRepository repository) {
        this.photos = photos;
        this.repository = repository;
    }

    public StageResult stage(UUID jobId, List<PhotoPart> parts, String watermarkPrefix) {
        if (jobId == null || watermarkPrefix == null || watermarkPrefix.isBlank()) {
            throw warning("INVALID_IMPORT_PHOTO_PACKAGE", "照片包上下文无效");
        }
        List<PhotoPart> supplied = parts == null ? List.of() : List.copyOf(parts);
        Map<String, List<NamedPart>> grouped = new LinkedHashMap<>();
        List<PhotoWarning> warnings = new ArrayList<>();
        for (PhotoPart part : supplied) {
            if (part == null) {
                warnings.add(new PhotoWarning("", "INVALID_IMPORT_PHOTO_NAME", "照片文件名无效，已跳过"));
                continue;
            }
            try {
                String normalized = normalizeName(part.filename());
                grouped.computeIfAbsent(normalized, ignored -> new ArrayList<>())
                        .add(new NamedPart(part, normalized));
            } catch (ClientRequestException exception) {
                warnings.add(new PhotoWarning(safeName(part.filename()), exception.code(), exception.clientMessage()));
            }
        }

        List<UUID> staged = new ArrayList<>();
        for (Map.Entry<String, List<NamedPart>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(part -> warnings.add(new PhotoWarning(
                        safeName(part.part().filename()), "DUPLICATE_IMPORT_PHOTO_NAME",
                        "照片包内存在规范化后重名文件，均已跳过")));
                continue;
            }
            NamedPart named = entry.getValue().getFirst();
            try {
                staged.add(photos.uploadForImport(jobId, named.part().filename(), named.normalizedName(),
                        named.part().mediaType(), named.part().bytes(), watermarkPrefix).id());
            } catch (ClientRequestException exception) {
                warnings.add(new PhotoWarning(safeName(named.part().filename()),
                        exception.code(), exception.clientMessage()));
            }
        }
        return new StageResult(staged, warnings);
    }

    public List<String> parseNames(String cellValue) {
        if (cellValue == null || cellValue.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (String token : cellValue.split("[;；]", -1)) {
            if (!token.isBlank()) names.add(normalizeName(token));
        }
        if (names.size() > MAX_PHOTOS_PER_ROW) {
            throw warning("IMPORT_PHOTO_LIMIT_EXCEEDED", "每个样本点最多可引用 5 张照片");
        }
        if (new LinkedHashSet<>(names).size() != names.size()) {
            throw warning("DUPLICATE_IMPORT_PHOTO_NAME", "同一行不能重复引用同名照片");
        }
        return List.copyOf(names);
    }

    public List<UUID> resolve(UUID jobId, String cellValue) {
        if (jobId == null) throw warning("INVALID_IMPORT_PHOTO_PACKAGE", "照片包上下文无效");
        return parseNames(cellValue).stream().map(name -> repository.findImportJobPhoto(jobId, name)
                .orElseThrow(() -> warning("IMPORT_PHOTO_NOT_FOUND", "未选择或无法使用照片：" + name)))
                .toList();
    }

    static String normalizeName(String value) {
        if (value == null) throw warning("INVALID_IMPORT_PHOTO_NAME", "照片文件名无效，已跳过");
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.length() > 255 || normalized.equals(".") || normalized.equals("..")
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw warning("INVALID_IMPORT_PHOTO_NAME", "照片文件名无效，已跳过");
        }
        return normalized;
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String cleaned = name.codePoints().filter(code -> !Character.isISOControl(code))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return cleaned.length() <= 255 ? cleaned : cleaned.substring(0, 255);
    }

    private static ClientRequestException warning(String code, String message) {
        return new ClientRequestException(code, message);
    }

    public record PhotoPart(String filename, String mediaType, byte[] bytes) {
        public PhotoPart {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    public record PhotoWarning(String filename, String code, String message) {}

    public record StageResult(List<UUID> stagedPhotoIds, List<PhotoWarning> warnings) {
        public StageResult {
            stagedPhotoIds = List.copyOf(stagedPhotoIds);
            warnings = List.copyOf(warnings);
        }
    }

    private record NamedPart(PhotoPart part, String normalizedName) {}
}

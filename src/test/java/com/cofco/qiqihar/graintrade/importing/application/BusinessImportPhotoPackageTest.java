package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoRepository;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoView;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessImportPhotoPackageTest {
    private final EvidencePhotoService photos = mock(EvidencePhotoService.class);
    private final EvidencePhotoRepository repository = mock(EvidencePhotoRepository.class);
    private final BusinessImportPhotoPackage photoPackage = new BusinessImportPhotoPackage(photos, repository);

    @Test
    void parsesBothSemicolonsAndMatchesUnicodeNamesInNfcForm() {
        assertThat(photoPackage.parseNames("地块一.jpg；地块二.png; e\u0301vidence.jpg"))
                .containsExactly("地块一.jpg", "地块二.png", "évidence.jpg");
    }

    @Test
    void emptyCellMeansNoPhotoWhileUnsafeDuplicateAndTooManyNamesBecomeControlledWarnings() {
        assertThat(photoPackage.parseNames("  ")).isEmpty();
        assertWarning("../secret.jpg", "INVALID_IMPORT_PHOTO_NAME");
        assertWarning("目录\\secret.jpg", "INVALID_IMPORT_PHOTO_NAME");
        assertWarning("a.jpg；a.jpg", "DUPLICATE_IMPORT_PHOTO_NAME");
        assertWarning("1.jpg;2.jpg;3.jpg;4.jpg;5.jpg;6.jpg", "IMPORT_PHOTO_LIMIT_EXCEEDED");
    }

    @Test
    void stagesValidPhotosAndSkipsInvalidOnesWithoutFailingThePackage() {
        UUID jobId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        byte[] validBytes = "valid-image-placeholder".getBytes(StandardCharsets.UTF_8);
        byte[] corruptBytes = "corrupt".getBytes(StandardCharsets.UTF_8);
        when(photos.uploadForImport(eq(jobId), eq("现场一.png"), eq("现场一.png"), eq("image/png"),
                eq(validBytes), eq("产情 | 样本点一"))).thenReturn(view(photoId, "现场一.png"));
        when(photos.uploadForImport(eq(jobId), eq("损坏.png"), eq("损坏.png"), eq("image/png"),
                eq(corruptBytes), eq("产情 | 样本点一")))
                .thenThrow(new ClientRequestException("INVALID_EVIDENCE_PHOTO", "Evidence photo is invalid"));

        var staged = photoPackage.stage(jobId, List.of(
                new BusinessImportPhotoPackage.PhotoPart("现场一.png", "image/png", validBytes),
                new BusinessImportPhotoPackage.PhotoPart("损坏.png", "image/png", corruptBytes)),
                "产情 | 样本点一");

        assertThat(staged.stagedPhotoIds()).containsExactly(photoId);
        assertThat(staged.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.filename()).isEqualTo("损坏.png");
            assertThat(warning.code()).isEqualTo("INVALID_EVIDENCE_PHOTO");
        });
    }

    @Test
    void duplicateUploadedNamesAreAllSkippedSoResolutionCannotChooseTheWrongFile() {
        UUID jobId = UUID.randomUUID();
        var staged = photoPackage.stage(jobId, List.of(
                new BusinessImportPhotoPackage.PhotoPart("e\u0301vidence.jpg", "image/jpeg", new byte[] {1}),
                new BusinessImportPhotoPackage.PhotoPart("évidence.jpg", "image/jpeg", new byte[] {2})), "市场");

        assertThat(staged.stagedPhotoIds()).isEmpty();
        assertThat(staged.warnings()).hasSize(2).allSatisfy(warning ->
                assertThat(warning.code()).isEqualTo("DUPLICATE_IMPORT_PHOTO_NAME"));
        verify(photos, never()).uploadForImport(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void resolvesOnlyExactJobScopedNormalizedFilenames() {
        UUID jobId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findImportJobPhoto(jobId, "地块一.jpg")).thenReturn(Optional.of(first));
        when(repository.findImportJobPhoto(jobId, "évidence.png")).thenReturn(Optional.of(second));

        assertThat(photoPackage.resolve(jobId, "地块一.jpg；e\u0301vidence.png"))
                .containsExactly(first, second);
        verify(repository).findImportJobPhoto(jobId, "地块一.jpg");
        verify(repository).findImportJobPhoto(jobId, "évidence.png");
    }

    @Test
    void missingPhotoIsAControlledRowWarningNotAnInfrastructureFailure() {
        UUID jobId = UUID.randomUUID();
        when(repository.findImportJobPhoto(jobId, "未选择.jpg")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> photoPackage.resolve(jobId, "未选择.jpg"))
                .isInstanceOfSatisfying(ClientRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("IMPORT_PHOTO_NOT_FOUND"));
    }

    private void assertWarning(String cell, String code) {
        assertThatThrownBy(() -> photoPackage.parseNames(cell))
                .isInstanceOfSatisfying(ClientRequestException.class, error ->
                        assertThat(error.code()).isEqualTo(code));
    }

    private static EvidencePhotoView view(UUID id, String filename) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-17T14:00:00+08:00");
        return new EvidencePhotoView(id, "STAGED", filename, "image/png", 10, "a".repeat(64),
                null, null, null, "产情 | 样本点一 | 定位待补充", "production-tester", now, null, null);
    }
}

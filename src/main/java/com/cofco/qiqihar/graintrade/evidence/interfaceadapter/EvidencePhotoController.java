package com.cofco.qiqihar.graintrade.evidence.interfaceadapter;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoService;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoView;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/evidence-photos")
public class EvidencePhotoController {
    private final EvidencePhotoService service;

    public EvidencePhotoController(EvidencePhotoService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<EvidencePhotoView> upload(@RequestParam("file") MultipartFile file,
            @RequestParam OffsetDateTime capturedAt, @RequestParam String latitude,
            @RequestParam String longitude, @RequestParam String watermarkText) throws IOException {
        return new ApiResponse<>(service.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                capturedAt, latitude, longitude, watermarkText));
    }

    @GetMapping("/{id}/content")
    ResponseEntity<byte[]> content(@PathVariable UUID id) {
        var content = service.content(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType())).body(content.bytes());
    }
}

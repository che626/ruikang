package com.ruikang.qc.controller.inference;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.inference.BrowserInferenceManifestResponse;
import com.ruikang.qc.service.BrowserInferenceModelService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/inference")
public class BrowserInferenceController {

    private final BrowserInferenceModelService browserInferenceModelService;

    public BrowserInferenceController(BrowserInferenceModelService browserInferenceModelService) {
        this.browserInferenceModelService = browserInferenceModelService;
    }

    @GetMapping("/manifest")
    public ApiResponse<BrowserInferenceManifestResponse> manifest() {
        return ApiResponse.success(browserInferenceModelService.manifest());
    }

    @GetMapping("/model")
    public ResponseEntity<org.springframework.core.io.Resource> model(@RequestParam String sha256) {
        BrowserInferenceModelService.ModelResource model = browserInferenceModelService.modelResource(sha256);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(model.fileSize())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
                .eTag('"' + model.sha256() + '"')
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(model.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(model.resource());
    }
}

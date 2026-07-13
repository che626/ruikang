package com.ruikang.qc.controller.system;

import com.ruikang.qc.service.StoragePathService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final StoragePathService storagePathService;

    public FileController(StoragePathService storagePathService) {
        this.storagePathService = storagePathService;
    }

    @GetMapping("/content")
    public ResponseEntity<Resource> content(@RequestParam String relativePath) {
        Path path = storagePathService.resolveRelativePath(relativePath);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(path);
        String mediaType = detectMediaType(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .body(resource);
    }

    private String detectMediaType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            return detected != null ? detected : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        } catch (Exception exception) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}

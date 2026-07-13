package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class StoragePathService {

    private final Path storageRoot;

    public StoragePathService(QcProperties qcProperties) {
        this.storageRoot = Paths.get(qcProperties.getStorage().getRootPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化文件存储根目录: " + storageRoot, exception);
        }
    }

    public String toRelativeDatabasePath(Path absolutePath) {
        Path normalizedAbsolute = absolutePath.toAbsolutePath().normalize();
        if (!normalizedAbsolute.startsWith(storageRoot)) {
            throw new IllegalArgumentException("文件路径不在配置的存储根目录下");
        }
        return storageRoot.relativize(normalizedAbsolute).toString().replace('\\', '/');
    }

    public Path resolveRelativePath(String relativePath) {
        return storageRoot.resolve(relativePath).normalize();
    }

    public Path resolveAndEnsureParent(String relativePath) {
        Path path = resolveRelativePath(relativePath);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建文件目录: " + path.getParent(), exception);
        }
        return path;
    }

    public String exampleRelativePath() {
        return "realtime/2026/06/04/evidence-001-result.png";
    }
}

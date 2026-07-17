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

    public Path resolveRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件相对路径不能为空");
        }
        Path requestedPath = Paths.get(relativePath);
        if (requestedPath.isAbsolute()) {
            throw new IllegalArgumentException("文件路径必须是相对路径");
        }
        Path resolvedPath = storageRoot.resolve(requestedPath).normalize();
        if (resolvedPath.equals(storageRoot) || !resolvedPath.startsWith(storageRoot)) {
            throw new IllegalArgumentException("文件路径不在配置的存储根目录下");
        }
        return resolvedPath;
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
}

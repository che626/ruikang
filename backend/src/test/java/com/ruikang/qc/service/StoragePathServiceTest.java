package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoragePathServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void resolvesFilesInsideStorageRoot() {
        StoragePathService service = createService();

        Path resolved = service.resolveRelativePath("image/2026/result.jpg");

        assertEquals(storageRoot.resolve("image/2026/result.jpg").normalize(), resolved);
    }

    @Test
    void rejectsPathsOutsideStorageRoot() {
        StoragePathService service = createService();

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveRelativePath("../outside.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveRelativePath(storageRoot.resolve("absolute.txt").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveRelativePath(" "));
    }

    private StoragePathService createService() {
        QcProperties properties = new QcProperties();
        properties.getStorage().setRootPath(storageRoot.toString());
        return new StoragePathService(properties);
    }
}

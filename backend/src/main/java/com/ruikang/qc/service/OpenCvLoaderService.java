package com.ruikang.qc.service;

import nu.pattern.OpenCV;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OpenCvLoaderService {

    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public void ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            OpenCV.loadLocally();
        }
    }
}

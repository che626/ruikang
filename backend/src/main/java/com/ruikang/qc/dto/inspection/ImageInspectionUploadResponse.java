package com.ruikang.qc.dto.inspection;

import java.util.List;

public record ImageInspectionUploadResponse(
        String taskNo,
        String taskStatus,
        int fileCount,
        List<ImageInspectionRecordResponse> records
) {
}

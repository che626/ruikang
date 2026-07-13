package com.ruikang.qc.dto.inspection;

import java.util.List;

public record ImageInspectionTaskResponse(
        String taskNo,
        String taskType,
        String taskStatus,
        int fileCount,
        List<String> acceptedFiles,
        String message
) {
}

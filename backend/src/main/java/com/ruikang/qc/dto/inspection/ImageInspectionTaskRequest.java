package com.ruikang.qc.dto.inspection;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ImageInspectionTaskRequest(
        @NotEmpty(message = "至少需要一个文件名") List<String> fileNames,
        String remark
) {
}

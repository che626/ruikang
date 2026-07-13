package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.aigc.GenerateQualityReportRequest;
import com.ruikang.qc.dto.aigc.QualityReportListItemResponse;
import com.ruikang.qc.dto.aigc.QualityReportResponse;
import com.ruikang.qc.service.AigcQualityReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/aigc/quality-reports")
public class AigcQualityReportController {

    private final AigcQualityReportService aigcQualityReportService;

    public AigcQualityReportController(AigcQualityReportService aigcQualityReportService) {
        this.aigcQualityReportService = aigcQualityReportService;
    }

    @GetMapping
    public ApiResponse<List<QualityReportListItemResponse>> listReports() {
        return ApiResponse.success(aigcQualityReportService.listReports());
    }

    @DeleteMapping
    public ApiResponse<Void> clearReports() {
        aigcQualityReportService.clearReports();
        return ApiResponse.success("AIGC历史报告已清空", null);
    }

    @GetMapping("/{reportNo}")
    public ApiResponse<QualityReportResponse> detail(@PathVariable String reportNo) {
        return ApiResponse.success(aigcQualityReportService.getReport(reportNo));
    }

    @PostMapping("/generate")
    public ApiResponse<QualityReportResponse> generate(@Valid @RequestBody GenerateQualityReportRequest request) {
        return ApiResponse.success("AIGC质检分析报告已生成", aigcQualityReportService.generateReport(request.recordNo()));
    }

    @PostMapping("/generate-overall")
    public ApiResponse<QualityReportResponse> generateOverall() {
        return ApiResponse.success("AIGC整体质检分析报告已生成", aigcQualityReportService.generateOverallReport());
    }
}

package com.ruikang.qc.controller.inspection;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.exceptioncase.ExceptionDetailResponse;
import com.ruikang.qc.dto.exceptioncase.ExceptionListItemResponse;
import com.ruikang.qc.dto.exceptioncase.HandleExceptionRequest;
import com.ruikang.qc.service.ExceptionCaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {

    private final ExceptionCaseService exceptionCaseService;

    public ExceptionController(ExceptionCaseService exceptionCaseService) {
        this.exceptionCaseService = exceptionCaseService;
    }

    @GetMapping
    public ApiResponse<List<ExceptionListItemResponse>> list() {
        return ApiResponse.success(exceptionCaseService.listExceptions());
    }

    @GetMapping("/{exceptionNo}")
    public ApiResponse<ExceptionDetailResponse> detail(@PathVariable String exceptionNo) {
        return ApiResponse.success(exceptionCaseService.getExceptionDetail(exceptionNo));
    }

    @PostMapping("/{exceptionNo}/handle")
    public ApiResponse<ExceptionDetailResponse> handle(@PathVariable String exceptionNo, @Valid @RequestBody HandleExceptionRequest request) {
        return ApiResponse.success("异常处理结果已更新", exceptionCaseService.handleException(exceptionNo, request));
    }
}

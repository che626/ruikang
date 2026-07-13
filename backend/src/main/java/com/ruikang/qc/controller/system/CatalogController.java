package com.ruikang.qc.controller.system;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.catalog.CameraItemResponse;
import com.ruikang.qc.dto.catalog.ConfigItemResponse;
import com.ruikang.qc.dto.catalog.DefectTypeItemResponse;
import com.ruikang.qc.dto.catalog.ModelItemResponse;
import com.ruikang.qc.dto.catalog.UpdateConfigRequest;
import com.ruikang.qc.dto.catalog.UpdateDefectColorRequest;
import com.ruikang.qc.dto.catalog.UpdateModelActiveRequest;
import com.ruikang.qc.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/defect-types")
    public ApiResponse<List<DefectTypeItemResponse>> listDefectTypes() {
        return ApiResponse.success(catalogService.listDefectTypes());
    }

    @GetMapping("/models")
    public ApiResponse<List<ModelItemResponse>> listModels() {
        return ApiResponse.success(catalogService.listModels());
    }

    @GetMapping("/cameras")
    public ApiResponse<List<CameraItemResponse>> listCameras() {
        return ApiResponse.success(catalogService.listCameras());
    }

    @GetMapping("/configs")
    public ApiResponse<List<ConfigItemResponse>> listConfigs() {
        return ApiResponse.success(catalogService.listConfigs());
    }

    @PutMapping("/defect-types/{defectCode}/color")
    public ApiResponse<List<DefectTypeItemResponse>> updateDefectColor(
            @PathVariable String defectCode,
            @Valid @RequestBody UpdateDefectColorRequest request
    ) {
        catalogService.updateDefectTypeColor(defectCode, request.displayColor());
        return ApiResponse.success("缺陷颜色已更新", catalogService.listDefectTypes());
    }

    @PutMapping("/models/{modelId}/active")
    public ApiResponse<List<ModelItemResponse>> updateModelActive(
            @PathVariable Long modelId,
            @Valid @RequestBody UpdateModelActiveRequest request
    ) {
        catalogService.setModelActive(modelId, request.active());
        return ApiResponse.success(request.active() ? "模型已启用" : "模型已停用", catalogService.listModels());
    }

    @PutMapping("/cameras/{cameraCode}/active")
    public ApiResponse<List<CameraItemResponse>> updateCameraActive(@PathVariable String cameraCode) {
        catalogService.activateCamera(cameraCode);
        return ApiResponse.success("摄像头配置已启用", catalogService.listCameras());
    }

    @PutMapping("/configs/{configCode}")
    public ApiResponse<List<ConfigItemResponse>> updateConfig(@PathVariable String configCode, @Valid @RequestBody UpdateConfigRequest request) {
        catalogService.updateConfig(configCode, request.configValue());
        return ApiResponse.success("参数已更新", catalogService.listConfigs());
    }
}

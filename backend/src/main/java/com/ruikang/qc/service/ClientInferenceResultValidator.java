package com.ruikang.qc.service;

import com.ruikang.qc.dto.inference.ClientDetectionRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ClientInferenceResultValidator {

    private final BrowserInferenceModelService browserInferenceModelService;
    private final CatalogService catalogService;

    public ClientInferenceResultValidator(
            BrowserInferenceModelService browserInferenceModelService,
            CatalogService catalogService
    ) {
        this.browserInferenceModelService = browserInferenceModelService;
        this.catalogService = catalogService;
    }

    public void validateModelHash(String modelSha256) {
        browserInferenceModelService.assertCurrentHash(modelSha256);
    }

    public List<ValidatedDetection> validateDetections(
            List<ClientDetectionRequest> detections,
            int imageWidth,
            int imageHeight
    ) {
        Map<String, CatalogService.DefectDictionaryItem> dictionary = catalogService.getDefectDictionaryMap();
        return detections.stream()
                .map(item -> validateDetection(item, imageWidth, imageHeight, dictionary))
                .toList();
    }

    private ValidatedDetection validateDetection(
            ClientDetectionRequest item,
            int imageWidth,
            int imageHeight,
            Map<String, CatalogService.DefectDictionaryItem> dictionary
    ) {
        CatalogService.DefectDictionaryItem defectType = dictionary.get(item.defectCode());
        if (defectType == null) {
            throw new IllegalArgumentException("未知缺陷类别: " + item.defectCode());
        }
        if (item.x2() <= item.x1() || item.y2() <= item.y1()) {
            throw new IllegalArgumentException("检测框坐标无效");
        }
        if (item.x2() > imageWidth || item.y2() > imageHeight) {
            throw new IllegalArgumentException("检测框超出图像范围");
        }
        int width = item.x2() - item.x1();
        int height = item.y2() - item.y1();
        return new ValidatedDetection(
                defectType.id(),
                defectType.defectCode(),
                defectType.defectName(),
                defectType.displayColor(),
                item.confidence(),
                item.x1(),
                item.y1(),
                item.x2(),
                item.y2(),
                width,
                height,
                width * height
        );
    }

    public record ValidatedDetection(
            Long defectTypeId,
            String defectCode,
            String defectName,
            String displayColor,
            double confidence,
            int x1,
            int y1,
            int x2,
            int y2,
            int width,
            int height,
            int area
    ) {
    }
}

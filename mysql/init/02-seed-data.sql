SET NAMES utf8mb4;
USE ruikang_fabric_qc;

UPDATE ai_model SET is_active = 0;

INSERT INTO ai_model (
    model_name, model_version, model_type, model_path, input_size,
    conf_threshold, iou_threshold, prefer_gpu, fallback_cpu, is_active, remark
)
VALUES (
    'ruikang.onnx', 'v1', 'YOLO', 'models/ruikang.onnx', '640x640',
    0.4500, 0.4200, 1, 1, 1, '面料瑕疵检测模型'
)
ON DUPLICATE KEY UPDATE
    model_name = VALUES(model_name),
    model_version = VALUES(model_version),
    model_type = VALUES(model_type),
    input_size = VALUES(input_size),
    conf_threshold = VALUES(conf_threshold),
    iou_threshold = VALUES(iou_threshold),
    prefer_gpu = VALUES(prefer_gpu),
    fallback_cpu = VALUES(fallback_cpu),
    is_active = VALUES(is_active),
    remark = VALUES(remark);

INSERT INTO qc_camera (camera_code, camera_name, camera_type, status, location_desc, remark)
VALUES ('WEB_CAM_001', 'Webcam 0', 'local_webcam', 1, '本机摄像头', '本地默认摄像头')
ON DUPLICATE KEY UPDATE
    camera_name = VALUES(camera_name),
    status = VALUES(status),
    location_desc = VALUES(location_desc),
    remark = VALUES(remark);

INSERT INTO qc_config (config_code, config_name, config_value, value_type, config_group, remark)
VALUES
    ('conf_threshold', '置信度阈值', '0.45', 'number', 'runtime', '模型输出最低置信度'),
    ('iou_threshold', 'IOU 阈值', '0.42', 'number', 'runtime', 'NMS IOU 阈值'),
    ('continuous_hit_frames', '连续命中帧数', '4', 'number', 'runtime', '连续命中后触发异常保存'),
    ('evidence_image_count', '证据图数量', '3', 'number', 'runtime', '每次异常保存的证据图数量'),
    ('target_inference_fps', '目标推理帧率', '10', 'number', 'runtime', '浏览器实时推理目标帧率')
ON DUPLICATE KEY UPDATE
    config_name = VALUES(config_name),
    config_value = VALUES(config_value),
    value_type = VALUES(value_type),
    config_group = VALUES(config_group),
    remark = VALUES(remark);

UPDATE defect_type SET status = 0;

INSERT INTO defect_type (defect_code, defect_name, defect_name_en, defect_desc, display_color, sort_no, status)
VALUES
    ('oil_stain', '油渍', 'oil_stain', '油渍检测类别', '#facc15', 0, 1),
    ('ink_stain', '墨渍', 'ink_stain', '墨渍检测类别', '#22d3ee', 1, 1),
    ('hole', '破洞', 'hole', '破洞检测类别', '#fb923c', 2, 1),
    ('broken_yarn', '断纱', 'broken_yarn', '断纱检测类别', '#fb7185', 3, 1),
    ('wrinkle', '褶皱', 'wrinkle', '褶皱检测类别', '#38bdf8', 4, 1),
    ('foreign_matter', '异物', 'foreign_matter', '异物检测类别', '#34d399', 5, 1)
ON DUPLICATE KEY UPDATE
    defect_name = VALUES(defect_name),
    defect_name_en = VALUES(defect_name_en),
    defect_desc = VALUES(defect_desc),
    display_color = VALUES(display_color),
    sort_no = VALUES(sort_no),
    status = VALUES(status);

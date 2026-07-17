-- Align an existing deployment with the browser-side realtime inference target.
UPDATE qc_config
SET config_value = '10',
    remark = '浏览器实时推理目标帧率',
    update_time = CURRENT_TIMESTAMP
WHERE config_code = 'target_inference_fps';

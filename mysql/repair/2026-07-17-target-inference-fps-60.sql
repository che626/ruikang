-- Raise the browser realtime inference target for the current MySQL deployment.
UPDATE qc_config
SET config_value = '60',
    update_time = CURRENT_TIMESTAMP
WHERE config_code = 'target_inference_fps';

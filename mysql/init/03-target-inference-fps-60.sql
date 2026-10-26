-- Use a 60 FPS target for fresh MySQL deployments after seed data is loaded.
UPDATE qc_config
SET config_value = '60',
    update_time = CURRENT_TIMESTAMP
WHERE config_code = 'target_inference_fps';

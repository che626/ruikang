CREATE TABLE IF NOT EXISTS defect_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    defect_code VARCHAR(64) NOT NULL,
    defect_name VARCHAR(100) NOT NULL,
    defect_name_en VARCHAR(100),
    defect_desc VARCHAR(255),
    display_color VARCHAR(32) NOT NULL DEFAULT '#1de2d1',
    sort_no INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_defect_type_code UNIQUE (defect_code)
);

CREATE TABLE IF NOT EXISTS ai_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_name VARCHAR(100) NOT NULL,
    model_version VARCHAR(50),
    model_type VARCHAR(50) NOT NULL DEFAULT 'YOLO',
    model_path VARCHAR(255) NOT NULL,
    input_size VARCHAR(50) NOT NULL DEFAULT '640x640',
    conf_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.4500,
    iou_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.4200,
    prefer_gpu TINYINT NOT NULL DEFAULT 1,
    fallback_cpu TINYINT NOT NULL DEFAULT 1,
    is_active TINYINT NOT NULL DEFAULT 0,
    remark VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_model_path UNIQUE (model_path)
);

CREATE TABLE IF NOT EXISTS qc_camera (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_code VARCHAR(64) NOT NULL,
    camera_name VARCHAR(100) NOT NULL,
    camera_type VARCHAR(50) NOT NULL DEFAULT 'local_webcam',
    status TINYINT NOT NULL DEFAULT 1,
    location_desc VARCHAR(100),
    remark VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_camera_code UNIQUE (camera_code)
);

CREATE TABLE IF NOT EXISTS qc_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_no VARCHAR(64) NOT NULL,
    task_name VARCHAR(100),
    task_type VARCHAR(30) NOT NULL,
    camera_id BIGINT,
    model_id BIGINT,
    task_status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    total_frame_count INT NOT NULL DEFAULT 0,
    defect_frame_count INT NOT NULL DEFAULT 0,
    total_defect_count INT NOT NULL DEFAULT 0,
    total_image_count INT NOT NULL DEFAULT 0,
    processed_image_count INT NOT NULL DEFAULT 0,
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    remark VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_task_no UNIQUE (task_no)
);

CREATE INDEX IF NOT EXISTS idx_qc_task_type ON qc_task(task_type);
CREATE INDEX IF NOT EXISTS idx_qc_task_status ON qc_task(task_status);

CREATE TABLE IF NOT EXISTS qc_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_no VARCHAR(64) NOT NULL,
    task_id BIGINT,
    camera_id BIGINT,
    model_id BIGINT,
    inspect_type VARCHAR(30) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    result_judge VARCHAR(20) NOT NULL,
    confidence_score DECIMAL(8,6),
    defect_count INT NOT NULL DEFAULT 0,
    frame_snapshot_no VARCHAR(64),
    result_summary VARCHAR(500),
    inference_time_ms INT,
    preprocess_time_ms INT,
    postprocess_time_ms INT,
    review_status VARCHAR(30) NOT NULL DEFAULT 'CONFIRMED',
    review_remark VARCHAR(255),
    review_time TIMESTAMP,
    inspect_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_record_no UNIQUE (record_no)
);

CREATE INDEX IF NOT EXISTS idx_qc_record_task_id ON qc_record(task_id);
CREATE INDEX IF NOT EXISTS idx_qc_record_inspect_type ON qc_record(inspect_type);
CREATE INDEX IF NOT EXISTS idx_qc_record_review_status ON qc_record(review_status);
CREATE INDEX IF NOT EXISTS idx_qc_record_inspect_time ON qc_record(inspect_time);

CREATE TABLE IF NOT EXISTS qc_defect_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    defect_type_id BIGINT,
    defect_code VARCHAR(64) NOT NULL,
    defect_name VARCHAR(100),
    confidence DECIMAL(8,6) NOT NULL,
    x1 INT NOT NULL,
    y1 INT NOT NULL,
    x2 INT NOT NULL,
    y2 INT NOT NULL,
    box_width INT,
    box_height INT,
    area INT,
    image_width INT,
    image_height INT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qc_defect_result_record_id ON qc_defect_result(record_id);
CREATE INDEX IF NOT EXISTS idx_qc_defect_result_defect_code ON qc_defect_result(defect_code);

CREATE TABLE IF NOT EXISTS qc_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT,
    record_id BIGINT,
    file_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    file_ext VARCHAR(20),
    file_size BIGINT,
    mime_type VARCHAR(100),
    width INT,
    height INT,
    frame_index INT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qc_file_task_id ON qc_file(task_id);
CREATE INDEX IF NOT EXISTS idx_qc_file_record_id ON qc_file(record_id);
CREATE INDEX IF NOT EXISTS idx_qc_file_type ON qc_file(file_type);

CREATE TABLE IF NOT EXISTS qc_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exception_no VARCHAR(64) NOT NULL,
    record_id BIGINT NOT NULL,
    task_id BIGINT,
    exception_level VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    exception_title VARCHAR(200) NOT NULL,
    exception_desc VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    handle_result VARCHAR(255),
    handle_remark VARCHAR(255),
    handle_time TIMESTAMP,
    close_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_exception_no UNIQUE (exception_no)
);

CREATE INDEX IF NOT EXISTS idx_qc_exception_record_id ON qc_exception(record_id);
CREATE INDEX IF NOT EXISTS idx_qc_exception_status ON qc_exception(status);

CREATE TABLE IF NOT EXISTS qc_quality_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no VARCHAR(64) NOT NULL,
    record_id BIGINT,
    record_no VARCHAR(64) NOT NULL,
    report_title VARCHAR(200) NOT NULL,
    defect_summary CLOB,
    cause_analysis CLOB,
    risk_assessment CLOB,
    handling_suggestion CLOB,
    quality_conclusion CLOB,
    risk_level VARCHAR(30),
    ai_provider VARCHAR(50),
    ai_model VARCHAR(100),
    prompt_snapshot CLOB,
    raw_response CLOB,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_quality_report_no UNIQUE (report_no)
);

CREATE INDEX IF NOT EXISTS idx_qc_quality_report_record_no ON qc_quality_report(record_no);
CREATE INDEX IF NOT EXISTS idx_qc_quality_report_create_time ON qc_quality_report(create_time);

CREATE TABLE IF NOT EXISTS qc_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_code VARCHAR(64) NOT NULL,
    config_name VARCHAR(100) NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    value_type VARCHAR(30) NOT NULL DEFAULT 'string',
    config_group VARCHAR(50) NOT NULL DEFAULT 'runtime',
    remark VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qc_config_code UNIQUE (config_code)
);

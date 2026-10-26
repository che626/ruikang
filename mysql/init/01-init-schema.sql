CREATE DATABASE IF NOT EXISTS ruikang_fabric_qc
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE ruikang_fabric_qc;

CREATE TABLE IF NOT EXISTS defect_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    defect_code VARCHAR(64) NOT NULL COMMENT '缺陷编码，对应模型类别名',
    defect_name VARCHAR(100) NOT NULL COMMENT '缺陷中文名称',
    defect_name_en VARCHAR(100) DEFAULT NULL COMMENT '缺陷英文名称',
    defect_desc VARCHAR(255) DEFAULT NULL COMMENT '缺陷说明',
    display_color VARCHAR(32) DEFAULT '#1de2d1' COMMENT '前端显示颜色',
    sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_defect_type_code (defect_code)
) COMMENT='缺陷类型表';

CREATE TABLE IF NOT EXISTS ai_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    model_version VARCHAR(50) DEFAULT NULL COMMENT '模型版本',
    model_type VARCHAR(50) NOT NULL DEFAULT 'YOLO' COMMENT '模型类型',
    model_path VARCHAR(255) NOT NULL COMMENT '模型相对路径或项目内路径',
    input_size VARCHAR(50) NOT NULL DEFAULT '640x640' COMMENT '输入尺寸',
    conf_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.4500 COMMENT '置信度阈值',
    iou_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.4200 COMMENT 'IOU阈值',
    prefer_gpu TINYINT NOT NULL DEFAULT 1 COMMENT '是否优先GPU',
    fallback_cpu TINYINT NOT NULL DEFAULT 1 COMMENT 'GPU失败后是否回退CPU',
    is_active TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_ai_model_path (model_path)
) COMMENT='AI模型表';

CREATE TABLE IF NOT EXISTS qc_camera (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    camera_code VARCHAR(64) NOT NULL COMMENT '摄像头编码',
    camera_name VARCHAR(100) NOT NULL COMMENT '摄像头名称',
    camera_type VARCHAR(50) NOT NULL DEFAULT 'local_webcam' COMMENT '摄像头类型',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
    location_desc VARCHAR(100) DEFAULT NULL COMMENT '安装位置说明',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_qc_camera_code (camera_code)
) COMMENT='摄像头表';

CREATE TABLE IF NOT EXISTS qc_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    task_name VARCHAR(100) DEFAULT NULL COMMENT '任务名称',
    task_type VARCHAR(30) NOT NULL COMMENT '任务类型：image/realtime',
    camera_id BIGINT DEFAULT NULL COMMENT '摄像头ID，仅实时质检时使用',
    model_id BIGINT DEFAULT NULL COMMENT '模型ID',
    task_status VARCHAR(30) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/RUNNING/FINISHED/CLOSED',
    total_frame_count INT NOT NULL DEFAULT 0 COMMENT '总帧数，仅实时质检统计',
    defect_frame_count INT NOT NULL DEFAULT 0 COMMENT '缺陷帧数',
    total_defect_count INT NOT NULL DEFAULT 0 COMMENT '缺陷总数',
    total_image_count INT NOT NULL DEFAULT 0 COMMENT '图片任务总图片数',
    processed_image_count INT NOT NULL DEFAULT 0 COMMENT '已处理图片数',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    finish_time DATETIME DEFAULT NULL COMMENT '结束时间',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_qc_task_no (task_no),
    KEY idx_qc_task_type (task_type),
    KEY idx_qc_task_status (task_status)
) COMMENT='质检任务表';

CREATE TABLE IF NOT EXISTS qc_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    record_no VARCHAR(64) NOT NULL COMMENT '质检记录编号',
    task_id BIGINT DEFAULT NULL COMMENT '任务ID',
    camera_id BIGINT DEFAULT NULL COMMENT '摄像头ID',
    model_id BIGINT DEFAULT NULL COMMENT '模型ID',
    inspect_type VARCHAR(30) NOT NULL COMMENT '检测类型：image/realtime',
    source_type VARCHAR(30) NOT NULL COMMENT '来源类型：upload/camera',
    result_judge VARCHAR(20) NOT NULL COMMENT '检测结论：PASS/FAIL',
    confidence_score DECIMAL(8,6) DEFAULT NULL COMMENT '最高置信度',
    defect_count INT NOT NULL DEFAULT 0 COMMENT '缺陷数量',
    frame_snapshot_no VARCHAR(64) DEFAULT NULL COMMENT '实时检测帧事件编号',
    result_summary VARCHAR(500) DEFAULT NULL COMMENT '结果摘要',
    inference_time_ms INT DEFAULT NULL COMMENT '推理耗时毫秒',
    preprocess_time_ms INT DEFAULT NULL COMMENT '预处理耗时毫秒',
    postprocess_time_ms INT DEFAULT NULL COMMENT '后处理耗时毫秒',
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '人工复核状态：PENDING/PASS/REJECTED/MISSED/FALSE_ALARM',
    review_remark VARCHAR(255) DEFAULT NULL COMMENT '复核备注',
    review_time DATETIME DEFAULT NULL COMMENT '复核时间',
    inspect_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检测时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_qc_record_no (record_no),
    KEY idx_qc_record_task_id (task_id),
    KEY idx_qc_record_inspect_type (inspect_type),
    KEY idx_qc_record_review_status (review_status),
    KEY idx_qc_record_inspect_time (inspect_time)
) COMMENT='质检记录表';

CREATE TABLE IF NOT EXISTS qc_defect_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    record_id BIGINT NOT NULL COMMENT '质检记录ID',
    defect_type_id BIGINT DEFAULT NULL COMMENT '缺陷类型ID',
    defect_code VARCHAR(64) NOT NULL COMMENT '缺陷编码',
    defect_name VARCHAR(100) DEFAULT NULL COMMENT '缺陷名称',
    confidence DECIMAL(8,6) NOT NULL COMMENT '置信度',
    x1 INT NOT NULL COMMENT '左上角X',
    y1 INT NOT NULL COMMENT '左上角Y',
    x2 INT NOT NULL COMMENT '右下角X',
    y2 INT NOT NULL COMMENT '右下角Y',
    box_width INT DEFAULT NULL COMMENT '框宽度',
    box_height INT DEFAULT NULL COMMENT '框高度',
    area INT DEFAULT NULL COMMENT '面积',
    image_width INT DEFAULT NULL COMMENT '原图宽度',
    image_height INT DEFAULT NULL COMMENT '原图高度',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_qc_defect_result_record_id (record_id),
    KEY idx_qc_defect_result_defect_code (defect_code)
) COMMENT='缺陷检测结果表';

CREATE TABLE IF NOT EXISTS qc_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id BIGINT DEFAULT NULL COMMENT '任务ID',
    record_id BIGINT DEFAULT NULL COMMENT '记录ID',
    file_type VARCHAR(50) NOT NULL COMMENT '文件类型：source_image/result_image/evidence_image',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    relative_path VARCHAR(500) NOT NULL COMMENT '相对存储根目录的相对路径',
    file_ext VARCHAR(20) DEFAULT NULL COMMENT '扩展名',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小',
    mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    width INT DEFAULT NULL COMMENT '图片宽度',
    height INT DEFAULT NULL COMMENT '图片高度',
    frame_index INT DEFAULT NULL COMMENT '实时检测帧序号',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_qc_file_task_id (task_id),
    KEY idx_qc_file_record_id (record_id),
    KEY idx_qc_file_type (file_type)
) COMMENT='质检文件表';

CREATE TABLE IF NOT EXISTS qc_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    exception_no VARCHAR(64) NOT NULL COMMENT '异常编号',
    record_id BIGINT NOT NULL COMMENT '关联记录ID',
    task_id BIGINT DEFAULT NULL COMMENT '关联任务ID',
    exception_level VARCHAR(30) NOT NULL DEFAULT 'MEDIUM' COMMENT '异常等级：LOW/MEDIUM/HIGH/CRITICAL',
    exception_title VARCHAR(200) NOT NULL COMMENT '异常标题',
    exception_desc VARCHAR(500) DEFAULT NULL COMMENT '异常描述',
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/PROCESSING/CLOSED/IGNORED',
    handle_result VARCHAR(255) DEFAULT NULL COMMENT '处理结果',
    handle_remark VARCHAR(255) DEFAULT NULL COMMENT '处理备注',
    handle_time DATETIME DEFAULT NULL COMMENT '处理时间',
    close_time DATETIME DEFAULT NULL COMMENT '关闭时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_qc_exception_no (exception_no),
    KEY idx_qc_exception_record_id (record_id),
    KEY idx_qc_exception_status (status)
) COMMENT='质量异常表';

CREATE TABLE IF NOT EXISTS qc_quality_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    report_no VARCHAR(64) NOT NULL COMMENT '报告编号',
    record_id BIGINT DEFAULT NULL COMMENT '关联记录ID',
    record_no VARCHAR(64) NOT NULL COMMENT '关联记录编号或整体分析编号',
    report_title VARCHAR(200) NOT NULL COMMENT '报告标题',
    defect_summary LONGTEXT COMMENT '缺陷摘要',
    cause_analysis LONGTEXT COMMENT '原因分析',
    risk_assessment LONGTEXT COMMENT '风险评估',
    handling_suggestion LONGTEXT COMMENT '处理建议',
    quality_conclusion LONGTEXT COMMENT '质检结论',
    risk_level VARCHAR(30) DEFAULT NULL COMMENT '风险等级',
    ai_provider VARCHAR(50) DEFAULT NULL COMMENT 'AI提供方',
    ai_model VARCHAR(100) DEFAULT NULL COMMENT 'AI模型',
    prompt_snapshot LONGTEXT COMMENT '提示词快照',
    raw_response LONGTEXT COMMENT '原始响应',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_qc_quality_report_no (report_no),
    KEY idx_qc_quality_report_record_no (record_no),
    KEY idx_qc_quality_report_create_time (create_time)
) COMMENT='AIGC质检分析报告表';

CREATE TABLE IF NOT EXISTS qc_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    config_code VARCHAR(64) NOT NULL COMMENT '配置编码',
    config_name VARCHAR(100) NOT NULL COMMENT '配置名称',
    config_value VARCHAR(255) NOT NULL COMMENT '配置值',
    value_type VARCHAR(30) NOT NULL DEFAULT 'string' COMMENT '值类型：string/number/boolean',
    config_group VARCHAR(50) NOT NULL DEFAULT 'runtime' COMMENT '配置分组',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_qc_config_code (config_code)
) COMMENT='系统参数表';

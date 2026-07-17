SET NAMES utf8mb4;
USE ruikang_fabric_qc;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE qc_quality_report;
TRUNCATE TABLE qc_exception;
TRUNCATE TABLE qc_file;
TRUNCATE TABLE qc_defect_result;
TRUNCATE TABLE qc_record;
TRUNCATE TABLE qc_task;

SET FOREIGN_KEY_CHECKS = 1;

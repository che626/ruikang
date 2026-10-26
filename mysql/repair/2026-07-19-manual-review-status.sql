USE ruikang_fabric_qc;

UPDATE qc_record
SET review_status = 'PENDING',
    update_time = CURRENT_TIMESTAMP
WHERE review_time IS NULL
  AND review_status = 'CONFIRMED';

ALTER TABLE qc_record
    MODIFY review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
    COMMENT '人工复核状态：PENDING/PASS/REJECTED/MISSED/FALSE_ALARM';

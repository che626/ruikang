package com.ruikang.qc.service;

import com.ruikang.qc.dto.inspection.DetectionResultResponse;
import com.ruikang.qc.dto.record.RecordDetailResponse;
import com.ruikang.qc.dto.record.RecordListItemResponse;
import com.ruikang.qc.dto.record.ReviewRecordRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RecordService {

    private final JdbcTemplate jdbcTemplate;

    public RecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RecordListItemResponse> listRecords() {
        return jdbcTemplate.query("""
                        SELECT r.record_no, r.inspect_type, r.result_judge, r.review_status, r.defect_count,
                               r.confidence_score, r.result_summary, r.inspect_time,
                               COALESCE(
                                   (
                                       SELECT f.relative_path
                                       FROM qc_file f
                                       WHERE f.record_id = r.id AND f.file_type = 'result_image'
                                       ORDER BY f.id DESC
                                       LIMIT 1
                                   ),
                                   (
                                       SELECT f.relative_path
                                       FROM qc_file f
                                       WHERE f.record_id = r.id AND f.file_type = 'source_image'
                                       ORDER BY f.id DESC
                                       LIMIT 1
                                   )
                               ) AS thumbnail_image_path
                        FROM qc_record r
                        ORDER BY r.inspect_time DESC
                        """,
                (resultSet, rowNum) -> new RecordListItemResponse(
                        resultSet.getString("record_no"),
                        resultSet.getString("inspect_type"),
                        resultSet.getString("result_judge"),
                        resultSet.getString("review_status"),
                        resultSet.getInt("defect_count"),
                        resultSet.getObject("confidence_score", Double.class),
                        resultSet.getString("result_summary"),
                        resultSet.getObject("inspect_time", LocalDateTime.class),
                        resultSet.getString("thumbnail_image_path")
                ));
    }

    public RecordDetailResponse getRecordDetail(String recordNo) {
        return jdbcTemplate.query("""
                        SELECT inspect_type, source_type, result_judge, confidence_score, defect_count,
                               review_status, review_remark, inspect_time, preprocess_time_ms,
                               inference_time_ms, postprocess_time_ms
                        FROM qc_record
                        WHERE record_no = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("记录不存在: " + recordNo);
                    }
                    List<DetectionResultResponse> detections = jdbcTemplate.query("""
                                    SELECT d.defect_code, d.defect_name, t.display_color, d.confidence,
                                           d.x1, d.y1, d.x2, d.y2, d.box_width, d.box_height, d.area
                                    FROM qc_defect_result d
                                    JOIN qc_record r ON r.id = d.record_id
                                    LEFT JOIN defect_type t ON t.id = d.defect_type_id
                                    WHERE r.record_no = ?
                                    ORDER BY d.confidence DESC
                                    """,
                            (detectionRs, rowNum) -> new DetectionResultResponse(
                                    detectionRs.getString("defect_code"),
                                    detectionRs.getString("defect_name"),
                                    detectionRs.getString("display_color"),
                                    detectionRs.getDouble("confidence"),
                                    detectionRs.getInt("x1"),
                                    detectionRs.getInt("y1"),
                                    detectionRs.getInt("x2"),
                                    detectionRs.getInt("y2"),
                                    detectionRs.getInt("box_width"),
                                    detectionRs.getInt("box_height"),
                                    detectionRs.getInt("area")
                            ),
                            recordNo);

                    List<String> evidencePaths = jdbcTemplate.query("""
                                    SELECT f.relative_path
                                    FROM qc_file f
                                    JOIN qc_record r ON r.id = f.record_id
                                    WHERE r.record_no = ? AND f.file_type = 'evidence_image'
                                    ORDER BY f.id ASC
                                    """,
                            (fileRs, rowNum) -> fileRs.getString("relative_path"),
                            recordNo);

                    String sourcePath = querySinglePath(recordNo, "source_image");
                    String resultPath = querySinglePath(recordNo, "result_image");

                    LatestException latestException = jdbcTemplate.query("""
                                    SELECT exception_no, status
                                    FROM qc_exception
                                    WHERE record_id = (SELECT id FROM qc_record WHERE record_no = ?)
                                    ORDER BY id DESC
                                    LIMIT 1
                                    """,
                            exceptionRs -> exceptionRs.next()
                                    ? new LatestException(
                                    exceptionRs.getString("exception_no"),
                                    exceptionRs.getString("status"))
                                    : null,
                            recordNo);

                    return new RecordDetailResponse(
                            recordNo,
                            resultSet.getString("inspect_type"),
                            resultSet.getString("source_type"),
                            resultSet.getString("result_judge"),
                            resultSet.getObject("confidence_score", Double.class),
                            resultSet.getInt("defect_count"),
                            resultSet.getString("review_status"),
                            resultSet.getString("review_remark"),
                            resultSet.getObject("inspect_time", LocalDateTime.class),
                            resultSet.getObject("preprocess_time_ms", Integer.class),
                            resultSet.getObject("inference_time_ms", Integer.class),
                            resultSet.getObject("postprocess_time_ms", Integer.class),
                            sourcePath,
                            resultPath,
                            evidencePaths,
                            detections,
                            latestException == null ? null : latestException.exceptionNo(),
                            latestException == null ? null : latestException.status()
                    );
                },
                recordNo);
    }

    public RecordDetailResponse reviewRecord(String recordNo, ReviewRecordRequest request) {
        int updatedRows = jdbcTemplate.update("""
                UPDATE qc_record
                SET review_status = ?, review_remark = ?, review_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
                WHERE record_no = ?
                """, request.reviewStatus(), request.reviewRemark(), recordNo);
        if (updatedRows == 0) {
            throw new IllegalArgumentException("记录不存在: " + recordNo);
        }
        return getRecordDetail(recordNo);
    }

    @Transactional
    public void clearRecords() {
        jdbcTemplate.update("DELETE FROM qc_exception");
        jdbcTemplate.update("DELETE FROM qc_defect_result");
        jdbcTemplate.update("DELETE FROM qc_file");
        jdbcTemplate.update("DELETE FROM qc_record");
    }

    private String querySinglePath(String recordNo, String fileType) {
        return jdbcTemplate.query("""
                        SELECT f.relative_path
                        FROM qc_file f
                        JOIN qc_record r ON r.id = f.record_id
                        WHERE r.record_no = ? AND f.file_type = ?
                        ORDER BY f.id ASC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getString("relative_path") : null,
                recordNo, fileType);
    }

    private record LatestException(String exceptionNo, String status) {
    }
}

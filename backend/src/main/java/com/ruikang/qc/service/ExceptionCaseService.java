package com.ruikang.qc.service;

import com.ruikang.qc.dto.exceptioncase.ExceptionDetailResponse;
import com.ruikang.qc.dto.exceptioncase.ExceptionListItemResponse;
import com.ruikang.qc.dto.exceptioncase.HandleExceptionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExceptionCaseService {

    private final JdbcTemplate jdbcTemplate;

    public ExceptionCaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ExceptionListItemResponse> listExceptions() {
        return jdbcTemplate.query("""
                        SELECT e.exception_no, e.exception_title, e.exception_level, e.status, r.record_no, e.create_time,
                               (
                                   SELECT t.defect_name
                                   FROM qc_defect_result d
                                   JOIN defect_type t ON t.defect_code = d.defect_code AND t.status = 1
                                   WHERE d.record_id = r.id
                                   ORDER BY d.confidence DESC
                                   LIMIT 1
                               ) AS defect_name
                        FROM qc_exception e
                        JOIN qc_record r ON r.id = e.record_id
                        ORDER BY e.create_time DESC
                        """,
                (resultSet, rowNum) -> {
                    String defectName = resultSet.getString("defect_name");
                    String title = defectName == null || defectName.isBlank()
                            ? resultSet.getString("exception_title")
                            : "质检发现 " + defectName + " 缺陷";
                    return new ExceptionListItemResponse(
                            resultSet.getString("exception_no"),
                            title,
                            resultSet.getString("exception_level"),
                            resultSet.getString("status"),
                            resultSet.getString("record_no"),
                            resultSet.getObject("create_time", LocalDateTime.class)
                    );
                });
    }

    public ExceptionDetailResponse getExceptionDetail(String exceptionNo) {
        return jdbcTemplate.query("""
                        SELECT e.exception_title, e.exception_desc, e.exception_level, e.status, r.record_no,
                               e.handle_result, e.handle_remark, e.create_time, e.handle_time,
                               (
                                   SELECT t.defect_name
                                   FROM qc_defect_result d
                                   JOIN defect_type t ON t.defect_code = d.defect_code AND t.status = 1
                                   WHERE d.record_id = r.id
                                   ORDER BY d.confidence DESC
                                   LIMIT 1
                               ) AS defect_name
                        FROM qc_exception e
                        JOIN qc_record r ON r.id = e.record_id
                        WHERE e.exception_no = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("异常单不存在: " + exceptionNo);
                    }
                    String recordNo = resultSet.getString("record_no");
                    String defectName = resultSet.getString("defect_name");
                    String title = defectName == null || defectName.isBlank()
                            ? resultSet.getString("exception_title")
                            : "质检发现 " + defectName + " 缺陷";
                    String description = defectName == null || defectName.isBlank()
                            ? resultSet.getString("exception_desc")
                            : "关联质检记录 " + recordNo;
                    List<String> evidencePaths = jdbcTemplate.query("""
                                    SELECT f.relative_path
                                    FROM qc_file f
                                    JOIN qc_record r ON r.id = f.record_id
                                    JOIN qc_exception e ON e.record_id = r.id
                                    WHERE e.exception_no = ? AND f.file_type IN ('evidence_image', 'result_image')
                                    ORDER BY f.id ASC
                                    """,
                            (fileRs, rowNum) -> fileRs.getString("relative_path"),
                            exceptionNo);
                    return new ExceptionDetailResponse(
                            exceptionNo,
                            title,
                            description,
                            resultSet.getString("exception_level"),
                            resultSet.getString("status"),
                            recordNo,
                            resultSet.getString("handle_result"),
                            resultSet.getString("handle_remark"),
                            resultSet.getObject("create_time", LocalDateTime.class),
                            resultSet.getObject("handle_time", LocalDateTime.class),
                            evidencePaths
                    );
                },
                exceptionNo);
    }

    public ExceptionDetailResponse handleException(String exceptionNo, HandleExceptionRequest request) {
        jdbcTemplate.update("""
                UPDATE qc_exception
                SET status = ?, handle_result = ?, handle_remark = ?, handle_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
                WHERE exception_no = ?
                """,
                request.status(),
                request.handleResult(),
                request.handleRemark(),
                exceptionNo
        );
        return getExceptionDetail(exceptionNo);
    }
}

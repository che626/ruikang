package com.ruikang.qc.dto;

import com.ruikang.qc.dto.catalog.UpdateDefectColorRequest;
import com.ruikang.qc.dto.exceptioncase.HandleExceptionRequest;
import com.ruikang.qc.dto.record.ReviewRecordRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void acceptsDocumentedValues() {
        assertThat(VALIDATOR.validate(new ReviewRecordRequest("FALSE_ALARM", null))).isEmpty();
        assertThat(VALIDATOR.validate(new ReviewRecordRequest("PASS", "人工确认通过"))).isEmpty();
        assertThat(VALIDATOR.validate(new ReviewRecordRequest("REJECTED", "人工确认不通过"))).isEmpty();
        assertThat(VALIDATOR.validate(new ReviewRecordRequest("MISSED", "人工确认漏报"))).isEmpty();
        assertThat(VALIDATOR.validate(new HandleExceptionRequest("CLOSED", null, null))).isEmpty();
        assertThat(VALIDATOR.validate(new UpdateDefectColorRequest("#34d399"))).isEmpty();
    }

    @Test
    void rejectsValuesOutsideDatabaseContract() {
        assertThat(VALIDATOR.validate(new ReviewRecordRequest("REVIEWED", null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new HandleExceptionRequest("DONE", null, null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new UpdateDefectColorRequest("green"))).isNotEmpty();
    }
}

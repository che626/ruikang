package com.ruikang.qc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ruikang_auth_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AuthSessionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rejectsProtectedApiWithoutSession() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/defect-types", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(objectMapper.readTree(response.getBody()).path("success").asBoolean()).isFalse();
    }

    @Test
    void allowsProtectedApiAfterLogin() throws Exception {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new HttpEntity<>("""
                        {"username":"admin","password":"admin123"}
                        """, loginHeaders),
                String.class
        );

        assertThat(loginResponse.getStatusCode().is2xxSuccessful()).isTrue();
        String setCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("ruikang_session=").contains("HttpOnly");

        HttpHeaders authenticatedHeaders = new HttpHeaders();
        authenticatedHeaders.set(HttpHeaders.COOKIE, setCookie.split(";", 2)[0]);
        ResponseEntity<String> defectResponse = restTemplate.exchange(
                "/api/defect-types",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders),
                String.class
        );

        assertThat(defectResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(objectMapper.readTree(defectResponse.getBody()).path("data").size()).isEqualTo(6);
    }
}

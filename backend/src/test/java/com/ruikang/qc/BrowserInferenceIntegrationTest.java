package com.ruikang.qc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ruikang_browser_inference_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qc.storage.root-path=target/browser-inference-test-uploads"
})
@ActiveProfiles("test")
class BrowserInferenceIntegrationTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z2S8AAAAASUVORK5CYII="
    );

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void servesVersionedBrowserModelManifestAndModelHeaders() throws Exception {
        HttpHeaders headers = authenticatedHeaders();
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/inference/manifest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode manifest = objectMapper.readTree(response.getBody()).path("data");
        assertThat(manifest.path("sha256").asText()).hasSize(64);
        assertThat(manifest.path("inputWidth").asInt()).isEqualTo(640);
        assertThat(manifest.path("inputHeight").asInt()).isEqualTo(640);
        assertThat(manifest.path("classes").size()).isEqualTo(6);
        assertThat(manifest.path("preferredExecutionProviders").toString()).contains("webgpu", "wasm");

        String modelUrl = manifest.path("modelUrl").asText();
        ResponseEntity<byte[]> modelResponse = restTemplate.exchange(
                modelUrl,
                HttpMethod.HEAD,
                new HttpEntity<>(headers),
                byte[].class
        );
        assertThat(modelResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(modelResponse.getHeaders().getETag()).contains(manifest.path("sha256").asText());
        assertThat(modelResponse.getHeaders().getContentLength()).isEqualTo(manifest.path("fileSize").asLong());
        assertThat(modelResponse.getHeaders().getCacheControl()).contains("immutable");
    }

    @Test
    void acceptsImageResultsProducedByTheBrowser() throws Exception {
        HttpHeaders headers = authenticatedHeaders();
        JsonNode manifest = fetchManifest(headers);
        String payload = objectMapper.createObjectNode()
                .put("modelSha256", manifest.path("sha256").asText())
                .put("remark", "browser integration test")
                .set("records", objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode()
                                .put("originalFileName", "sample.png")
                                .put("imageWidth", 1)
                                .put("imageHeight", 1)
                                .put("preprocessTimeMs", 3)
                                .put("inferenceTimeMs", 80)
                                .put("postprocessTimeMs", 1)
                                .put("providerStrategy", "WASM")
                                .set("detections", objectMapper.createArrayNode())
                ))
                .toString();

        ResponseEntity<String> response = postMultipart(
                "/api/inspection/image/tasks/client-results",
                headers,
                payload,
                "sourceFiles",
                "sample.png",
                "resultFiles",
                "sample-result.png"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("taskStatus").asText()).isEqualTo("FINISHED");
        assertThat(data.path("fileCount").asInt()).isEqualTo(1);
        assertThat(data.path("records").get(0).path("resultJudge").asText()).isEqualTo("PASS");
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qc_record WHERE task_id = (SELECT id FROM qc_task WHERE task_no = ?)",
                Integer.class,
                data.path("taskNo").asText()
        );
        assertThat(records).isEqualTo(1);
    }

    @Test
    void acceptsRealtimeMetricsAndDeduplicatesBrowserEvidenceEvents() throws Exception {
        HttpHeaders headers = authenticatedHeaders();
        JsonNode manifest = fetchManifest(headers);
        restTemplate.exchange(
                "/api/inspection/realtime/start",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        try {
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.putAll(headers);
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> metricsResponse = restTemplate.exchange(
                    "/api/inspection/realtime/metrics",
                    HttpMethod.PUT,
                    new HttpEntity<>("""
                            {
                              "totalFrameCount": 4,
                              "defectFrameCount": 4,
                              "totalDefectCount": 4,
                              "previewFps": 30,
                              "inferenceFps": 10,
                              "latencyMs": 92,
                              "providerStrategy": "WebGPU/WASM"
                            }
                            """, jsonHeaders),
                    String.class
            );
            assertThat(metricsResponse.getStatusCode().is2xxSuccessful()).isTrue();

            String defectCode = manifest.path("classes").get(0).path("defectCode").asText();
            String payload = objectMapper.createObjectNode()
                    .put("modelSha256", manifest.path("sha256").asText())
                    .put("frameIndex", 4)
                    .put("imageWidth", 16)
                    .put("imageHeight", 16)
                    .put("preprocessTimeMs", 5)
                    .put("inferenceTimeMs", 85)
                    .put("postprocessTimeMs", 2)
                    .put("providerStrategy", "WebGPU/WASM")
                    .set("detections", objectMapper.createArrayNode().add(
                            objectMapper.createObjectNode()
                                    .put("defectCode", defectCode)
                                    .put("confidence", 0.91)
                                    .put("x1", 1)
                                    .put("y1", 1)
                                    .put("x2", 8)
                                    .put("y2", 8)
                    ))
                    .toString();

            ResponseEntity<String> first = postMultipart(
                    "/api/inspection/realtime/client-events",
                    headers,
                    payload,
                    "sourceFiles",
                    "frame-source.png",
                    "resultFiles",
                    "frame-result.png"
            );
            assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
            JsonNode firstData = objectMapper.readTree(first.getBody()).path("data");
            assertThat(firstData.path("accepted").asBoolean()).isTrue();
            assertThat(firstData.path("recordNo").asText()).isNotBlank();

            ResponseEntity<String> duplicate = postMultipart(
                    "/api/inspection/realtime/client-events",
                    headers,
                    payload,
                    "sourceFiles",
                    "frame-source-2.png",
                    "resultFiles",
                    "frame-result-2.png"
            );
            JsonNode duplicateData = objectMapper.readTree(duplicate.getBody()).path("data");
            assertThat(duplicateData.path("accepted").asBoolean()).isFalse();
            assertThat(duplicateData.path("duplicate").asBoolean()).isTrue();
        } finally {
            restTemplate.exchange(
                    "/api/inspection/realtime/stop",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class
            );
        }
    }

    private JsonNode fetchManifest(HttpHeaders headers) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/inference/manifest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private ResponseEntity<String> postMultipart(
            String path,
            HttpHeaders authenticatedHeaders,
            String payload,
            String firstPartName,
            String firstFileName,
            String secondPartName,
            String secondFileName
    ) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders payloadHeaders = new HttpHeaders();
        payloadHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("payload", new HttpEntity<>(payload, payloadHeaders));
        body.add(firstPartName, imagePart(firstFileName));
        body.add(secondPartName, imagePart(secondFileName));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(authenticatedHeaders);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders),
                String.class
        );
    }

    private HttpEntity<ByteArrayResource> imagePart(String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        ByteArrayResource resource = new ByteArrayResource(ONE_PIXEL_PNG) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        return new HttpEntity<>(resource, headers);
    }

    private HttpHeaders authenticatedHeaders() throws Exception {
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
        String cookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie.split(";", 2)[0]);
        return headers;
    }
}

package com.ruikang.qc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruikang.qc.service.RealtimeInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeStatusWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeStatusWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final RealtimeInspectionService realtimeInspectionService;
    private final ObjectMapper objectMapper;

    public RealtimeStatusWebSocketHandler(RealtimeInspectionService realtimeInspectionService, ObjectMapper objectMapper) {
        this.realtimeInspectionService = realtimeInspectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        log.info("WebSocket connected: session={}, remote={}", session.getId(), session.getRemoteAddress());
        session.sendMessage(new TextMessage("""
                {
                  "type": "welcome",
                  "channel": "realtime-status",
                  "message": "Realtime inspection websocket connected.",
                  "timestamp": "%s"
                }
                """.formatted(LocalDateTime.now())));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        byte[] frameBytes = new byte[message.getPayloadLength()];
        message.getPayload().get(frameBytes);
        log.debug("WebSocket binary frame: session={}, size={} bytes", session.getId(), frameBytes.length);
        sendFrameResult(session, frameBytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String payload = message.getPayload();
        if (payload != null && payload.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> frameMessage = objectMapper.readValue(payload, Map.class);
                Object frameData = frameMessage.get("frameData");
                if (frameData instanceof String base64Frame) {
                    log.debug("WebSocket text frame: session={}, payloadLength={}", session.getId(), payload.length());
                    sendFrameResult(session, decodeBase64Frame(base64Frame));
                    return;
                }
            } catch (Exception parseException) {
                log.warn("WebSocket frame parse error: session={}, payloadLength={}, error={}",
                        session.getId(), payload != null ? payload.length() : 0, parseException.getMessage());
            }
        }
        if (session.isOpen()) {
            session.sendMessage(new TextMessage("""
                    {
                      "type": "echo",
                      "payload": %s,
                      "timestamp": "%s"
                    }
                    """.formatted(quoteJson(payload), LocalDateTime.now())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket disconnected: session={}, code={}, reason={}",
                session.getId(), status.getCode(), status.getReason());
    }

    private String quoteJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public void broadcast(String payload) throws IOException {
        for (WebSocketSession current : sessions) {
            if (current.isOpen()) {
                current.sendMessage(new TextMessage(payload));
            }
        }
    }

    private void sendFrameResult(WebSocketSession session, byte[] frameBytes) throws IOException {
        RealtimeInspectionService.RealtimeFrameResultResponse result = realtimeInspectionService.processFrame(frameBytes);
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(result)));
        }
    }

    private byte[] decodeBase64Frame(String frameData) {
        String normalized = frameData;
        int commaIndex = normalized.indexOf(',');
        if (commaIndex >= 0) {
            normalized = normalized.substring(commaIndex + 1);
        }
        return Base64.getDecoder().decode(normalized);
    }
}

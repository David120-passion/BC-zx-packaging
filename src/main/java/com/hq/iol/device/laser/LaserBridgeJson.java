package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** LaserBridge.exe 行 JSON 协议的编解码（Jackson，仅主类内部使用）。 */
final class LaserBridgeJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private LaserBridgeJson() {
    }

    static ObjectNode request(long id, String cmd) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("cmd", cmd);
        return node;
    }

    /** 新建一个空 params 对象（请求体由 client 组装）。 */
    static ObjectNode params() {
        return MAPPER.createObjectNode();
    }

    static JsonNode parse(String line) throws LaserException {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new LaserException("BAD_RESPONSE", "桥接进程返回了无法解析的 JSON: " + abbreviate(line), e);
        }
    }

    static boolean isResponse(JsonNode node) {
        return node.has("id");
    }

    static boolean isEvent(JsonNode node) {
        return node.has("event");
    }

    static long id(JsonNode node) {
        return node.get("id").asLong();
    }

    static boolean ok(JsonNode node) {
        return node.path("ok").asBoolean(false);
    }

    static String eventName(JsonNode node) {
        return node.path("event").asText(null);
    }

    /** 将错误响应转换为异常；对成功响应返回 {@code null}。 */
    static LaserException asException(JsonNode response) {
        JsonNode err = response.path("error");
        String code = err.path("code").asText("UNKNOWN");
        String message = err.path("message").asText("unknown bridge error");
        Integer sdkCode = err.hasNonNull("sdkCode") ? err.get("sdkCode").asInt() : null;
        return new LaserException(code, message, sdkCode);
    }

    static List<LaserEntity> entities(JsonNode result) {
        List<LaserEntity> out = new ArrayList<>();
        JsonNode arr = result.path("entities");
        if (arr instanceof ArrayNode array) {
            for (JsonNode e : array) {
                String text = e.hasNonNull("text") ? e.get("text").asText() : null;
                out.add(new LaserEntity(e.path("name").asText(), text, e.path("order").asInt(0)));
            }
        }
        return out;
    }

    static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}

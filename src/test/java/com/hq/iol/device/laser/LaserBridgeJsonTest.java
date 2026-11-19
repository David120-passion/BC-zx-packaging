package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 桥接协议 JSON 编解码与 DTO 解析（纯内存，不启动进程）。 */
class LaserBridgeJsonTest {

    @Test
    void 请求包含自增id与命令() throws Exception {
        ObjectNode req = LaserBridgeJson.request(7, "ping");
        assertThat(req.get("id").asLong()).isEqualTo(7L);
        assertThat(req.get("cmd").asText()).isEqualTo("ping");
        assertThat(req.has("params")).isFalse();
    }

    @Test
    void 成功响应解析() throws Exception {
        JsonNode node = LaserBridgeJson.parse("{\"id\":1,\"ok\":true,\"result\":{\"pong\":true}}");
        assertThat(LaserBridgeJson.isResponse(node)).isTrue();
        assertThat(LaserBridgeJson.ok(node)).isTrue();
        assertThat(node.path("result").path("pong").asBoolean()).isTrue();
    }

    @Test
    void 错误响应保留协议码与SDK错误码() throws Exception {
        JsonNode node = LaserBridgeJson.parse(
                "{\"id\":2,\"ok\":false,\"error\":{\"code\":\"SDK_ERROR\",\"message\":\"lmc1_Initial failed\",\"sdkCode\":4}}");
        assertThat(LaserBridgeJson.ok(node)).isFalse();

        LaserException ex = LaserBridgeJson.asException(node);
        assertThat(ex.getCode()).isEqualTo("SDK_ERROR");
        assertThat(ex.getMessage()).contains("lmc1_Initial failed");
        assertThat(ex.getSdkCode()).isEqualTo(4);
    }

    @Test
    void 错误响应无SDK错误码时为null() throws Exception {
        JsonNode node = LaserBridgeJson.parse(
                "{\"id\":3,\"ok\":false,\"error\":{\"code\":\"NOT_INITIALIZED\",\"message\":\"SDK not initialized\"}}");
        LaserException ex = LaserBridgeJson.asException(node);
        assertThat(ex.getCode()).isEqualTo("NOT_INITIALIZED");
        assertThat(ex.getSdkCode()).isNull();
    }

    @Test
    void 完成事件解析() throws Exception {
        JsonNode node = LaserBridgeJson.parse("{\"event\":\"markFinished\",\"data\":{\"code\":8}}");
        assertThat(LaserBridgeJson.isEvent(node)).isTrue();
        assertThat(LaserBridgeJson.eventName(node)).isEqualTo("markFinished");
        assertThat(node.path("data").path("code").asInt()).isEqualTo(8);
    }

    @Test
    void 实体列表解析区分文本与非文本对象() throws Exception {
        JsonNode result = LaserBridgeJson.parse(
                "{\"count\":2,\"entities\":["
                        + "{\"name\":\"TITLE\",\"text\":\"abc\",\"order\":0},"
                        + "{\"name\":\"LOGO\",\"text\":null,\"order\":1}]}");
        List<LaserEntity> list = LaserBridgeJson.entities(result);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).name()).isEqualTo("TITLE");
        assertThat(list.get(0).text()).isEqualTo("abc");
        assertThat(list.get(0).isText()).isTrue();
        assertThat(list.get(0).order()).isEqualTo(0);
        assertThat(list.get(1).isText()).isFalse();
    }

    @Test
    void 非法JSON抛出协议异常() throws Exception {
        assertThatThrownBy(() -> LaserBridgeJson.parse("not json"))
                .isInstanceOf(LaserException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void 激光异常记录SDK码的字符串表示() throws Exception {
        LaserException ex = new LaserException("SDK_ERROR", "打标失败", 18);
        assertThat(ex.toString()).contains("sdkCode=18");
        assertThat(ex.getSdkCode()).isEqualTo(18);
    }
}

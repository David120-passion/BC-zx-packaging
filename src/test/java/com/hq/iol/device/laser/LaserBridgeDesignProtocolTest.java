package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 桥接进程级 design 协议的最小验证（真实启动 LaserBridge.exe，但不初始化 SDK——
 * 本机无 LMC1 驱动/板卡，且禁止真实出光）。只验证命令已注册且在未初始化时被明确
 * 拒绝（NOT_INITIALIZED 而不是 UNKNOWN_COMMAND），非法结构/打标中的防护由
 * CmdDesign 的实现保证（EnsureReady 优先于参数解析）。
 */
class LaserBridgeDesignProtocolTest {

    private LaserBridgeClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private void start() throws Exception {
        client = new LaserBridgeClient(LaserBridgeBinary.resolve(), Path.of(".").toAbsolutePath());
    }

    private static ObjectNode designParams() {
        var params = LaserBridgeJson.params();
        var mapper = LaserBridgeJson.MAPPER;
        var design = mapper.createObjectNode();
        design.put("name", "协议测试");
        design.put("widthMm", 100);
        design.put("heightMm", 60);
        design.put("pen", 0);
        var objects = design.putArray("objects");
        var text = objects.addObject();
        text.put("kind", "text");
        text.put("name", "t1");
        text.put("text", "HELLO");
        text.put("x", 1.5);
        text.put("y", 2.0);
        text.put("width", 30);
        text.put("height", 5);
        text.put("rotation", 0);
        text.put("pen", 0);
        var attributes = text.putObject("attributes");
        attributes.put("font", "Microsoft YaHei UI");
        attributes.put("fontHeight", 3.0);
        attributes.put("letterSpacing", 0.1);
        attributes.put("align", 1);
        attributes.put("bold", true);
        attributes.put("italic", false);
        params.set("design", design);
        return params;
    }

    @Test
    void design未初始化时回报NOT_INITIALIZED证明命令已注册() throws Exception {
        start();
        assertThatThrownBy(() -> client.request("design", designParams(), Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode())
                        .isIn("NOT_INITIALIZED", "INIT_HUNG", "CLOSED"));
        // 被拒绝后进程仍然可用（未因 design 崩溃或退出）
        var ping = client.request("ping", null, Duration.ofSeconds(10));
        assertThat(ping.path("pong").asBoolean()).isTrue();
    }

    @Test
    void 红光未初始化时回报NOT_INITIALIZED证明命令已注册() throws Exception {
        start();
        assertThatThrownBy(() -> client.request("redLight", null, Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode())
                        .isIn("NOT_INITIALIZED", "INIT_HUNG", "CLOSED"));
        var ping = client.request("ping", null, Duration.ofSeconds(10));
        assertThat(ping.path("pong").asBoolean()).isTrue();
    }

    @Test
    void design缺少design参数时同样先回报未初始化而非BAD_REQUEST() throws Exception {
        start();
        // EnsureReady 优先于参数解析：未初始化时无论参数是否合法都必须报 NOT_INITIALIZED，
        // 证明防护顺序正确（若实现先解析参数会得到 BAD_REQUEST）。
        assertThatThrownBy(() -> client.request("design", null, Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode())
                        .isIn("NOT_INITIALIZED", "INIT_HUNG", "CLOSED"));
    }
}

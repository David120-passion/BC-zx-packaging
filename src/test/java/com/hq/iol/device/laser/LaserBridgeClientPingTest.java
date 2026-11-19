package com.hq.iol.device.laser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 桥接进程通信冒烟测试：ping、错误命令、协议错误、关闭语义。
 * 需要 classpath 上已有 /laser-bridge/LaserBridge.exe（不加载 Ezcad2 SDK，无硬件要求）。
 */
class LaserBridgeClientPingTest {

    private LaserBridgeClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private LaserBridgeClient start() throws Exception {
        Path exe = LaserBridgeBinary.resolve();
        client = new LaserBridgeClient(exe, Path.of(".").toAbsolutePath());
        return client;
    }

    @Test
    void ping返回pong与x86架构() throws Exception {
        start();
        assertThat(client.isAlive()).isTrue();
        var result = client.request("ping", null, Duration.ofSeconds(10));
        assertThat(result.path("pong").asBoolean()).isTrue();
        assertThat(result.path("arch").asText()).isEqualTo("x86");
        assertThat(result.path("initialized").asBoolean()).isFalse();
    }

    @Test
    void 未知命令返回协议错误() throws Exception {
        start();
        assertThatThrownBy(() -> client.request("frobnicate", null, Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("UNKNOWN_COMMAND"));
    }

    @Test
    void 关闭后请求被拒绝且可重复关闭() throws Exception {
        start();
        client.close();
        assertThatThrownBy(() -> client.request("ping", null, Duration.ofSeconds(5)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("CLOSED"));
        client.close(); // 第二次关闭不得抛异常
    }

    @Test
    void 桥接二进制可从classpath提取() throws Exception {
        Path exe = LaserBridgeBinary.resolve();
        assertThat(exe).exists();
        assertThat(java.nio.file.Files.size(exe)).isGreaterThan(0);
    }
}

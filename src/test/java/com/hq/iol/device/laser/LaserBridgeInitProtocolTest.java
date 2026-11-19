package com.hq.iol.device.laser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 桥接进程级的 init 协议验证（真实启动 LaserBridge.exe。SDK 初始化可能成功、失败或超时，
 * 这里验证各种结果都有明确、真实的协议状态，而不是挂死或假装成功）。
 */
class LaserBridgeInitProtocolTest {

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

    @Test
    void init拒绝不存在的SDK目录() throws Exception {
        start();
        var params = LaserBridgeJson.params();
        params.put("sdkPath", "D:\\definitely\\not\\a\\laser\\sdk");
        params.put("testMode", true);
        assertThatThrownBy(() -> client.request("init", params, Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("INVALID_PATH"));
        // 初始化失败后仍可与进程通信
        var ping = client.request("ping", null, Duration.ofSeconds(10));
        assertThat(ping.path("pong").asBoolean()).isTrue();
    }

    @Test
    void init缺失MarkEzdDll的目录报非法路径() throws Exception {
        Path empty = java.nio.file.Files.createTempDirectory("laser-sdk-empty");
        start();
        var params = LaserBridgeJson.params();
        params.put("sdkPath", empty.toAbsolutePath().toString());
        params.put("testMode", true);
        assertThatThrownBy(() -> client.request("init", params, Duration.ofSeconds(10)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("INVALID_PATH"));
    }

    @Test
    void DLL加载失败前能收到诊断日志(@TempDir Path sdk) throws Exception {
        Path dll = Files.writeString(sdk.resolve("MarkEzd.dll"), "not a DLL");
        List<String> diagnostics = new ArrayList<>();
        client = new LaserBridgeClient(LaserBridgeBinary.resolve(), sdk, diagnostics::add);
        var params = LaserBridgeJson.params();
        params.put("sdkPath", sdk.toString());
        params.put("testMode", true);

        assertThatThrownBy(() -> client.request("init", params, Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(LaserException.class, error ->
                        assertThat(error.getCode()).isEqualTo("DLL_LOAD_FAILED"));
        assertThat(diagnostics).containsExactly("LoadLibrary " + dll);
    }

    @Test
    void init真实结果有界且后续命令状态明确() throws Exception {
        Path sdk = Path.of("src", "main", "laser-sdk");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(sdk),
                "src/main/laser-sdk 不存在，跳过真实 SDK init 验证");
        start();
        var params = LaserBridgeJson.params();
        params.put("sdkPath", sdk.toAbsolutePath().toString());
        params.put("testMode", true);
        params.put("initTimeoutMs", 3000);
        try {
            var initialized = client.request("init", params, Duration.ofSeconds(15));
            assertThat(initialized.path("initialized").asBoolean()).isTrue();
            var entities = client.request("entities", null, Duration.ofSeconds(10));
            assertThat(entities.path("count").asInt()).isGreaterThanOrEqualTo(0);
        } catch (LaserException e) {
            assertThat(e.getCode()).isIn("INIT_TIMEOUT", "SDK_ERROR");
            assertThatThrownBy(() -> client.request("entities", null, Duration.ofSeconds(10)))
                    .isInstanceOf(LaserException.class)
                    .satisfies(error -> assertThat(((LaserException) error).getCode())
                            .isIn("INIT_HUNG", "NOT_INITIALIZED", "SDK_ERROR"));
        }
        var closed = client.request("close", null, Duration.ofSeconds(10));
        assertThat(closed.path("closed").asBoolean()).isTrue();
    }
}

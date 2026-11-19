package com.hq.iol.device.laser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 驱动层防护：未初始化/非法路径/非法参数不得触发任何 SDK 调用（不加载 DLL、不出光）。
 */
class EzcadLaserDriverGuardTest {

    private EzcadLaserDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void SDK目录不存在时初始化报非法路径() {
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.initialize(Path.of("no-such-laser-sdk-dir"), true))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("INVALID_PATH"));
        assertThat(driver.isInitialized()).isFalse();
    }

    @Test
    void SDK目录缺少MarkEzdDll时初始化报非法路径() throws Exception {
        Path empty = Files.createTempDirectory("laser-sdk-empty");
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.initialize(empty, true))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("INVALID_PATH"));
    }

    @Test
    void 未初始化时loadTemplate报未初始化() {
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.loadTemplate(Path.of("whatever.ezd")))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("NOT_INITIALIZED"));
    }

    @Test
    void 未初始化时updateText报未初始化() {
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.updateText("TITLE", "x"))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("NOT_INITIALIZED"));
    }

    @Test
    void 未初始化时mark报未初始化且不算在打标() throws LaserException {
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.mark(Duration.ofSeconds(1)))
                .isInstanceOf(LaserException.class)
                .satisfies(e -> assertThat(((LaserException) e).getCode()).isEqualTo("NOT_INITIALIZED"));
        assertThat(driver.isMarking()).isFalse();
        driver.stopMarking(); // 不应抛异常
    }

    @Test
    void 未初始化时textEntities为空列表() {
        driver = new EzcadLaserDriver();
        assertThat(driver.textEntities()).isEmpty();
    }

    @Test
    void updateText拒绝空名称与null文本() {
        driver = new EzcadLaserDriver();
        assertThatThrownBy(() -> driver.updateText("", "x"))
                .isInstanceOf(LaserException.class);
        assertThatThrownBy(() -> driver.updateText("TITLE", null))
                .isInstanceOf(LaserException.class);
    }

    @Test
    void 初始化失败后状态保持未初始化() throws Exception {
        Path empty = Files.createTempDirectory("laser-sdk-empty");
        driver = new EzcadLaserDriver(Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThatThrownBy(() -> driver.initialize(empty, true))
                .isInstanceOf(LaserException.class);
        assertThat(driver.isInitialized()).isFalse();
        // 失败后可重试（这里仍用非法路径验证，不启动进程）
        assertThatThrownBy(() -> driver.initialize(Path.of("still-not-there"), true))
                .isInstanceOf(LaserException.class);
    }
}

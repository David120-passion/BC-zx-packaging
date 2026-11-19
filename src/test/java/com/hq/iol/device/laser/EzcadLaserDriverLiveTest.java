package com.hq.iol.device.laser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 SDK 初始化 + 模板读取验证（testMode=true，绝不真实出光）。
 *
 * <p>本开发机没有安装 LMC1 驱动/控制卡，SDK 初始化按实测会挂起并最终回报
 * INIT_TIMEOUT——这属于环境事实，测试将真实结果打印出来并以结构性断言验证
 * 错误路径。在装有驱动和板卡的机器上同一测试会走完初始化并读取模板实体。</p>
 */
class EzcadLaserDriverLiveTest {

    private static final Path SDK_DIR = Path.of("src", "main", "laser-sdk");
    private static final Path TEMPLATE = Path.of(
            "通讯对接", "对接", "激光打标机开发资料", "Ezcad2开发库", "Ezcad2开发库1",
            "BJJCZ_Ezcad2", "BJJCZ_Ezcad2", "Demo", "C++", "BJJCZ_EZCAD2_Fly_Hatch_Split",
            "EzdFile", "Hatch.ezd");

    private EzcadLaserDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void testMode初始化并读取模板文本对象() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(SDK_DIR),
                "缺少 " + SDK_DIR.toAbsolutePath() + "（SDK 运行文件），跳过实测");

        driver = new EzcadLaserDriver(Duration.ofSeconds(10), Duration.ofSeconds(8));
        try {
            driver.initialize(SDK_DIR.toAbsolutePath(), true);
        } catch (LaserException e) {
            // 无驱动/无板卡环境：初始化失败是真实结果，要求错误被完整报告（非挂死、非伪成功）
            System.out.println("[laser-live] initialize 失败（真实错误）: code=" + e.getCode()
                    + ", sdkCode=" + e.getSdkCode() + ", message=" + e.getMessage());
            assertThat(e.getCode()).isNotBlank();
            assertThat(e.getMessage()).isNotBlank();
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "SDK 初始化未成功（" + e.getCode() + ": " + e.getMessage() + "）——环境可能缺少 LMC1 驱动/板卡");
            return;
        }

        assertThat(driver.isInitialized()).isTrue();
        assertThat(driver.isTestMode()).isTrue();

        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(TEMPLATE),
                "缺少示例模板 " + TEMPLATE.toAbsolutePath());
        List<LaserEntity> entities = driver.loadTemplate(TEMPLATE.toAbsolutePath());
        assertThat(entities).isNotNull();
        System.out.println("[laser-live] 模板实体数: " + entities.size());
        for (LaserEntity e : entities) {
            System.out.println("[laser-live]   #" + e.order() + " " + e.name()
                    + (e.isText() ? " text=" + e.text() : " (非文本)"));
        }
        assertThat(driver.textEntities()).containsExactlyElementsOf(entities);
        assertThat(driver.isMarking()).isFalse();
    }
}

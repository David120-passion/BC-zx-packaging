package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.device.laser.LaserDesign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaserServiceTest {

    private static final String DESIGN = "{\"widthMm\":100,\"heightMm\":80,\"pen\":0,\"elements\":["
            + "{\"id\":\"text_1\",\"type\":\"text\",\"x\":5,\"y\":5,\"widthMm\":50,"
            + "\"heightMm\":8,\"contentMode\":\"variable\",\"variableKey\":\"code\","
            + "\"content\":\"\",\"fontSizeMm\":3,\"rotation\":0}]}";

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;
    private ConfigService config;
    private FakeLaserOperations device;
    private LaserService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("laser.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        config = new ConfigService(jdbc, new DbInitializer(jdbc));
        Path sdk = Files.createDirectories(dir.resolve("sdk"));
        Files.write(sdk.resolve("MarkEzd.dll"), new byte[0]);
        Files.write(sdk.resolve("EZCAD.CFG"), new byte[0]);
        config.saveAll(Map.of(ConfigKeys.LASER_SDK_DIRECTORY, sdk.toString()));
        device = new FakeLaserOperations();
        service = new LaserService(config, () -> device);
    }

    @AfterEach
    void close() {
        service.close();
        dataSource.close();
    }

    @Test
    void 对象预览不需要连接设备() {
        AtomicInteger creations = new AtomicInteger();
        LaserService local = new LaserService(config, () -> {
            creations.incrementAndGet();
            return device;
        });
        try {
            assertThat(local.previewDesign(DESIGN, Map.of("code", "ABC123"))).contains("ABC123");
            assertThat(creations).hasValue(0);
        } finally {
            local.close();
        }
    }

    @Test
    void 每次打标根据变量重新构建对象() throws Exception {
        service.markDesign(DESIGN, Map.of("code", "ABC"), true);
        service.markDesign(DESIGN, Map.of("code", "XYZ"), true);
        assertThat(device.buildCount).isEqualTo(2);
        assertThat(device.designs.get(0).objects().get(0).text()).isEqualTo("ABC");
        assertThat(device.designs.get(1).objects().get(0).text()).isEqualTo("XYZ");
        assertThat(device.markCount).isEqualTo(2);
        assertThat(device.markTimeout).isEqualTo(Duration.ofMillis(120000));
    }

    @Test
    void 切换模式会重新初始化并重建设计() throws Exception {
        service.markDesign(DESIGN, Map.of("code", "ABC"), true);
        service.markDesign(DESIGN, Map.of("code", "ABC"), false);
        assertThat(device.initializeCount).isEqualTo(2);
        assertThat(device.buildCount).isEqualTo(2);
        assertThat(device.testMode).isFalse();
    }

    @Test
    void 对象创建失败时不会开始打标() {
        device.failBuild = true;
        assertThatThrownBy(() -> service.markDesign(DESIGN, Map.of("code", "FAIL"), true))
                .hasMessageContaining("对象创建失败");
        assertThat(device.markCount).isZero();
        assertThat(service.isMarking()).isFalse();
        assertThat(service.isLoaded()).isFalse();
    }

    @Test
    void 打标等待期间可以请求停止() throws Exception {
        device.markEntered = new CountDownLatch(1);
        device.markRelease = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                service.markDesign(DESIGN, Map.of("code", "STOP"), true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        assertThat(device.markEntered.await(2, TimeUnit.SECONDS)).isTrue();
        service.stopMarking();
        assertThat(device.stopCount).isEqualTo(1);
        device.markRelease.countDown();
        worker.join(2000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(service.isMarking()).isFalse();
    }

    @Test
    void 红光指示重建对象且可以停止() throws Exception {
        device.redLightEntered = new CountDownLatch(1);
        device.redLightRelease = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                service.redLightDesign(DESIGN, Map.of("code", "RED"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        assertThat(device.redLightEntered.await(2, TimeUnit.SECONDS)).isTrue();
        service.stopMarking();
        device.redLightRelease.countDown();
        worker.join(2000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(device.redLightCount).isEqualTo(1);
        assertThat(device.markCount).isZero();
        assertThat(device.stopCount).isEqualTo(1);
        assertThat(device.testMode).isFalse();
        assertThat(device.designs.get(0).objects().get(0).text()).isEqualTo("RED");
        assertThat(service.isMarking()).isFalse();
    }

    @Test
    void 非法设计在创建原生桥接之前拒绝() {
        AtomicInteger creations = new AtomicInteger();
        LaserService local = new LaserService(config, () -> {
            creations.incrementAndGet();
            return device;
        });
        try {
            assertThatThrownBy(() -> local.markDesign("{\"widthMm\":100,\"heightMm\":80,\"elements\":[]}",
                    Map.of(), true)).hasMessageContaining("至少");
            assertThat(creations).hasValue(0);
        } finally {
            local.close();
        }
    }

    private static final class FakeLaserOperations implements LaserOperations {
        private boolean testMode;
        private int initializeCount;
        private int buildCount;
        private int markCount;
        private int redLightCount;
        private int stopCount;
        private boolean failBuild;
        private Duration markTimeout;
        private CountDownLatch markEntered;
        private CountDownLatch markRelease;
        private CountDownLatch redLightEntered;
        private CountDownLatch redLightRelease;
        private final List<LaserDesign> designs = new ArrayList<>();

        @Override
        public void initialize(Path sdkDirectory, boolean testMode) {
            this.testMode = testMode;
            initializeCount++;
        }

        @Override
        public void buildDesign(LaserDesign design) {
            if (failBuild) {
                throw new IllegalStateException("对象创建失败");
            }
            designs.add(design);
            buildCount++;
        }

        @Override
        public void mark(Duration timeout) throws Exception {
            markCount++;
            markTimeout = timeout;
            if (markEntered != null) {
                markEntered.countDown();
                assertThat(markRelease.await(2, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Override
        public void redLight() throws Exception {
            redLightCount++;
            if (redLightEntered != null) {
                redLightEntered.countDown();
                assertThat(redLightRelease.await(2, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Override
        public void stopMarking() {
            stopCount++;
        }

        @Override
        public void close() {
        }
    }
}

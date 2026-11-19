package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.device.scale.ScaleProfile;
import com.hq.iol.device.scale.SerialPortInspector;
import com.hq.iol.service.ScaleRegistry;
import com.hq.iol.service.ScaleService;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaleManagerPanelTest {

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 三秤管理与称重选择界面可实际创建() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("scale-ui.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        ConfigService config = new ConfigService(jdbc, initializer);
        ScaleRegistry registry = new ScaleRegistry(config);
        registry.saveAll(List.of(
                new ScaleProfile("a", "A秤", "COM1", "USB-A"),
                new ScaleProfile("b", "B秤", "COM2", "USB-B"),
                new ScaleProfile("c", "C秤", "COM3", "USB-C")), "b");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                UiLogService logs = new UiLogService();
                new ScaleManagerPanel(registry, logs).build();
                var scalePanel = new ScalePanel(new ScaleService(config, registry), config, logs, registry).build();
                @SuppressWarnings("unchecked")
                ComboBox<ScaleProfile> combo = (ComboBox<ScaleProfile>) scalePanel.lookup(".combo-box");
                assertEquals(3, combo.getItems().size());
                assertEquals("b", combo.getValue().id());
                SerialPortInspector.discover();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }
}

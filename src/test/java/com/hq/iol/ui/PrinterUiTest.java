package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.device.printer.PrinterProbe;
import com.hq.iol.device.printer.PrinterProfile;
import com.hq.iol.service.PrinterRegistry;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrinterUiTest {

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
    void 多打印机管理与菜单状态均可实际创建() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("printer-ui.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        ConfigService config = new ConfigService(jdbc, initializer);
        PrinterRegistry registry = new PrinterRegistry(config);
        List<PrinterProfile> profiles = List.of(
                new PrinterProfile("p1", "一号机", "127.0.0.1", 9100),
                new PrinterProfile("p2", "二号机", "127.0.0.2", 9101),
                new PrinterProfile("p3", "三号机", "127.0.0.3", 9102));
        registry.saveAll(profiles, "p1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                UiLogService logs = new UiLogService();
                new PrinterManagerPanel(registry, logs).build();
                SidebarNav nav = new SidebarNav(new Image(getClass().getResourceAsStream("/icon.png")),
                        List.of(new SidebarNav.NavItem("打", "打印测试", "chip-blue")));
                nav.setPrinterStatuses(profiles, Map.of(
                        "p1", new PrinterProbe(true, 0),
                        "p2", new PrinterProbe(true, null),
                        "p3", new PrinterProbe(false, null)));
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

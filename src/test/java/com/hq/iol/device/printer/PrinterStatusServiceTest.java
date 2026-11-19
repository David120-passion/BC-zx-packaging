package com.hq.iol.device.printer;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.service.PrinterRegistry;
import com.hq.iol.service.PrinterStatusService;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterStatusServiceTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private SingleConnectionDataSource dataSource;
    private PrinterStatusService service;

    @BeforeEach
    void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeResources() throws Exception {
        if (service != null) {
            service.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void 状态监控分别返回就绪和无应答() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("status.db"), true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DbInitializer initializer = new DbInitializer(jdbc);
            ConfigService config = new ConfigService(jdbc, initializer);
            config.saveAll(Map.of(ConfigKeys.PRINTER_HOST, "127.0.0.1",
                    ConfigKeys.PRINTER_PORT, String.valueOf(fake.port())));
            service = new PrinterStatusService(new PrinterRegistry(config), vertx);
            AtomicReference<Map<String, PrinterProbe>> notified = new AtomicReference<>();
            service.addListener(notified::set);

            Map<String, PrinterProbe> ready = service.refreshNow();
            assertThat(ready.get("default").tcpConnected()).isTrue();
            assertThat(ready.get("default").status()).isEqualTo(0);
            assertThat(notified.get()).isNotNull();

            fake.setRespond(false);
            Map<String, PrinterProbe> noResponse = service.refreshNow();
            assertThat(noResponse.get("default").tcpConnected()).isTrue();
            assertThat(noResponse.get("default").status()).isNull();
        }
    }
}

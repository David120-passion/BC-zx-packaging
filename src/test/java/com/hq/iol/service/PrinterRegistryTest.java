package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.device.printer.PrinterProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterRegistryTest {

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;
    private ConfigService config;

    private PrinterRegistry registry() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("config.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        config = new ConfigService(jdbc, initializer);
        return new PrinterRegistry(config);
    }

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 旧单打印机配置自动迁移() {
        PrinterRegistry registry = registry();
        config.saveAll(Map.of(ConfigKeys.PRINTER_HOST, "127.0.0.1", ConfigKeys.PRINTER_PORT, "9100"));
        List<PrinterProfile> profiles = registry.list();
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).host()).isEqualTo("127.0.0.1");
        assertThat(profiles.get(0).port()).isEqualTo(9100);
        assertThat(registry.active().id()).isEqualTo("default");
    }

    @Test
    void 多打印机与活动配置持久化() {
        PrinterRegistry registry = registry();
        List<PrinterProfile> profiles = List.of(
                new PrinterProfile("p1", "一号机", "127.0.0.1", 9100),
                new PrinterProfile("p2", "二号机", "192.168.1.20", 9101));
        registry.saveAll(profiles, "p2");
        assertThat(registry.list()).containsExactlyElementsOf(profiles);
        assertThat(registry.active().name()).isEqualTo("二号机");
        assertThat(config.get(ConfigKeys.PRINTER_HOST)).isEqualTo("192.168.1.20");
        assertThat(config.get(ConfigKeys.PRINTER_PORT)).isEqualTo("9101");
        registry.setActive("p1");
        assertThat(registry.active().id()).isEqualTo("p1");
    }

    @Test
    void 最后一台和无效活动配置不能保存() {
        PrinterRegistry registry = registry();
        assertThatThrownBy(() -> registry.saveAll(List.of(), "p1"))
                .isInstanceOf(IllegalArgumentException.class);
        List<PrinterProfile> profiles = List.of(new PrinterProfile("p1", "一号机", "127.0.0.1", 9100));
        assertThatThrownBy(() -> registry.saveAll(profiles, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

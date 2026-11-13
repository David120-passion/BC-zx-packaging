package com.hq.iol.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigServiceTest {

    @TempDir
    Path dir;

    private final java.util.List<SingleConnectionDataSource> dataSources = new java.util.ArrayList<>();

    private ConfigService newService() {
        String url = "jdbc:sqlite:" + dir.resolve("test.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        dataSources.add(ds);
        DbInitializer dbInitializer = new DbInitializer(new JdbcTemplate(ds));
        return new ConfigService(new JdbcTemplate(ds), dbInitializer);
    }

    @org.junit.jupiter.api.AfterEach
    void releaseConnections() {
        dataSources.forEach(SingleConnectionDataSource::close);
    }

    @Test
    void 未入库的键返回默认值() {
        ConfigService cs = newService();
        assertThat(cs.get(ConfigKeys.PRINTER_HOST)).isEqualTo("192.168.1.100");
        assertThat(cs.getInt(ConfigKeys.PRINTER_PORT)).isEqualTo(9100);
    }

    @Test
    void 保存后覆盖默认值并重建后可读回() {
        ConfigService cs = newService();
        cs.saveAll(Map.of(ConfigKeys.PRINTER_HOST, "10.0.0.8"));
        assertThat(cs.get(ConfigKeys.PRINTER_HOST)).isEqualTo("10.0.0.8");
        assertThat(newService().get(ConfigKeys.PRINTER_HOST)).isEqualTo("10.0.0.8");
    }
}

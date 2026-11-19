package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.device.scale.ScaleProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaleRegistryTest {

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;

    private ScaleRegistry registry() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("scale.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        return new ScaleRegistry(new ConfigService(jdbc, initializer));
    }

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 旧单串口自动迁移为电子秤1() {
        ScaleRegistry registry = registry();
        assertThat(registry.list()).hasSize(1);
        assertThat(registry.list().get(0).name()).isEqualTo("电子秤 1");
        assertThat(registry.active().port()).isEqualTo("COM3");
    }

    @Test
    void 三台秤可保存并切换当前设备() {
        ScaleRegistry registry = registry();
        List<ScaleProfile> profiles = List.of(
                new ScaleProfile("a", "A秤", "COM1", "USB-A"),
                new ScaleProfile("b", "B秤", "COM2", "USB-B"),
                new ScaleProfile("c", "C秤", "COM3", "USB-C"));
        registry.saveAll(profiles, "b");
        assertThat(registry.list()).containsExactlyElementsOf(profiles);
        assertThat(registry.active().name()).isEqualTo("B秤");
        registry.setActive("c");
        assertThat(registry.active().port()).isEqualTo("COM3");
    }

    @Test
    void 空列表第四台和无效活动设备被拒绝() {
        ScaleRegistry registry = registry();
        assertThatThrownBy(() -> registry.saveAll(List.of(), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        List<ScaleProfile> four = List.of(
                new ScaleProfile("a", "A", "COM1", ""),
                new ScaleProfile("b", "B", "COM2", ""),
                new ScaleProfile("c", "C", "COM3", ""),
                new ScaleProfile("d", "D", "COM4", ""));
        assertThatThrownBy(() -> registry.saveAll(four, "a"))
                .isInstanceOf(IllegalArgumentException.class);
        List<ScaleProfile> one = List.of(new ScaleProfile("a", "A", "COM1", ""));
        assertThatThrownBy(() -> registry.saveAll(one, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 保存每台秤的地址与波特率() {
        ScaleRegistry registry = registry();
        ScaleProfile profile = new ScaleProfile("a", "A秤", "COM4", "USB", 12, 4800);
        registry.saveAll(List.of(profile), "a");
        assertThat(registry.active().address()).isEqualTo(12);
        assertThat(registry.active().baud()).isEqualTo(4800);
    }

    @Test
    void 旧配置缺少新参数时采用001与9600() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("legacy.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ConfigService config = new ConfigService(jdbc, new DbInitializer(jdbc));
        config.saveAll(java.util.Map.of(ConfigKeys.SCALE_PROFILES,
                "[{\"id\":\"old\",\"name\":\"老秤\",\"port\":\"COM9\",\"description\":\"USB\"}]",
                ConfigKeys.SCALE_ACTIVE, "old"));
        ScaleProfile active = new ScaleRegistry(config).active();
        assertThat(active.address()).isEqualTo(1);
        assertThat(active.baud()).isEqualTo(9600);
    }

    @Test
    void 地址和波特率有明确取值范围() {
        assertThatThrownBy(() -> new ScaleProfile("a", "A", "COM1", "", 256, 9600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("地址");
        assertThatThrownBy(() -> new ScaleProfile("a", "A", "COM1", "", 1, 115200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("波特率");
    }
}

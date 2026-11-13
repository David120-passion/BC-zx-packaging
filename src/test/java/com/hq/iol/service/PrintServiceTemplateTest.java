package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.label.LabelSeed;
import com.hq.iol.label.TemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrintServiceTemplateTest {

    @TempDir
    Path dir;

    private final List<SingleConnectionDataSource> dataSources = new ArrayList<>();

    private PrintService newService() {
        String url = "jdbc:sqlite:" + dir.resolve("t.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        dataSources.add(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DbInitializer init = new DbInitializer(jdbc);
        ConfigService config = new ConfigService(jdbc, init);
        TemplateRepository repo = new TemplateRepository(jdbc, init);
        LabelSeed.seedAll(repo);
        return new PrintService(config, repo);
    }

    @AfterEach
    void releaseConnections() {
        dataSources.forEach(SingleConnectionDataSource::close);
    }

    @Test
    void 模板变量列表合并GS1必需变量() {
        PrintService ps = newService();
        Set<String> vars = ps.templateVariables(LabelSeed.LENS_NAME);
        assertThat(vars).contains("serialNo", "diNo", "sterileDate", "type");
    }

    @Test
    void buildTspl从仓储取模板并渲染() {
        PrintService ps = newService();
        String tspl = ps.buildTspl(LabelSeed.LENS_NAME, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28",
                "serialNo", "2605283500", "type", "AT2000", "power", "+20.0D",
                "totalDia", "12.0", "bodyDia", "6.0"));
        assertThat(tspl).contains("DMATRIX");
        assertThat(tspl).contains("2605283500");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void 模板不存在抛异常() {
        PrintService ps = newService();
        assertThatThrownBy(() -> ps.buildTspl("不存在", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}

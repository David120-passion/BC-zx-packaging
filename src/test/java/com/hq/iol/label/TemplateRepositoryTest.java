package com.hq.iol.label;

import com.hq.iol.config.DbInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRepositoryTest {

    @TempDir
    Path dir;

    private final List<SingleConnectionDataSource> dataSources = new ArrayList<>();

    private TemplateRepository newRepo() {
        String url = "jdbc:sqlite:" + dir.resolve("t.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        dataSources.add(ds);
        DbInitializer init = new DbInitializer(new JdbcTemplate(ds));
        return new TemplateRepository(new JdbcTemplate(ds), init);
    }

    @AfterEach
    void releaseConnections() {
        dataSources.forEach(SingleConnectionDataSource::close);
    }

    @Test
    void 插入后可按名查询且字段完整() {
        TemplateRepository repo = newRepo();
        repo.insert("A线晶体标签", "{\"widthMm\":60}", true);
        LabelTemplate t = repo.findByName("A线晶体标签");
        assertThat(t.name()).isEqualTo("A线晶体标签");
        assertThat(t.designJson()).isEqualTo("{\"widthMm\":60}");
        assertThat(t.builtin()).isTrue();
        assertThat(t.updatedAt()).isNotBlank();
    }

    @Test
    void 更新会刷新设计稿与同名冲突抛错() {
        TemplateRepository repo = newRepo();
        repo.insert("t1", "{}", false);
        repo.update("t1", "{\"v\":2}");
        assertThat(repo.findByName("t1").designJson()).isEqualTo("{\"v\":2}");
        assertThatThrownBy(() -> repo.insert("t1", "{}", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 内置模板禁止删除_seed幂等() {
        TemplateRepository repo = newRepo();
        repo.seed("内置", "{}", true);
        assertThatThrownBy(() -> repo.delete("内置"))
                .isInstanceOf(IllegalStateException.class);
        repo.seed("内置", "{\"changed\":1}", true);
        assertThat(repo.findByName("内置").designJson()).isEqualTo("{}");
        repo.delete("t1_none"); // 不存在也不抛错
    }

    @Test
    void 启动清理旧内置模板但保留用户模板() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dir.resolve("migration.db"), true);
        dataSources.add(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DbInitializer initializer = new DbInitializer(jdbc);
        TemplateRepository repo = new TemplateRepository(jdbc, initializer);
        repo.insert("A线晶体标签", "{}", true);
        repo.insert("用户模板", "{}", false);

        new DbInitializer(jdbc);

        assertThat(repo.findAll()).extracting(LabelTemplate::name).containsExactly("用户模板");
        assertThat(repo.findAll().get(0).builtin()).isFalse();
    }
}

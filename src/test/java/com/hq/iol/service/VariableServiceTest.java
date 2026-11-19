package com.hq.iol.service;

import com.hq.iol.config.DbInitializer;
import com.hq.iol.label.TemplateVariable;
import com.hq.iol.label.VariableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableServiceTest {

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private VariableService service;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("variables.db"), true);
        jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        service = new VariableService(new VariableRepository(jdbc, initializer));
    }

    @AfterEach
    void close() {
        dataSource.close();
    }

    @Test
    void 变量名和备注持久化而运行时值仅在内存中() {
        service.create("serialNo", "产品序列号");
        service.setValue("serialNo", "SN001");
        service.updateRemark("serialNo", "产品唯一序列号");

        VariableService reloaded = new VariableService(new VariableRepository(jdbc,
                new DbInitializer(jdbc)));
        assertThat(reloaded.list()).containsExactly(new TemplateVariable("serialNo", "产品唯一序列号"));
        assertThat(reloaded.get("serialNo")).isEmpty();
        assertThat(service.snapshot()).containsExactlyEntriesOf(Map.of("serialNo", "SN001"));
        assertThat(jdbc.queryForObject("SELECT remark FROM template_variable WHERE var_key='serialNo'",
                String.class)).isEqualTo("产品唯一序列号");
    }

    @Test
    void 运行时值支持批量赋值覆盖和清除() {
        service.setValues(Map.of("type", "PCA81", "power", "+30.0D"));
        assertThat(service.withOverrides(Map.of("power", "+20.0D")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("type", "PCA81", "power", "+20.0D"));
        assertThat(service.snapshot()).containsEntry("power", "+30.0D");
        assertThatThrownBy(() -> service.snapshot().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        service.setValue("type", null);
        assertThat(service.get("type")).isEmpty();
    }

    @Test
    void 拒绝重名和非法变量名并允许删除定义() {
        service.create("serialNo", "");
        assertThatThrownBy(() -> service.create("serialNo", "重复"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("变量已存在");
        assertThatThrownBy(() -> service.create("bad-key", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("变量名");
        assertThatThrownBy(() -> service.create("中文键", ""))
                .isInstanceOf(IllegalArgumentException.class);
        service.setValue("serialNo", "SN001");
        service.delete("serialNo");
        assertThat(service.list()).isEmpty();
        assertThat(service.get("serialNo")).isEmpty();
    }
}

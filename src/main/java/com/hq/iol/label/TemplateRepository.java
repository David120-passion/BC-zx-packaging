package com.hq.iol.label;

import com.hq.iol.config.DbInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 标签模板 CRUD。内置模板（builtin=1）禁止删除；seed 幂等。 */
@org.springframework.context.annotation.Primary
@Repository
public class TemplateRepository {

    private final JdbcTemplate jdbc;
    private final String table;

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateRepository(JdbcTemplate jdbc, DbInitializer initializer) {
        this(jdbc, initializer, "label_template");
    }

    public TemplateRepository(JdbcTemplate jdbc, DbInitializer initializer, String table) {
        if (!java.util.Set.of("label_template", "laser_template").contains(table)) {
            throw new IllegalArgumentException("不支持的模板表: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
    }

    public List<LabelTemplate> findAll() {
        return jdbc.query("SELECT id,name,design_json,builtin,updated_at FROM "+table+" ORDER BY id",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)));
    }

    public LabelTemplate findByName(String name) {
        List<LabelTemplate> list = jdbc.query(
                "SELECT id,name,design_json,builtin,updated_at FROM "+table+" WHERE name=?",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)), name);
        if (list.isEmpty()) {
            throw new IllegalStateException("模板不存在: " + name);
        }
        return list.get(0);
    }

    public void insert(String name, String designJson, boolean builtin) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE name=?",
                Integer.class, name) > 0) {
            throw new IllegalStateException("模板已存在: " + name);
        }
        jdbc.update("INSERT INTO "+table+"(name,design_json,builtin,updated_at) VALUES(?,?,?,?)",
                name, designJson, builtin ? 1 : 0, Instant.now().toString());
    }

    public void update(String name, String designJson) {
        int n = jdbc.update("UPDATE "+table+" SET design_json=?, updated_at=? WHERE name=?",
                designJson, Instant.now().toString(), name);
        if (n == 0) {
            throw new IllegalStateException("模板不存在: " + name);
        }
    }

    /** 同名模板已存在则拒绝（含内置模板，内置模板内容升级用迁移而非 seed）。 */
    public void seed(String name, String designJson, boolean builtin) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE name=?",
                Integer.class, name) == 0) {
            jdbc.update("INSERT INTO "+table+"(name,design_json,builtin,updated_at) VALUES(?,?,?,?)",
                    name, designJson, builtin ? 1 : 0, Instant.now().toString());
        }
    }

    public void delete(String name) {
        LabelTemplate t = findByNameOrNull(name);
        if (t == null) {
            return;
        }
        if (t.builtin()) {
            throw new IllegalStateException("内置模板不可删除: " + name);
        }
        jdbc.update("DELETE FROM "+table+" WHERE name=?", name);
    }

    private LabelTemplate findByNameOrNull(String name) {
        List<LabelTemplate> list = jdbc.query(
                "SELECT id,name,design_json,builtin,updated_at FROM "+table+" WHERE name=?",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)), name);
        return list.isEmpty() ? null : list.get(0);
    }
}

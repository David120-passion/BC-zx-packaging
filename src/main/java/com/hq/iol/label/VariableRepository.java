package com.hq.iol.label;

import com.hq.iol.config.DbInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class VariableRepository {

    private final JdbcTemplate jdbc;

    public VariableRepository(JdbcTemplate jdbc, DbInitializer initializer) {
        this.jdbc = jdbc;
    }

    public List<TemplateVariable> findAll() {
        return jdbc.query("SELECT var_key,remark FROM template_variable ORDER BY var_key COLLATE NOCASE",
                (rs, rowNum) -> new TemplateVariable(rs.getString(1), rs.getString(2)));
    }

    public boolean exists(String key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM template_variable WHERE var_key=?",
                Integer.class, key);
        return count != null && count > 0;
    }

    public void insert(TemplateVariable variable) {
        if (exists(variable.key())) {
            throw new IllegalStateException("变量已存在: " + variable.key());
        }
        jdbc.update("INSERT INTO template_variable(var_key,remark,updated_at) VALUES(?,?,?)",
                variable.key(), variable.remark(), Instant.now().toString());
    }

    public void updateRemark(String key, String remark) {
        int updated = jdbc.update("UPDATE template_variable SET remark=?,updated_at=? WHERE var_key=?",
                remark, Instant.now().toString(), key);
        if (updated == 0) {
            throw new IllegalStateException("变量不存在: " + key);
        }
    }

    public void delete(String key) {
        int deleted = jdbc.update("DELETE FROM template_variable WHERE var_key=?", key);
        if (deleted == 0) {
            throw new IllegalStateException("变量不存在: " + key);
        }
    }
}

package com.hq.iol.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 建表与旧内置标签清理先于其他 Bean：ConfigService 以构造器注入本类保证顺序。 */
@Component
public class DbInitializer {

    public DbInitializer(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_config (cfg_key TEXT PRIMARY KEY, cfg_value TEXT)");
        createTemplateTable(jdbc, "label_template");
        jdbc.update("DELETE FROM label_template WHERE builtin=1");
        createTemplateTable(jdbc, "laser_template");
        jdbc.execute("CREATE TABLE IF NOT EXISTS template_variable ("
                + "var_key TEXT PRIMARY KEY,"
                + "remark TEXT NOT NULL DEFAULT '',"
                + "updated_at TEXT NOT NULL)");
    }

    private static void createTemplateTable(JdbcTemplate jdbc, String table) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT UNIQUE NOT NULL,"
                + "design_json TEXT NOT NULL,"
                + "builtin INTEGER NOT NULL DEFAULT 0,"
                + "updated_at TEXT NOT NULL)");
    }
}

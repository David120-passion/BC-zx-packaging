package com.hq.iol.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 建表先于其他 Bean：ConfigService 以构造器注入本类保证顺序。 */
@Component
public class DbInitializer {

    public DbInitializer(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_config (cfg_key TEXT PRIMARY KEY, cfg_value TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS label_template ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT UNIQUE NOT NULL,"
                + "design_json TEXT NOT NULL,"
                + "builtin INTEGER NOT NULL DEFAULT 0,"
                + "updated_at TEXT NOT NULL)");
    }
}

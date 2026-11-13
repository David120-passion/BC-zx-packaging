package com.hq.iol.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SQLite kv 配置中心：启动全量加载进内存，键不存在回退 ConfigKeys.DEFAULTS。 */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public ConfigService(JdbcTemplate jdbc, DbInitializer initializer) {
        this.jdbc = jdbc;
        jdbc.query("SELECT cfg_key, cfg_value FROM app_config",
                (RowCallbackHandler) rs -> cache.put(rs.getString(1), rs.getString(2)));
    }

    public String get(String key) {
        String v = cache.get(key);
        return v != null ? v : ConfigKeys.DEFAULTS.get(key);
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    @Transactional
    public void saveAll(Map<String, String> entries) {
        entries.forEach((k, v) -> {
            jdbc.update("INSERT INTO app_config(cfg_key, cfg_value) VALUES(?,?) "
                    + "ON CONFLICT(cfg_key) DO UPDATE SET cfg_value=excluded.cfg_value", k, v);
            cache.put(k, v);
        });
    }
}

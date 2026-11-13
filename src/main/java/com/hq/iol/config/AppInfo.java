package com.hq.iol.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class AppInfo {
    private static final String VERSION = loadVersion();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    static String normalizeVersion(String value) {
        if (value == null || value.isBlank() || value.contains("@") || value.contains("$")) {
            return "dev";
        }
        return value.trim();
    }

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return normalizeVersion(properties.getProperty("app.version"));
        } catch (java.io.IOException e) {
            return "dev";
        }
    }
}

package com.hq.iol.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配置键常量与默认值。键名风格：设备.参数。 */
public final class ConfigKeys {

    public static final String PRINTER_HOST = "printer.host";
    public static final String PRINTER_PORT = "printer.port";
    public static final String PRINTER_DPI = "printer.dpi";
    public static final String PRINTER_PROFILES = "printer.profiles.json";
    public static final String PRINTER_ACTIVE = "printer.active";
    public static final String SCALE_PORT = "scale.port";
    public static final String SCALE_PROFILES = "scale.profiles.json";
    public static final String SCALE_ACTIVE = "scale.active";
    public static final String SCALE_READ_TIMEOUT_MS = "scale.readTimeoutMs";
    public static final String LASER_SDK_DIRECTORY = "laser.sdkDirectory";
    public static final String LASER_TEMPLATE = "laser.template";
    public static final String LASER_TEST_MODE = "laser.testMode";
    public static final String LASER_MARK_TIMEOUT_MS = "laser.markTimeoutMs";

    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(PRINTER_HOST, "192.168.1.100");
        DEFAULTS.put(PRINTER_PORT, "9100");
        DEFAULTS.put(PRINTER_DPI, "600"); // 旧模板未设置 DPI 时的回退值
        DEFAULTS.put(PRINTER_PROFILES, "");
        DEFAULTS.put(PRINTER_ACTIVE, "default");
        DEFAULTS.put(SCALE_PORT, "COM3");
        DEFAULTS.put(SCALE_PROFILES, "");
        DEFAULTS.put(SCALE_ACTIVE, "default");
        DEFAULTS.put(SCALE_READ_TIMEOUT_MS, "3000");
        DEFAULTS.put(LASER_SDK_DIRECTORY, "");
        DEFAULTS.put(LASER_TEMPLATE, "");
        DEFAULTS.put(LASER_TEST_MODE, "true");
        DEFAULTS.put(LASER_MARK_TIMEOUT_MS, "120000");
    }

    private ConfigKeys() {
    }
}

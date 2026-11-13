package com.hq.iol.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配置键常量与默认值。键名风格：设备.参数。 */
public final class ConfigKeys {

    public static final String PRINTER_HOST = "printer.host";
    public static final String PRINTER_PORT = "printer.port";
    public static final String PRINTER_DPI = "printer.dpi";
    public static final String SCALE_PORT = "scale.port";
    public static final String SCALE_READ_TIMEOUT_MS = "scale.readTimeoutMs";

    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(PRINTER_HOST, "192.168.1.100");
        DEFAULTS.put(PRINTER_PORT, "9100");
        DEFAULTS.put(PRINTER_DPI, "600"); // MH641 为 600 DPI
        DEFAULTS.put(SCALE_PORT, "COM3");
        DEFAULTS.put(SCALE_READ_TIMEOUT_MS, "3000");
    }

    private ConfigKeys() {
    }
}

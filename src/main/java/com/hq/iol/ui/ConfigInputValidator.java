package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;

import java.util.Optional;

final class ConfigInputValidator {

    private ConfigInputValidator() {
    }

    static Optional<String> error(String key, String raw) {
        if (!ConfigKeys.PRINTER_PORT.equals(key)
                && !ConfigKeys.PRINTER_DPI.equals(key)
                && !ConfigKeys.SCALE_READ_TIMEOUT_MS.equals(key)) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            if (ConfigKeys.PRINTER_PORT.equals(key) && (value < 1 || value > 65535)) {
                return Optional.of("端口须为 1–65535 的整数");
            }
            if (!ConfigKeys.PRINTER_PORT.equals(key) && value <= 0) {
                return Optional.of(ConfigKeys.PRINTER_DPI.equals(key)
                        ? "DPI 须为正整数" : "读取超时须为正整数");
            }
            return Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.of("请输入有效整数");
        }
    }
}

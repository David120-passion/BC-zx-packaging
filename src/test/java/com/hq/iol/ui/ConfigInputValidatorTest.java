package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigInputValidatorTest {

    @Test
    void validatesPrinterPortRange() {
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "0").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "65536").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "abc").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "9100").isEmpty());
    }

    @Test
    void validatesPositiveDpiAndTimeout() {
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_DPI, "-1").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_DPI, "600").isEmpty());
        assertTrue(ConfigInputValidator.error(ConfigKeys.SCALE_READ_TIMEOUT_MS, "0").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.SCALE_READ_TIMEOUT_MS, "3000").isEmpty());
        assertTrue(ConfigInputValidator.error(ConfigKeys.SCALE_PORT, "COM3").isEmpty());
    }
}

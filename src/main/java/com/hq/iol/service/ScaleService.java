package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.scale.EkiScaleDriver;
import com.hq.iol.device.scale.ScaleOverloadException;
import com.hq.iol.device.scale.ScaleTimeoutException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

/** 称重业务编排：短连接模式，读一次开一次秤（触发式称重，频率低）。 */
@Service
public class ScaleService {

    private final ConfigService config;

    public ScaleService(ConfigService config) {
        this.config = config;
    }

    public BigDecimal readStableWeight()
            throws IOException, ScaleOverloadException, ScaleTimeoutException {
        try (EkiScaleDriver scale = EkiScaleDriver.open(config.get(ConfigKeys.SCALE_PORT))) {
            return scale.readStableGrams(
                    Duration.ofMillis(config.getInt(ConfigKeys.SCALE_READ_TIMEOUT_MS)));
        }
    }

    public void tare() throws IOException {
        try (EkiScaleDriver scale = EkiScaleDriver.open(config.get(ConfigKeys.SCALE_PORT))) {
            // Task 8 修复轮后签名为 zero(Duration)：清零同样受有界读约束
            scale.zero(Duration.ofMillis(config.getInt(ConfigKeys.SCALE_READ_TIMEOUT_MS)));
        }
    }
}

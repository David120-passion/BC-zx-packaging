package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.scale.KxqyScaleDriver;
import com.hq.iol.device.scale.ScaleDeviceException;
import com.hq.iol.device.scale.ScaleProfile;
import com.hq.iol.device.scale.ScaleProtocolException;
import com.hq.iol.device.scale.ScaleReading;
import com.hq.iol.device.scale.ScaleTimeoutException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

@Service
public class ScaleService {

    private final ConfigService config;
    private final ScaleRegistry scales;

    public ScaleService(ConfigService config, ScaleRegistry scales) {
        this.config = config;
        this.scales = scales;
    }

    public BigDecimal readStableWeight()
            throws IOException, ScaleProtocolException, ScaleTimeoutException, ScaleDeviceException {
        return readStableReading().value();
    }

    public ScaleReading readStableReading()
            throws IOException, ScaleProtocolException, ScaleTimeoutException, ScaleDeviceException {
        try (KxqyScaleDriver scale = KxqyScaleDriver.open(scales.active())) {
            return scale.readStableReading(timeout());
        }
    }

    public ScaleReading readCurrentFrame()
            throws IOException, ScaleProtocolException, ScaleTimeoutException, ScaleDeviceException {
        try (KxqyScaleDriver scale = KxqyScaleDriver.open(scales.active())) {
            ScaleReading reading = scale.readCurrentFrame(timeout());
            KxqyScaleDriver.validateReading(reading);
            return reading;
        }
    }

    public Optional<ScaleReading> tare() throws IOException {
        return adjust("去皮", KxqyScaleDriver::tare);
    }

    public Optional<ScaleReading> zero() throws IOException {
        return adjust("置零", KxqyScaleDriver::zero);
    }

    private Optional<ScaleReading> adjust(String operation, ScaleCommand command) throws IOException {
        ScaleProfile profile = scales.active();
        try (KxqyScaleDriver scale = KxqyScaleDriver.open(profile)) {
            try {
                command.send(scale);
            } catch (IOException ex) {
                throw new IOException(operation + "发送阶段异常：" + ex.getMessage(), ex);
            }
            if (profile.address() == 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(scale.readAfterAdjustment(timeout()));
            } catch (IOException | ScaleProtocolException | ScaleTimeoutException ex) {
                throw new IOException(operation + "发送队列已排空，回读失败：" + ex.getMessage(), ex);
            }
        }
    }

    private Duration timeout() {
        return Duration.ofMillis(config.getInt(ConfigKeys.SCALE_READ_TIMEOUT_MS));
    }

    @FunctionalInterface
    private interface ScaleCommand {
        void send(KxqyScaleDriver scale) throws IOException;
    }
}

package com.hq.iol.device.scale;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaleReadingTest {

    @Test
    void 解析完整稳定重量数据组() throws Exception {
        ScaleReading reading = ScaleReading.parse(frame("STEADY", "WT:12.50g"));
        assertThat(reading.status()).isEqualTo("STEADY");
        assertThat(reading.value()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(reading.unit()).isEqualTo("g");
        assertThat(reading.mode()).isEqualTo("NORMAL");
        assertThat(reading.fields()).containsEntry("TYPE", "6002");
        assertThat(reading.isStable()).isTrue();
        assertThat(reading.isGram()).isTrue();
    }

    @Test
    void 支持负数与不稳定状态() throws Exception {
        ScaleReading reading = ScaleReading.parse(frame("UNSTEADY", "WT:-0.025g"));
        assertThat(reading.value()).isEqualByComparingTo(new BigDecimal("-0.025"));
        assertThat(reading.isStable()).isFalse();
        assertThat(reading.hasDeviceError()).isFalse();
    }

    @Test
    void 区分超载欠载与设备错误() throws Exception {
        assertThat(ScaleReading.parse(frame("OVERLOAD", "WT:999.9g")).statusDescription())
                .isEqualTo("超载");
        assertThat(ScaleReading.parse(frame("UNDERLOAD", "WT:-999.9g")).hasDeviceError()).isTrue();
        assertThat(ScaleReading.parse(frame("ERROR1", "WT:0.00g")).statusDescription())
                .isEqualTo("AD 错误");
    }

    @Test
    void 缺重量或结束行时抛出协议异常() {
        assertThatThrownBy(() -> ScaleReading.parse("STATUS:STEADY\r\n----COMPLETE----\r\n"))
                .isInstanceOf(ScaleProtocolException.class)
                .hasMessageContaining("WT");
        assertThatThrownBy(() -> ScaleReading.parse("STATUS:STEADY\r\nWT:0.00g\r\n"))
                .isInstanceOf(ScaleProtocolException.class)
                .hasMessageContaining("COMPLETE");
    }

    @Test
    void 非法状态和重量不会被当成零克() {
        assertThatThrownBy(() -> ScaleReading.parse(frame("UNKNOWN", "WT:0.00g")))
                .isInstanceOf(ScaleProtocolException.class);
        assertThatThrownBy(() -> ScaleReading.parse(frame("STEADY", "WT:abc")))
                .isInstanceOf(ScaleProtocolException.class);
        assertThatThrownBy(() -> ScaleReading.parse("GARBAGE"))
                .isInstanceOf(ScaleProtocolException.class);
    }

    static String frame(String status, String weight) {
        return "TYPE:6002\r\nID:1\r\nMODE:NORMAL\r\nSTATUS:" + status
                + "\r\n" + weight + "\r\n----COMPLETE----\r\n";
    }
}

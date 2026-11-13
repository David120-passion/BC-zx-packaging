package com.hq.iol.device.scale;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaleReadingTest {

    @Test
    void 解析稳定重量帧() throws Exception {
        ScaleReading r = ScaleReading.parse("ST,+001234.5   g\r\n");
        assertThat(r.status()).isEqualTo("ST");
        assertThat(r.value()).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(r.unit()).isEqualTo("g");
    }

    @Test
    void 解析负数与超载帧() throws Exception {
        assertThat(ScaleReading.parse("US,-000012.3   g\r\n").status()).isEqualTo("US");
        assertThat(ScaleReading.parse("OL,+999999.9   g\r\n").status()).isEqualTo("OL");
    }

    @Test
    void 小数点被配置为逗号时归一化() throws Exception {
        ScaleReading r = ScaleReading.parse("ST,+001234,5   g\r\n");
        assertThat(r.value()).isEqualByComparingTo(new BigDecimal("1234.5"));
    }

    @Test
    void 帧长度或格式非法抛协议异常() {
        assertThatThrownBy(() -> ScaleReading.parse("GARBAGE"))
                .isInstanceOf(ScaleProtocolException.class);
        assertThatThrownBy(() -> ScaleReading.parse("XX,+001234.5   g\r\n"))
                .isInstanceOf(ScaleProtocolException.class);
    }
}

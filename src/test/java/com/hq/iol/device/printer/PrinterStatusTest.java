package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterStatusTest {

    @Test
    void 仅零状态为就绪() {
        assertThat(PrinterStatus.isReady(0x00)).isTrue();
        assertThat(PrinterStatus.isReady(0x20)).isFalse();
    }

    @Test
    void 手册P82状态表逐项描述() {
        assertThat(PrinterStatus.describe(0x00)).isEqualTo("就绪");
        assertThat(PrinterStatus.describe(0x01)).isEqualTo("打印头开启");
        assertThat(PrinterStatus.describe(0x02)).isEqualTo("卡纸");
        assertThat(PrinterStatus.describe(0x03)).isEqualTo("卡纸且打印头开启");
        assertThat(PrinterStatus.describe(0x04)).isEqualTo("缺纸");
        assertThat(PrinterStatus.describe(0x05)).isEqualTo("缺纸且打印头开启");
        assertThat(PrinterStatus.describe(0x08)).isEqualTo("缺碳带");
        assertThat(PrinterStatus.describe(0x09)).isEqualTo("缺碳带且打印头开启");
        assertThat(PrinterStatus.describe(0x0A)).isEqualTo("缺碳带且卡纸");
        assertThat(PrinterStatus.describe(0x0B)).isEqualTo("缺碳带、卡纸且打印头开启");
        assertThat(PrinterStatus.describe(0x0C)).isEqualTo("碳带用尽");
        assertThat(PrinterStatus.describe(0x0D)).isEqualTo("碳带用尽且打印头开启");
        assertThat(PrinterStatus.describe(0x10)).isEqualTo("暂停");
        assertThat(PrinterStatus.describe(0x20)).isEqualTo("打印中");
        assertThat(PrinterStatus.describe(0x80)).isEqualTo("其他错误");
    }

    @Test
    void 未知状态给出十六进制描述() {
        assertThat(PrinterStatus.describe(0x40)).isEqualTo("未知状态 0x40");
    }
}

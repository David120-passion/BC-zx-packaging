package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TscPrinterDriverTest {

    @Test
    void 状态就绪时下发打印数据() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            d.print("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n");
            assertThat(fake.received())
                    .isEqualTo("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 状态非就绪时拒绝下发并抛异常() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setStatus(0x04);
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            assertThatThrownBy(() -> d.print("PRINT 1\r\n"))
                    .isInstanceOf(TscPrinterDriver.PrinterNotReadyException.class)
                    .hasMessageContaining("缺纸");
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void 查询状态返回原始字节() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setStatus(0x10);
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            assertThat(d.queryStatus()).isEqualTo(0x10);
        }
    }

    @Test
    void 连接不上抛IOException() {
        TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", 1, 300);
        assertThatThrownBy(() -> d.print("PRINT 1\r\n")).isInstanceOf(IOException.class);
    }
}

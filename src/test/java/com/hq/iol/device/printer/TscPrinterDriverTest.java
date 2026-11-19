package com.hq.iol.device.printer;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TscPrinterDriverTest {

    private Vertx vertx;

    @BeforeEach
    void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void 状态就绪时下发打印数据() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            d.print("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n");
            assertThat(fake.received())
                    .isEqualTo("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 位图按真实二进制数据下发() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            d.print("BITMAP 0,0,3,1,0,80FF01\r\nPRINT 1,1\r\n");
            java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
            expected.write("BITMAP 0,0,3,1,0,".getBytes(StandardCharsets.UTF_8));
            expected.write(0x80);
            expected.write(0xFF);
            expected.write(0x01);
            expected.write("\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.UTF_8));
            assertThat(fake.received()).isEqualTo(expected.toByteArray());
        }
    }

    @Test
    void 状态非就绪时拒绝下发并抛异常() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setStatus(0x04);
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
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
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            assertThat(d.queryStatus()).isEqualTo(0x10);
        }
    }

    @Test
    void 无状态应答时抛出IOException() {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setRespond(false);
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            assertThatThrownBy(d::queryStatus)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("状态");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 探测返回就绪状态() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            PrinterProbe probe = d.probe();
            assertThat(probe.tcpConnected()).isTrue();
            assertThat(probe.status()).isEqualTo(0);
        }
    }

    @Test
    void 无应答仍属于Tcp已连接() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setRespond(false);
            TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", fake.port(), 1000);
            PrinterProbe probe = d.probe();
            assertThat(probe.tcpConnected()).isTrue();
            assertThat(probe.status()).isNull();
        }
    }

    @Test
    void 连接失败时探测为离线() {
        TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", 1, 300);
        PrinterProbe probe = d.probe();
        assertThat(probe.tcpConnected()).isFalse();
        assertThat(probe.status()).isNull();
    }

    @Test
    void 连接不上抛IOException() {
        TscPrinterDriver d = new TscPrinterDriver(vertx, "127.0.0.1", 1, 300);
        assertThatThrownBy(() -> d.print("PRINT 1\r\n")).isInstanceOf(IOException.class);
    }
}

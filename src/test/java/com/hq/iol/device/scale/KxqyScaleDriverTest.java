package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KxqyScaleDriverTest {

    @Test
    void 查询使用三位地址和P且不追加回车换行() throws Exception {
        SerialPort port = portReturning(ScaleReadingTest.frame("STEADY", "WT:12.50g"));
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 1)) {
            ScaleReading reading = driver.readCurrentFrame(Duration.ofSeconds(1));
            assertThat(reading.value()).isEqualByComparingTo("12.50");
        }
        verify(port).writeBytes(argThat(bytes ->
                java.util.Arrays.equals(bytes, "001P".getBytes(StandardCharsets.US_ASCII))), anyInt());
    }

    @Test
    void 稳定读取跳过不稳定数据组() throws Exception {
        SerialPort port = portReturning(
                ScaleReadingTest.frame("UNSTEADY", "WT:12.40g"),
                ScaleReadingTest.frame("STEADY", "WT:12.50g"));
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 8)) {
            assertThat(driver.readStableGrams(Duration.ofSeconds(1))).isEqualByComparingTo("12.50");
        }
        verify(port, times(2)).writeBytes(argThat(bytes ->
                java.util.Arrays.equals(bytes, "008P".getBytes(StandardCharsets.US_ASCII))), anyInt());
    }

    @Test
    void 连续输出从完整数据组读取匹配地址的重量() throws Exception {
        SerialPort port = portReturning(
                "STATUS:STEADY\r\nWT:99.00g\r\n"
                        + "TYPE:40002\r\nID:2\r\nSTATUS:STEADY\r\nWT:88.00g\r\n"
                        + "TYPE:40002\r\nID:1\r\nSTATUS:STEADY\r\n",
                "TYPE:40002\r\nID:1\r\nMODE:NORMAL\r\nSTATUS:STEADY\r\nWT:12.50g\r\nTY",
                "PE:40002\r\n");
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 1)) {
            ScaleReading reading = driver.readStableReading(Duration.ofSeconds(1));
            assertThat(reading.value()).isEqualByComparingTo("12.50");
            assertThat(reading.mode()).isEqualTo("NORMAL");
        }
    }

    @Test
    void 去皮和置零使用不同命令() throws Exception {
        SerialPort port = portReturning();
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 2)) {
            driver.tare();
            verify(port).writeBytes("002T".getBytes(StandardCharsets.US_ASCII), 4);
            driver.zero();
            verify(port).writeBytes("002Z".getBytes(StandardCharsets.US_ASCII), 4);
        }
    }

    @Test
    void 广播地址禁止读取但允许去皮() throws Exception {
        SerialPort port = portReturning();
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 0)) {
            assertThatThrownBy(() -> driver.readCurrentFrame(Duration.ofMillis(10)))
                    .isInstanceOf(IOException.class).hasMessageContaining("广播");
            driver.tare();
            verify(port).writeBytes("000T".getBytes(StandardCharsets.US_ASCII), 4);
        }
    }

    @Test
    void 去皮后等发送队列排空并跳过积压数据再回读() throws Exception {
        String stale = ScaleReadingTest.frame("STEADY", "WT:-11.66g");
        SerialPort port = portReturning(stale, ScaleReadingTest.frame("STEADY", "WT:0.00g"));
        when(port.bytesAwaitingWrite()).thenReturn(4, 2, 0);
        when(port.bytesAvailable()).thenReturn(stale.length());
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 1)) {
            driver.tare();
            assertThat(driver.readAfterAdjustment(Duration.ofSeconds(1)).value()).isZero();
        }
        InOrder order = inOrder(port);
        order.verify(port).writeBytes("001T".getBytes(StandardCharsets.US_ASCII), 4);
        order.verify(port, times(3)).bytesAwaitingWrite();
        order.verify(port).readBytes(any(byte[].class), eq(stale.length()));
        order.verify(port).writeBytes("001P".getBytes(StandardCharsets.US_ASCII), 4);
        order.verify(port).closePort();
    }

    @Test
    void 置零无法检查发送队列时报告异常() {
        SerialPort port = portReturning();
        when(port.bytesAwaitingWrite()).thenReturn(-1);
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(port, 1)) {
            assertThatThrownBy(driver::zero).isInstanceOf(IOException.class)
                    .hasMessageContaining("无法检查串口发送队列");
        }
        verify(port).writeBytes("001Z".getBytes(StandardCharsets.US_ASCII), 4);
    }

    @Test
    void 设备异常和非克单位不会被返回为正常重量() {
        SerialPort error = portReturning(ScaleReadingTest.frame("OVERLOAD", "WT:99.0g"));
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(error, 1)) {
            assertThatThrownBy(() -> driver.readStableReading(Duration.ofSeconds(1)))
                    .isInstanceOf(ScaleDeviceException.class).hasMessageContaining("超载");
        }
        SerialPort unit = portReturning(ScaleReadingTest.frame("STEADY", "WT:0.0125kg"));
        try (KxqyScaleDriver driver = KxqyScaleDriver.fromPort(unit, 1)) {
            assertThatThrownBy(() -> driver.readStableReading(Duration.ofSeconds(1)))
                    .isInstanceOf(IOException.class).hasMessageContaining("单位");
        }
    }

    private static SerialPort portReturning(String... frames) {
        SerialPort port = mock(SerialPort.class);
        when(port.writeBytes(any(byte[].class), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        Iterator<byte[]> responses = List.of(frames).stream()
                .map(frame -> frame.getBytes(StandardCharsets.US_ASCII)).iterator();
        when(port.readBytes(any(byte[].class), anyInt())).thenAnswer(invocation -> {
            if (!responses.hasNext()) {
                return 0;
            }
            byte[] bytes = responses.next();
            System.arraycopy(bytes, 0, invocation.getArgument(0), 0, bytes.length);
            return bytes.length;
        });
        return port;
    }
}

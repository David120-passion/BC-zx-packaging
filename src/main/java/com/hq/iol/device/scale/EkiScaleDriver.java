package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * A&D EK-i / EW-i 系列电子秤，RS-232C 指令模式驱动。
 * 协议见《EK-i 电子秤》手册 9.2 / 9.4 节。非线程安全：每个物理秤一个实例，单线程独占。
 */
public final class EkiScaleDriver implements AutoCloseable {

    /** 单位字段允许值，本项目只接受克。 */
    private static final String UNIT_GRAM = "g";

    private final SerialPort port;
    private final BufferedReader in;
    private final OutputStream out;

    private EkiScaleDriver(SerialPort port) throws IOException {
        this.port = port;
        this.in = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.US_ASCII));
        this.out = port.getOutputStream();
    }

    /**
     * 打开串口。参数须与秤上功能项一致：bPS=2 (9600)、btPr=2 (8N1)。
     *
     * @param portName Windows 为 "COM3"，Linux 为 "/dev/ttyUSB0"
     */
    public static EkiScaleDriver open(String portName) throws IOException {
        SerialPort p = SerialPort.getCommPort(portName);
        p.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED); // 7/8 脚内部短接，无流控
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 1500);
        if (!p.openPort()) {
            throw new IOException("串口打开失败: " + portName);
        }
        return new EkiScaleDriver(p);
    }

    /**
     * 读取一个稳定重量值。内部轮询，跳过 US（不稳定）帧。
     *
     * @param timeout 稳定等待上限，建议 3 秒
     * @throws ScaleOverloadException 秤返回 OL（超载）
     * @throws ScaleTimeoutException 超时仍未稳定
     */
    public BigDecimal readStableGrams(Duration timeout)
            throws IOException, ScaleOverloadException, ScaleTimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ScaleReading r = query();
            if (r == null) {
                continue; // 读超时，重发 Q
            }
            switch (r.status()) {
                case "ST", "QT" -> {
                    if (!UNIT_GRAM.equals(r.unit())) {
                        throw new IOException("秤单位非克，当前为: " + r.unit()
                                + "（检查是否被误按 MODE 键）");
                    }
                    return r.value();
                }
                case "OL" -> throw new ScaleOverloadException(r.value());
                case "US" -> { /* 不稳定，继续轮询 */ }
                default -> throw new IOException("未知状态头: " + r.status());
            }
        }
        throw new ScaleTimeoutException(timeout);
    }

    /** 去皮 / 清零，等同按 RE-ZERO 键。 */
    public void zero() throws IOException {
        send("Z");
        String ack = in.readLine();
        if (ack == null || !"Z".equals(ack.trim())) {
            throw new IOException("清零指令未确认，收到: " + ack);
        }
    }

    /** 发一次 Q 指令并解析应答；读超时返回 null。 */
    private ScaleReading query() throws IOException {
        send("Q");
        String line = in.readLine();
        if (line == null) {
            return null;
        }
        try {
            return ScaleReading.parse(line);
        } catch (ScaleProtocolException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void send(String cmd) throws IOException {
        out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() {
        port.closePort();
    }
}

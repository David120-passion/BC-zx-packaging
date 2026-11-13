package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * A&D EK-i / EW-i 系列电子秤，RS-232C 指令模式驱动。
 * 协议见《EK-i 电子秤》手册 9.2 / 9.4 节。非线程安全：每个物理秤一个实例，单线程独占。
 *
 * 所有读操作均为有界读：底层按 200ms 短超时轮询 readBytes 并自行按字节累积成帧，
 * 每轮检查 deadline，静默线路（秤断电/断线/无应答）不会挂死，超时上限可预期。
 */
public final class EkiScaleDriver implements AutoCloseable {

    /** 单次原生读超时（毫秒）。小粒度保证 deadline 检查及时，超时溢出 ≤ 该值。 */
    private static final int READ_CHUNK_TIMEOUT_MS = 200;

    /** 单位字段允许值，本项目只接受克。 */
    private static final String UNIT_GRAM = "g";

    private final SerialPort port;
    private final LineBuffer buffer = new LineBuffer();

    private EkiScaleDriver(SerialPort port) {
        this.port = port;
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
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_CHUNK_TIMEOUT_MS, 1500);
        if (!p.openPort()) {
            throw new IOException("串口打开失败: " + portName);
        }
        return new EkiScaleDriver(p);
    }

    /**
     * 读取一个稳定重量值。内部轮询 Q 指令，跳过 US（不稳定）帧。
     *
     * @param timeout 稳定等待上限，建议 3 秒
     * @throws ScaleOverloadException 秤返回 OL（超载）
     * @throws ScaleTimeoutException 超时仍未稳定（含静默线路无应答）
     */
    public BigDecimal readStableGrams(Duration timeout)
            throws IOException, ScaleOverloadException, ScaleTimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ScaleReading r = query(deadline);
            if (r == null) {
                break; // deadline 内未收到完整帧（静默线路）
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

    /**
     * 去皮 / 清零，等同按 RE-ZERO 键。
     *
     * @param timeout 等待秤确认的超时上限
     * @throws IOException 确认异常（回显非 Z）或清零超时（静默线路无应答）
     */
    public void zero(Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        send("Z");
        String ack = readLineWithDeadline(deadline);
        if (ack == null) {
            throw new IOException("清零指令超时（" + timeout.toSeconds() + " 秒未收到确认）");
        }
        if (!"Z".equals(ack.trim())) {
            throw new IOException("清零指令未确认，收到: " + ack);
        }
    }

    /** 发一次 Q 指令并解析应答；deadline 内未收到完整帧返回 null。 */
    private ScaleReading query(Instant deadline) throws IOException {
        send("Q");
        String line = readLineWithDeadline(deadline);
        if (line == null) {
            return null;
        }
        try {
            return ScaleReading.parse(line);
        } catch (ScaleProtocolException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 按 deadline 有界读一行（CR LF 结尾）。底层 200ms 短超时轮询，
     * 每轮检查 deadline，帧可跨多次读取累积。
     *
     * @return 完整行（不含 CR LF）；deadline 耗尽仍未收到整行返回 null
     */
    private String readLineWithDeadline(Instant deadline) throws IOException {
        byte[] chunk = new byte[64];
        while (Instant.now().isBefore(deadline)) {
            int n = port.readBytes(chunk, chunk.length);
            if (n < 0) {
                throw new IOException("串口读取失败");
            }
            if (n == 0) {
                continue; // 本轮 200ms 无数据，回到 deadline 检查
            }
            buffer.feed(chunk, n);
            String line = buffer.pollLine();
            if (line != null) {
                buffer.reset(); // 丢弃残帧，防污染下一次应答
                return line;
            }
        }
        return null;
    }

    private void send(String cmd) throws IOException {
        byte[] data = (cmd + "\r\n").getBytes(StandardCharsets.US_ASCII);
        if (port.writeBytes(data, data.length) < 0) {
            throw new IOException("串口写入失败");
        }
    }

    @Override
    public void close() {
        port.closePort();
    }
}

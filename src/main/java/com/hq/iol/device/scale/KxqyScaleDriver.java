package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class KxqyScaleDriver implements AutoCloseable {

    private static final int READ_CHUNK_TIMEOUT_MS = 200;
    private static final int WRITE_QUEUE_TIMEOUT_MS = 1500;
    private static final int WRITE_QUEUE_POLL_MS = 5;
    private static final int MAX_FRAME_LENGTH = 8192;

    private final SerialPort port;
    private final int address;
    private final LineBuffer buffer = new LineBuffer();

    private KxqyScaleDriver(SerialPort port, int address) {
        this.port = port;
        this.address = address;
    }

    public static KxqyScaleDriver open(ScaleProfile profile) throws IOException {
        SerialPort port = SerialPort.getCommPort(profile.port());
        port.setComPortParameters(profile.baud(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_CHUNK_TIMEOUT_MS, 1500);
        if (!port.openPort()) {
            throw new IOException("串口打开失败: " + profile.port()
                    + "，请关闭其他串口工具并检查设备是否已连接");
        }
        return new KxqyScaleDriver(port, profile.address());
    }

    static KxqyScaleDriver fromPort(SerialPort port, int address) {
        if (address < 0 || address > 255) {
            throw new IllegalArgumentException("电子秤地址必须在 0–255 之间");
        }
        return new KxqyScaleDriver(port, address);
    }

    public ScaleReading readCurrentFrame(Duration timeout)
            throws IOException, ScaleProtocolException, ScaleTimeoutException {
        if (address == 0) {
            throw new IOException("广播地址 000 不返回数据，请将本机地址设为与秤一致的 001–255");
        }
        Instant deadline = Instant.now().plus(timeout);
        buffer.reset();
        send('P');
        StringBuilder frame = new StringBuilder();
        boolean sawType = false;
        while (Instant.now().isBefore(deadline)) {
            String line = buffer.pollLine();
            if (line == null) {
                readChunk();
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            String field = line.strip();
            if (field.startsWith("TYPE:")) {
                // Continuous output uses the next TYPE line to delimit the previous group.
                if (sawType) {
                    ScaleReading reading = ScaleReading.parseContinuous(frame.toString(), address);
                    if (reading != null) {
                        return reading;
                    }
                }
                frame.setLength(0);
                sawType = true;
            }
            frame.append(line).append('\n');
            if (frame.length() > MAX_FRAME_LENGTH) {
                throw new IOException("电子秤数据组超过 " + MAX_FRAME_LENGTH + " 字符，请检查串口参数与 FMT 设置");
            }
            if (ScaleReading.END_MARKER.equals(field)) {
                return ScaleReading.parse(frame.toString());
            }
        }
        throw new ScaleTimeoutException(timeout);
    }

    public ScaleReading readStableReading(Duration timeout)
            throws IOException, ScaleProtocolException, ScaleTimeoutException, ScaleDeviceException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            ScaleReading reading = readCurrentFrame(remaining);
            validateReading(reading);
            if (reading.isStable()) {
                return reading;
            }
        }
        throw new ScaleTimeoutException(timeout);
    }

    public BigDecimal readStableGrams(Duration timeout)
            throws IOException, ScaleProtocolException, ScaleTimeoutException, ScaleDeviceException {
        return readStableReading(timeout).value();
    }

    public ScaleReading readAfterAdjustment(Duration timeout)
            throws IOException, ScaleProtocolException, ScaleTimeoutException {
        // A continuously streaming scale can leave pre-adjustment frames in the input queue.
        int pending = port.bytesAvailable();
        if (pending < 0) {
            throw new IOException("无法检查串口接收队列");
        }
        if (pending > 0) {
            byte[] discarded = new byte[Math.min(pending, 256)];
            while (pending > 0) {
                int count = port.readBytes(discarded, Math.min(pending, discarded.length));
                if (count <= 0) {
                    throw new IOException("读取串口积压数据失败");
                }
                pending -= count;
            }
        }
        return readStableReading(timeout);
    }

    public void tare() throws IOException {
        send('T');
        awaitWriteQueueEmpty();
    }

    public void zero() throws IOException {
        send('Z');
        awaitWriteQueueEmpty();
    }

    private void awaitWriteQueueEmpty() throws IOException {
        // On Windows, closing the serial port discards bytes that are still queued.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITE_QUEUE_TIMEOUT_MS);
        while (true) {
            int pending = port.bytesAwaitingWrite();
            if (pending == 0) {
                return;
            }
            if (pending < 0) {
                throw new IOException("无法检查串口发送队列，指令是否发出未知");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IOException("等待串口发送超时，队列仍有 " + pending + " 字节待发送");
            }
            try {
                Thread.sleep(WRITE_QUEUE_POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("等待串口发送时被中断", ex);
            }
        }
    }

    public static void validateReading(ScaleReading reading) throws IOException, ScaleDeviceException {
        if (reading.hasDeviceError()) {
            throw new ScaleDeviceException(reading.status(), reading.statusDescription()
                    + "，当前重量: " + reading.value().toPlainString() + " " + reading.unit());
        }
        if (!reading.isGram()) {
            throw new IOException("电子秤单位不是克，当前为: " + reading.unit() + "，请切换为 g");
        }
    }

    private void send(char command) throws IOException {
        byte[] data = (String.format("%03d", address) + command).getBytes(StandardCharsets.US_ASCII);
        int written = port.writeBytes(data, data.length);
        if (written != data.length) {
            throw new IOException("串口指令写入失败，已写入 " + written + "/" + data.length + " 字节");
        }
    }

    private void readChunk() throws IOException {
        byte[] chunk = new byte[256];
        int count = port.readBytes(chunk, chunk.length);
        if (count < 0) {
            throw new IOException("串口读取失败，请检查电子秤连接");
        }
        if (count > 0) {
            buffer.feed(chunk, count);
        }
    }

    @Override
    public void close() {
        port.closePort();
    }
}

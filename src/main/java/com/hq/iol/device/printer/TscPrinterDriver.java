package com.hq.iol.device.printer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TSC 打印机网口传输通道（RAW 9100）。
 * 打印前查询状态，避免缺纸空打导致序列号断号。非线程安全：调用方串行化打印动作。
 */
public final class TscPrinterDriver {

    /** 状态查询指令 &lt;ESC&gt;!? ，见手册 P82。 */
    private static final byte[] CMD_STATUS = {0x1B, 0x21, 0x3F};

    private final String host;
    private final int port;
    private final int connectTimeoutMs;

    public TscPrinterDriver(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 查询状态字节。 */
    public int queryStatus() throws IOException {
        try (Socket s = openSocket()) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(CMD_STATUS);
            out.flush();
            int status = in.read();
            if (status < 0) {
                throw new IOException("打印机无状态应答: " + host);
            }
            return status;
        }
    }

    /** 查询状态并打印。状态非就绪时抛 PrinterNotReadyException，不下发指令。 */
    public void print(String tspl) throws IOException, PrinterNotReadyException {
        try (Socket s = openSocket()) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(CMD_STATUS);
            out.flush();
            int status = in.read();
            if (status < 0) {
                throw new IOException("打印机无状态应答: " + host);
            }
            if (!PrinterStatus.isReady(status)) {
                throw new PrinterNotReadyException(status);
            }
            out.write(tspl.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private Socket openSocket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(2000);
        return s;
    }

    public static final class PrinterNotReadyException extends Exception {

        private final int status;

        PrinterNotReadyException(int status) {
            super("打印机未就绪: " + PrinterStatus.describe(status));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}

package com.hq.iol.device.printer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** 虚拟 TSC 打印机：9100 行为仿真，应答状态字节并记录收到的打印数据。 */
final class FakeTscPrinterServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private volatile int statusToReturn = 0x00;
    private volatile boolean respond = true;

    FakeTscPrinterServer() throws IOException {
        server = new ServerSocket(0);
        thread = new Thread(this::serve);
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void setStatus(int status) {
        this.statusToReturn = status;
    }

    void setRespond(boolean respond) {
        this.respond = respond;
    }

    byte[] received() {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待虚拟打印机接收数据时被中断", e);
        }
        if (thread.isAlive()) {
            throw new AssertionError("虚拟打印机未在 5 秒内收到完整连接数据");
        }
        return received.toByteArray();
    }

    private void serve() {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == 0x1B) { // ESC：按 3 字节读完 <ESC>!?，回应状态字节
                    in.read();
                    in.read();
                    if (respond) {
                        out.write(statusToReturn);
                        out.flush();
                    }
                } else {
                    received.write(b);
                }
            }
        } catch (IOException ignored) {
            // 测试结束关闭时正常退出
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}

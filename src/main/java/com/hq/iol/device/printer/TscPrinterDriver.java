package com.hq.iol.device.printer;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** TSC 打印机网口传输通道，使用 Vert.x NetClient 实现短连接 RAW 9100 通信。 */
public final class TscPrinterDriver {

    private static final byte[] CMD_STATUS = {0x1B, 0x21, 0x3F};
    private static final int IO_TIMEOUT_MS = 2000;

    private final Vertx vertx;
    private final String host;
    private final int port;
    private final int connectTimeoutMs;

    public TscPrinterDriver(Vertx vertx, String host, int port, int connectTimeoutMs) {
        this.vertx = vertx;
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int queryStatus() throws IOException {
        try (Connection connection = openConnection()) {
            return requestStatus(connection.socket());
        }
    }

    /** 查询状态并打印。状态非就绪时抛 PrinterNotReadyException，不下发指令。 */
    public void print(String tspl) throws IOException, PrinterNotReadyException {
        try (Connection connection = openConnection()) {
            int status = requestStatus(connection.socket());
            if (!PrinterStatus.isReady(status)) {
                throw new PrinterNotReadyException(status);
            }
            await(connection.socket().write(Buffer.buffer(tspl, StandardCharsets.UTF_8.name()))
                            .toCompletionStage(),
                    IO_TIMEOUT_MS, "打印数据发送失败");
        }
    }

    private int requestStatus(NetSocket socket) throws IOException {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        socket.handler(buffer -> {
            if (buffer.length() > 0) {
                response.complete(buffer.getByte(0) & 0xFF);
            }
        });
        socket.exceptionHandler(response::completeExceptionally);
        socket.closeHandler(v -> response.completeExceptionally(
                new IOException("打印机无状态应答: " + host)));
        await(socket.write(Buffer.buffer().appendBytes(CMD_STATUS)).toCompletionStage(),
                IO_TIMEOUT_MS, "状态指令发送失败");
        return await(response, IO_TIMEOUT_MS, "打印机状态读取超时");
    }

    private Connection openConnection() throws IOException {
        NetClient client = vertx.createNetClient(new NetClientOptions()
                .setConnectTimeout(connectTimeoutMs)
                .setReuseAddress(true)
                .setTcpNoDelay(true));
        try {
            NetSocket socket = await(client.connect(port, host).toCompletionStage(),
                    connectTimeoutMs + 500L, "打印机连接失败: " + host);
            return new Connection(client, socket);
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    private static <T> T await(CompletionStage<T> stage, long timeoutMs, String action) throws IOException {
        try {
            return stage.toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException(action + "（" + timeoutMs + " ms 超时）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail = cause.getMessage() != null ? ": " + cause.getMessage() : "";
            throw new IOException(action + detail, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(action + "（线程被中断）", e);
        }
    }

    private record Connection(NetClient client, NetSocket socket) implements AutoCloseable {
        @Override
        public void close() {
            socket.close();
            client.close();
        }
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

package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LaserBridge.exe（32 位 Ezcad2 桥接进程）的进程管理与行 JSON 协议客户端。
 *
 * <p>stdin/stdout 每行一个 JSON：请求带自增 id，响应按 id 关联，完成事件以
 * {@code {"event":"markFinished",...}} 推送。所有请求都有有界等待；桥接进程
 * 异常退出会使所有在途请求以 {@code PROCESS_EXITED} 失败。</p>
 */
public final class LaserBridgeClient implements AutoCloseable {

    private final Process process;
    private final BufferedWriter writer;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final Consumer<String> initDiagnostics;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final StringBuilder stderrTail = new StringBuilder();
    private volatile CompletableFuture<JsonNode> markFuture;

    public LaserBridgeClient(java.nio.file.Path exe, java.nio.file.Path workingDirectory) throws LaserException {
        this(exe, workingDirectory, message -> {});
    }

    LaserBridgeClient(java.nio.file.Path exe, java.nio.file.Path workingDirectory, Consumer<String> initDiagnostics)
            throws LaserException {
        this.initDiagnostics = initDiagnostics;
        ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString());
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        pb.redirectErrorStream(false);
        try {
            this.process = pb.start();
        } catch (IOException e) {
            throw new LaserException("PROCESS_START_FAILED", "无法启动桥接进程 " + exe + ": " + e.getMessage(), e);
        }
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::readLoop, "laser-bridge-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        this.stderrThread = new Thread(this::stderrLoop, "laser-bridge-stderr");
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * 发送一条请求并等待响应（有界等待）。
     *
     * @throws LaserException 超时、进程退出、协议错误或桥接返回错误
     */
    public JsonNode request(String cmd, ObjectNode params, Duration timeout) throws LaserException {
        if (closed.get()) {
            throw new LaserException("CLOSED", "桥接进程连接已关闭");
        }
        long id = nextId.getAndIncrement();
        ObjectNode req = LaserBridgeJson.request(id, cmd);
        if (params != null) {
            req.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(req.toString());
        } catch (LaserException e) {
            pending.remove(id, future);
            throw e;
        }
        final JsonNode response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id, future);
            throw new LaserException("TIMEOUT", cmd + " 在 " + timeout.toMillis() + "ms 内没有响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id, future);
            throw new LaserException("INTERRUPTED", cmd + " 等待被中断");
        } catch (ExecutionException e) {
            pending.remove(id, future);
            Throwable cause = e.getCause();
            if (cause instanceof LaserException le) {
                throw le;
            }
            throw new LaserException("INTERNAL", cmd + " 等待失败: " + cause, cause);
        }
        if (!LaserBridgeJson.ok(response)) {
            throw LaserBridgeJson.asException(response);
        }
        return response.path("result");
    }

    /** 发出 mark 请求；返回完成事件 future（由读取循环在 markFinished 事件时完成）。
     *  future 先注册再发请求，避免打标瞬间完成丢失事件。 */
    public synchronized CompletableFuture<JsonNode> beginMark(Duration ackTimeout) throws LaserException {
        return beginMarkCommand("mark", ackTimeout);
    }

    public synchronized CompletableFuture<JsonNode> beginRedLight(Duration ackTimeout) throws LaserException {
        return beginMarkCommand("redLight", ackTimeout);
    }

    private CompletableFuture<JsonNode> beginMarkCommand(String command, Duration ackTimeout) throws LaserException {
        if (markFuture != null && !markFuture.isDone()) {
            throw new LaserException("ALREADY_MARKING", "已有打标任务在执行");
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        markFuture = future;
        try {
            request(command, null, ackTimeout);
        } catch (LaserException e) {
            if (markFuture == future) {
                markFuture = null;
            }
            throw e;
        }
        return future;
    }

    private void writeLine(String line) throws LaserException {
        synchronized (writer) {
            if (closed.get()) {
                throw new LaserException("CLOSED", "桥接进程连接已关闭");
            }
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new LaserException("PROCESS_WRITE_FAILED", "写入桥接进程失败: " + e.getMessage(), e);
            }
        }
    }

    private void readLoop() {
        String line;
        try (Reader raw = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(raw)) {
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (IOException ignored) {
            // 进程管道断开，走统一退出处理
        } finally {
            int code = waitForExit();
            failAll(new LaserException("PROCESS_EXITED",
                    "桥接进程已退出 (exitCode=" + code + ")" + stderrSummary()));
        }
    }

    private void handleLine(String line) {
        final JsonNode node;
        try {
            node = LaserBridgeJson.parse(line);
        } catch (LaserException e) {
            appendStderr("非法响应行: " + e.getMessage());
            return;
        }
        if (LaserBridgeJson.isResponse(node)) {
            CompletableFuture<JsonNode> future = pending.remove(LaserBridgeJson.id(node));
            if (future != null) {
                future.complete(node);
            } else {
                appendStderr("无匹配请求的响应: " + LaserBridgeJson.abbreviate(line));
            }
        } else if (LaserBridgeJson.isEvent(node)) {
            String name = LaserBridgeJson.eventName(node);
            if ("markFinished".equals(name)) {
                CompletableFuture<JsonNode> future = markFuture;
                if (future != null) {
                    future.complete(node.path("data"));
                }
            } else if ("initDiagnostic".equals(name)) {
                String message = node.path("data").path("message").asText();
                try {
                    initDiagnostics.accept(message);
                } catch (RuntimeException ex) {
                    appendStderr("写入激光诊断日志失败: " + ex.getMessage());
                }
            } else {
                appendStderr("未知事件: " + name);
            }
        } else {
            appendStderr("无法识别的消息: " + LaserBridgeJson.abbreviate(line));
        }
    }

    private void stderrLoop() {
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                appendStderr(line);
            }
        } catch (IOException ignored) {
            // 进程结束
        }
    }

    private synchronized void appendStderr(String line) {
        if (stderrTail.length() > 4000) {
            stderrTail.setLength(0);
        }
        stderrTail.append('\n').append(line);
    }

    private synchronized String stderrSummary() {
        if (stderrTail.isEmpty()) {
            return "";
        }
        return "，桥接stderr: " + stderrTail;
    }

    private int waitForExit() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void failAll(LaserException failure) {
        pending.values().forEach(f -> f.completeExceptionally(failure));
        pending.clear();
        CompletableFuture<JsonNode> future = markFuture;
        if (future != null) {
            future.completeExceptionally(failure);
        }
    }

    /** 关闭：发送 close 命令等待应答，随后销毁进程并回收线程；可重复调用。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            long id = nextId.getAndIncrement();
            ObjectNode req = LaserBridgeJson.request(id, "close");
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            synchronized (writer) {
                writer.write(req.toString());
                writer.write('\n');
                writer.flush();
            }
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 尽力而为：进程随后会被销毁
        }
        // 让在途等待的调用方立即失败，而不是等到各自超时
        failAll(new LaserException("CLOSED", "桥接进程连接已关闭"));
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}

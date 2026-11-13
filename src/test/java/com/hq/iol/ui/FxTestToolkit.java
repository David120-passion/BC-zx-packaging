package com.hq.iol.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 JVM 内共享的 FX Toolkit 生命周期：首个使用者负责启动，
 * 并关闭隐式退出（避免窗口关闭导致 Toolkit 中途停止）；
 * Toolkit 不主动关闭，由 surefire 的 System.exit 随测试 JVM 一起终止。
 */
final class FxTestToolkit {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private FxTestToolkit() {
    }

    static void start() throws InterruptedException {
        if (STARTED.compareAndSet(false, true)) {
            CountDownLatch ready = new CountDownLatch(1);
            Platform.startup(ready::countDown);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "FX Toolkit 应完成启动");
            Platform.setImplicitExit(false);
        }
    }
}

package com.hq.iol.ui;

import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SplashScreen 生命周期回归：close() 必须能取消进行中的 finish() 淡出，
 * 停止呼吸动画，并阻止回调执行；close() 本身幂等。
 * 使用真实 Stage 与 transition，全部状态断言基于可观察行为。
 */
class SplashScreenLifecycleTest {

    private final List<SplashScreen> created = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestToolkit.start();
    }

    @AfterEach
    void closeSplashes() throws Exception {
        for (SplashScreen splash : created) {
            onFx(() -> {
                splash.close();
                return null;
            });
        }
        created.clear();
    }

    @Test
    void closeDuringFinishCancelsCallbackAndStopsBreath() throws Exception {
        SplashScreen splash = newShownSplash();
        AtomicBoolean callbackRan = new AtomicBoolean();
        onFx(() -> {
            splash.finish(() -> callbackRan.set(true));
            return null;
        });
        // 淡出共 320ms，中途调用 close()
        Thread.sleep(100);
        splash.close();
        // 取消必须立即停止无限呼吸动画（最晚 250ms 内，远早于淡出自然结束的 320ms）
        assertTrue(awaitBreathStopped(splash, 250), "close() 应立即停止呼吸动画");
        // 给被取消的淡出留出原本完成的时间
        Thread.sleep(400);
        assertFalse(callbackRan.get(), "被取消的 finish() 不得执行回调");
    }

    @Test
    void closeIsIdempotentAndCancelsLaterFinish() throws Exception {
        SplashScreen splash = newShownSplash();
        AtomicBoolean callbackRan = new AtomicBoolean();
        splash.close();
        splash.close();
        onFx(() -> {
            splash.finish(() -> callbackRan.set(true));
            return null;
        });
        Thread.sleep(400);
        assertFalse(callbackRan.get(), "close() 之后的 finish() 回调不得执行");
        assertFalse(breathRunning(splash), "呼吸动画应保持停止");
    }

    @Test
    void finishCompletesNormallyAndRunsCallback() throws Exception {
        SplashScreen splash = newShownSplash();
        AtomicBoolean callbackRan = new AtomicBoolean();
        onFx(() -> {
            splash.finish(() -> callbackRan.set(true));
            return null;
        });
        long deadline = System.currentTimeMillis() + 2000;
        while (!callbackRan.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(callbackRan.get(), "正常 finish() 应在淡出结束后执行回调");
        assertFalse(breathRunning(splash), "淡出结束后呼吸动画应停止");
    }

    private SplashScreen newShownSplash() throws Exception {
        Image icon = new Image(getClass().getResourceAsStream("/icon.png"));
        SplashScreen splash = onFx(() -> {
            SplashScreen s = new SplashScreen(icon);
            s.show();
            return s;
        });
        created.add(splash);
        return splash;
    }

    private static boolean awaitBreathStopped(SplashScreen splash, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!breathRunning(splash)) {
                return true;
            }
            Thread.sleep(15);
        }
        return !breathRunning(splash);
    }

    private static boolean breathRunning(SplashScreen splash) throws Exception {
        Field breath = SplashScreen.class.getDeclaredField("iconBreath");
        breath.setAccessible(true);
        return ((Animation) breath.get(splash)).getStatus() == Animation.Status.RUNNING;
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX 事件应按时执行");
        if (failure.get() != null) {
            throw new Exception("FX 线程上执行失败", failure.get());
        }
        return result.get();
    }
}

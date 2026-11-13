package com.hq.iol;

import com.hq.iol.ui.SplashScreen;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MainApp 启动失败路径的行为回归：
 * 1) 初始化阶段（如图标加载）失败必须走 failStartup（记录并提示错误）；
 * 2) splash.finish(...) 的异步回调里 showMainStage 抛异常必须回到 failStartup。
 * 通过子类测试替身替换可覆盖的启动步骤实现，断言基于状态与门闩，而非源码文本。
 */
class MainAppStartupFailureTest {

    private final List<Stage> stages = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestToolkit.start();
    }

    @AfterEach
    void closeStages() {
        Platform.runLater(() -> stages.forEach(Stage::close));
        stages.clear();
    }

    @Test
    void initializationFailureUsesFailurePath() throws Exception {
        RecordingMainApp app = new RecordingMainApp() {
            @Override
            List<Image> loadIcons() {
                throw new IllegalStateException("icon-missing");
            }
        };
        Stage stage = newPrimaryStage();

        onFx(() -> {
            app.start(stage);
            return null;
        });

        assertTrue(app.errorReported.await(5, TimeUnit.SECONDS),
                "初始化失败应记录并提示错误");
        assertEquals("icon-missing", app.errorMessage.get());
    }

    @Test
    void showMainStageFailureInFinishCallbackUsesFailurePath() throws Exception {
        RecordingMainApp app = new RecordingMainApp() {
            @Override
            ConfigurableApplicationContext runSpring() {
                // 替换真实 Spring 引导，避免测试启动完整容器
                return null;
            }

            @Override
            void prepareMainStage(Stage stage, List<Image> icons) {
                // 不构建真实主界面；仅设置最小场景，让 showMainStage 走真实路径
                stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.BorderPane(), 400, 300));
            }

            @Override
            void showMainStage(Stage stage) {
                throw new IllegalStateException("show-boom");
            }
        };
        Stage stage = newPrimaryStage();

        onFx(() -> {
            app.start(stage);
            return null;
        });

        assertTrue(app.errorReported.await(5, TimeUnit.SECONDS),
                "finish 回调中 showMainStage 抛异常应回到统一失败路径");
        assertEquals("show-boom", app.errorMessage.get());
    }

    /** 记录错误提示调用，避免真实 Alert 阻塞测试。 */
    private abstract static class RecordingMainApp extends MainApp {
        final CountDownLatch errorReported = new CountDownLatch(1);
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        @Override
        void showFatalError(String message) {
            errorMessage.set(message);
            errorReported.countDown();
        }
    }

    private Stage newPrimaryStage() throws Exception {
        Stage stage = onFx(Stage::new);
        stages.add(stage);
        return stage;
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

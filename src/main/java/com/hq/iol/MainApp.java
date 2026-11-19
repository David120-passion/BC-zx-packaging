package com.hq.iol;

import com.hq.iol.config.AppInfo;
import com.hq.iol.ui.MainView;
import com.hq.iol.ui.SplashScreen;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX 应用入口：start() 阶段先展示启动动画，
 * 后台 daemon 线程引导 Spring 容器，完成后淡出动画并挂载主界面。
 */
public class MainApp {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    private final StartupFailureHandler startupFailures = new StartupFailureHandler();

    private ConfigurableApplicationContext ctx;

    public static void main(String[] args) {
        Application.launch(FxApplication.class, args);
    }

    public void start(Stage stage) {
        SplashScreen splash = null;
        List<Image> icons = null;
        try {
            loadBundledFonts();
            icons = loadIcons();
            splash = new SplashScreen(icons.get(icons.size() - 1));
            splash.show();
        } catch (Exception e) {
            // 字体/图标/启动画面初始化失败同样走统一的失败路径
            failStartup(splash, e);
            return;
        }
        startBoot(splash, icons, stage);
    }

    /** 后台 daemon 线程引导 Spring，完成后淡出启动画面并挂载主界面。 */
    void startBoot(SplashScreen splash, List<Image> icons, Stage stage) {
        Thread boot = new Thread(() -> {
            try {
                splash.updateStatus("正在准备本地数据…");
                Files.createDirectories(Path.of("data"));
                splash.updateStatus("正在启动服务容器…");
                ctx = runSpring();
                Platform.runLater(() -> {
                    try {
                        splash.updateStatus("正在加载主界面…");
                        prepareMainStage(stage, icons);
                        splash.finish(() -> {
                            // 回调由动画结束时异步执行，异常必须回到统一的失败路径
                            try {
                                showMainStage(stage);
                            } catch (Exception e) {
                                failStartup(splash, e);
                            }
                        });
                    } catch (Exception e) {
                        failStartup(splash, e);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> failStartup(splash, e));
            }
        }, "spring-boot");
        boot.setDaemon(true);
        boot.start();
    }

    /** 引导 Spring 容器。包级可见，供启动失败路径的回归测试替换。 */
    ConfigurableApplicationContext runSpring() {
        return new SpringApplicationBuilder(SpringMain.class)
                .web(WebApplicationType.NONE).headless(false).run();
    }

    /** 加载内嵌的思源黑体（Medium 正文 + Bold 标题），不依赖系统字体。 */
    void loadBundledFonts() {
        Font.loadFont(getClass().getResourceAsStream("/fonts/NotoSansCJKsc-Medium.otf"), 14);
        Font.loadFont(getClass().getResourceAsStream("/fonts/NotoSansCJKsc-Bold.otf"), 14);
    }

    /** 依次读取五个窗口图标，返回 16→512 顺序的列表。 */
    List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>();
        for (String name : new String[]{"/icon-16.png", "/icon-32.png", "/icon-64.png", "/icon-256.png", "/icon.png"}) {
            icons.add(new Image(getClass().getResourceAsStream(name)));
        }
        return icons;
    }

    /** 构建主界面场景，并按主屏幕的可用区域设置窗口大小。 */
    void prepareMainStage(Stage stage, List<Image> icons) {
        MainView mainView = ctx.getBean(MainView.class);
        Scene scene = new Scene(mainView.build());
        // 填充色与页面背景一致：最大化/全屏时高分屏缩放会在边缘露出 Scene 底色，
        // 默认底色渲染为黑色，会出现黑边
        scene.setFill(Color.web("#F5F5F7"));
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1360, screenBounds.getWidth());
        double height = Math.min(900, screenBounds.getHeight());
        stage.setMinWidth(Math.min(1180, width));
        stage.setMinHeight(Math.min(720, height));
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(screenBounds.getMinX() + (screenBounds.getWidth() - width) / 2);
        stage.setY(screenBounds.getMinY() + (screenBounds.getHeight() - height) / 2);
        stage.setTitle("IOL 包装线上位机 v" + AppInfo.version());
        stage.getIcons().addAll(icons);
        stage.setScene(scene);
    }

    /** 显示主窗口并淡入。 */
    void showMainStage(Stage stage) {
        stage.getScene().getRoot().setOpacity(0);
        stage.show();
        FadeTransition fade = new FadeTransition(Duration.millis(300), stage.getScene().getRoot());
        fade.setToValue(1.0);
        fade.play();
    }

    /** 启动失败：记录异常、关闭启动画面、提示错误并退出。 */
    private void failStartup(SplashScreen splash, Exception e) {
        startupFailures.handle(e, new StartupFailureHandler.FxActions() {
            @Override
            public void closeSplash() {
                if (splash != null) {
                    splash.close();
                }
            }

            @Override
            public void showError(String message) {
                showFatalError(message);
            }
        });
    }

    /** 弹出致命错误提示并退出。包级可见，供启动失败路径的回归测试替换。 */
    void showFatalError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("启动失败");
        alert.setHeaderText("应用启动失败，即将退出");
        alert.setContentText(message);
        alert.showAndWait();
        Platform.exit();
    }

    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
        Platform.exit();
    }

    public static final class FxApplication extends Application {
        private final MainApp app = new MainApp();

        @Override
        public void start(Stage stage) {
            app.start(stage);
        }

        @Override
        public void stop() {
            app.stop();
        }
    }
}

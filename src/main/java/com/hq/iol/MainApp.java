package com.hq.iol;

import com.hq.iol.config.AppInfo;
import com.hq.iol.ui.MainView;
import com.hq.iol.ui.SplashScreen;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
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
public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    private ConfigurableApplicationContext ctx;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        loadBundledFonts();
        List<Image> icons = loadIcons();
        SplashScreen splash = new SplashScreen(icons.get(icons.size() - 1));
        splash.show();
        Thread boot = new Thread(() -> {
            try {
                splash.updateStatus("正在准备本地数据…");
                Files.createDirectories(Path.of("data"));
                splash.updateStatus("正在启动服务容器…");
                ctx = new SpringApplicationBuilder(SpringMain.class)
                        .web(WebApplicationType.NONE).headless(false).run();
                Platform.runLater(() -> {
                    try {
                        splash.updateStatus("正在加载主界面…");
                        prepareMainStage(stage, icons);
                        splash.finish(() -> showMainStage(stage));
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

    /** 加载内嵌的思源黑体（Medium 正文 + Bold 标题），不依赖系统字体。 */
    private void loadBundledFonts() {
        Font.loadFont(getClass().getResourceAsStream("/fonts/NotoSansCJKsc-Medium.otf"), 14);
        Font.loadFont(getClass().getResourceAsStream("/fonts/NotoSansCJKsc-Bold.otf"), 14);
    }

    /** 依次读取五个窗口图标，返回 16→512 顺序的列表。 */
    private List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>();
        for (String name : new String[]{"/icon-16.png", "/icon-32.png", "/icon-64.png", "/icon-256.png", "/icon.png"}) {
            icons.add(new Image(getClass().getResourceAsStream(name)));
        }
        return icons;
    }

    /** 构建主界面场景（不显示）。侧边栏布局较宽，窗口相应加大并设置最小尺寸保护布局。 */
    private void prepareMainStage(Stage stage, List<Image> icons) {
        MainView mainView = ctx.getBean(MainView.class);
        Scene scene = new Scene(mainView.build(), 1360, 900);
        // 填充色与页面背景一致：最大化/全屏时高分屏缩放会在边缘露出 Scene 底色，
        // 默认底色渲染为黑色，会出现黑边
        scene.setFill(Color.web("#F5F5F7"));
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        stage.setMinWidth(1180);
        stage.setMinHeight(720);
        stage.setTitle("IOL 包装线上位机 v" + AppInfo.version());
        stage.getIcons().addAll(icons);
        stage.setScene(scene);
    }

    /** 显示主窗口并淡入。 */
    private void showMainStage(Stage stage) {
        stage.getScene().getRoot().setOpacity(0);
        stage.show();
        FadeTransition fade = new FadeTransition(Duration.millis(300), stage.getScene().getRoot());
        fade.setToValue(1.0);
        fade.play();
    }

    /** 启动失败：记录异常、关闭启动画面、提示错误并退出。 */
    private void failStartup(SplashScreen splash, Exception e) {
        log.error("应用启动失败", e);
        splash.close();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("启动失败");
        alert.setHeaderText("应用启动失败，即将退出");
        alert.setContentText(e.getMessage() != null ? e.getMessage() : e.toString());
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
        Platform.exit();
    }
}

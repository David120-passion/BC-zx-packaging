package com.hq.iol.ui;

import com.hq.iol.config.AppInfo;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 开机启动动画（Apple 极简风）：透明无边框窗口 + 白色圆角卡片，
 * 圆角应用图标（柔和投影 + 呼吸微动效）、应用名/副标题、3px 细进度条、状态文字与版本号。
 * 调用顺序：show() → updateStatus(...) 若干次 → finish(...) 或 close()。
 */
public class SplashScreen {

    private final Stage stage;
    private final Label statusLabel;
    private final StackPane root;
    private final ScaleTransition iconBreath;

    /** 状态切换全部限定在 FX 线程；volatile 仅供 close() 的快速幂等判断。 */
    private FadeTransition exitFade;
    private boolean finishing;
    private Runnable pendingFinishCallback;
    private volatile boolean closed;

    public SplashScreen(Image appIcon) {
        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        if (appIcon != null) {
            stage.getIcons().add(appIcon);
        }

        // 应用图标：圆角裁剪 + 柔和投影（CSS），呼吸动效
        ImageView iconView = new ImageView(appIcon);
        iconView.setFitWidth(88);
        iconView.setFitHeight(88);
        iconView.setPreserveRatio(true);
        Rectangle clip = new Rectangle(88, 88);
        clip.setArcWidth(22);
        clip.setArcHeight(22);
        iconView.setClip(clip);
        iconView.getStyleClass().add("splash-icon");
        iconBreath = new ScaleTransition(Duration.millis(1500), iconView);
        iconBreath.setFromX(1.0);
        iconBreath.setFromY(1.0);
        iconBreath.setToX(1.045);
        iconBreath.setToY(1.045);
        iconBreath.setCycleCount(ScaleTransition.INDEFINITE);
        iconBreath.setAutoReverse(true);
        iconBreath.setInterpolator(Interpolator.EASE_BOTH);

        Label name = new Label("IOL 包装线上位机");
        name.getStyleClass().add("splash-title");

        Label subtitle = new Label("智能包装与标签工作站");
        subtitle.getStyleClass().add("splash-subtitle");

        ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setPrefWidth(240);
        progress.setMaxWidth(240);
        progress.getStyleClass().add("splash-progress");

        statusLabel = new Label("正在启动…");
        statusLabel.getStyleClass().add("splash-status");

        Label version = new Label("v" + AppInfo.version());
        version.getStyleClass().add("splash-version");

        VBox card = new VBox(8, iconView, name, subtitle, progress, statusLabel, version);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(38, 52, 26, 52));
        // 图标与名称之间、副标题与进度条之间留白稍大，拉开节奏层次
        VBox.setMargin(iconView, new Insets(0, 0, 10, 0));
        VBox.setMargin(progress, new Insets(10, 0, 2, 0));
        VBox.setMargin(version, new Insets(6, 0, 0, 0));
        card.getStyleClass().add("splash-card");
        card.setMaxWidth(400);

        root = new StackPane(card);
        root.setPadding(new Insets(24)); // 给阴影留出空间
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        stage.setScene(scene);
    }

    /** 显示启动画面（淡入 + 轻微放大）。必须在 FX 应用线程调用。 */
    public void show() {
        stage.show();
        stage.centerOnScreen();

        root.setOpacity(0);
        root.setScaleX(0.94);
        root.setScaleY(0.94);

        FadeTransition fade = new FadeTransition(Duration.millis(420), root);
        fade.setToValue(1.0);
        ScaleTransition scale = new ScaleTransition(Duration.millis(420), root);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, scale).play();

        iconBreath.play();
    }

    /** 更新状态文字，任意线程可调用。 */
    public void updateStatus(String text) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    /** 淡出并关闭启动画面，结束后回调 onFinished（可为 null）。任意线程可调用，状态切换限定在 FX 线程。 */
    public void finish(Runnable onFinished) {
        runOnFxThread(() -> finishOnFxThread(onFinished));
    }

    private void finishOnFxThread(Runnable onFinished) {
        if (closed) {
            // close() 已取消启动流程，回调不再执行
            return;
        }
        if (finishing) {
            pendingFinishCallback = onFinished;
            return;
        }
        finishing = true;
        pendingFinishCallback = onFinished;
        exitFade = new FadeTransition(Duration.millis(320), root);
        exitFade.setToValue(0);
        exitFade.setOnFinished(e -> completeFinishOnFxThread());
        exitFade.play();
    }

    private void completeFinishOnFxThread() {
        exitFade = null;
        if (closed) {
            return;
        }
        finishing = false;
        closed = true;
        Runnable callback = pendingFinishCallback;
        pendingFinishCallback = null;
        iconBreath.stop();
        stage.close();
        if (callback != null) {
            callback.run();
        }
    }

    /** 立即关闭（启动失败或取消时使用）。幂等，可任意线程调用；取消进行中的淡出及其回调。 */
    public void close() {
        if (closed) {
            return;
        }
        runOnFxThread(this::closeOnFxThread);
    }

    private void closeOnFxThread() {
        if (closed) {
            return;
        }
        closed = true;
        pendingFinishCallback = null;
        if (exitFade != null) {
            exitFade.stop();
            exitFade = null;
        }
        iconBreath.stop();
        stage.close();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}

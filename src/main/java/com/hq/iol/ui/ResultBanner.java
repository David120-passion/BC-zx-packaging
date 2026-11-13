package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;

/**
 * 流程结果横幅：执行中（蓝）/ 成功（绿）/ 失败（红），
 * 空闲时透明占位，保持固定高度不挤动布局。任意线程可调用。
 * <p>
 * 显示在卡片标题行右侧，故 <b>不换行</b>：
 * 长文字省略为 …，悬停显示完整内容，
 * 这样横幅出现/消失不会改变标题行高度。
 */
public class ResultBanner {

    private static final String[] STATES = {"result-running", "result-success", "result-fail"};

    private final Label label = new Label();

    public ResultBanner() {
        label.getStyleClass().add("result-banner");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        // 首选宽度设为 0：长文字不会把卡片撑宽，而是在容器分配的宽度内省略
        label.setPrefWidth(0);
        // 显式最小高度：13px 加粗文字 + 12 垂直内边距的胶囊约 30px，
        // 预留 32px 保证空闲（clear 后）与有内容时布局高度一致，不挤动标题行
        label.setMinHeight(32);
    }

    public Node node() {
        return label;
    }

    public void running(String text) {
        set("● " + text, "result-running");
    }

    public void success(String text) {
        set("✓ " + text, "result-success");
    }

    public void fail(String text) {
        set("✗ " + text, "result-fail");
    }

    /** 清除横幅：恢复空闲透明占位。 */
    public void clear() {
        Runnable update = () -> {
            label.setText("");
            label.setTooltip(null);
            label.getStyleClass().removeAll(STATES);
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void set(String text, String style) {
        Runnable update = () -> {
            label.setText(text);
            // 悬停显示去掉状态符号的原始文案
            label.setTooltip(new Tooltip(text.substring(2)));
            label.getStyleClass().removeAll(STATES);
            label.getStyleClass().add(style);
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}

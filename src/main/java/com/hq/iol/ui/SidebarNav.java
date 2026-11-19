package com.hq.iol.ui;

import com.hq.iol.config.AppInfo;
import com.hq.iol.device.printer.PrinterProbe;
import com.hq.iol.device.printer.PrinterProfile;
import com.hq.iol.device.printer.PrinterStatus;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.Map;

/**
 * iOS 设置风格侧边栏导航：顶部应用标识（圆角图标 + 名称）、
 * 中部导航项（彩色圆角图标块 + 文字，选中为白色胶囊）、底部打印机状态与版本号。
 * 页面切换通过 {@link #selectedIndexProperty()} 暴露，由主界面监听后切换内容区。
 */
public class SidebarNav {

    /** 导航项：symbol 为 Unicode 符号（如 ⚖ 🖨），label 为中文名称，chipStyle 为图标块配色 */
    public record NavItem(String symbol, String label, String chipStyle) {
    }

    private final ReadOnlyIntegerWrapper selectedIndex = new ReadOnlyIntegerWrapper(0);
    private final VBox box;
    private final VBox printerStatusBox = new VBox(4);

    public SidebarNav(Image appIcon, List<NavItem> items) {
        // 顶部应用标识：圆角裁剪的图标 + 名称/副标题
        ImageView iconView = new ImageView(appIcon);
        iconView.setFitWidth(40);
        iconView.setFitHeight(40);
        iconView.setPreserveRatio(true);
        Rectangle clip = new Rectangle(40, 40);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        iconView.setClip(clip);

        Label name = new Label("IOL 包装线");
        name.getStyleClass().add("sidebar-title");
        Label subtitle = new Label("包装上位机系统");
        subtitle.getStyleClass().add("sidebar-subtitle");
        VBox nameBox = new VBox(2, name, subtitle);
        nameBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(10, iconView, nameBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 2 6 16 6;");

        // 导航项（彩色圆角图标块 + 文字；ToggleGroup 保证单选，
        // 再次点击已选中项时回弹，不允许出现「无选中」）
        ToggleGroup group = new ToggleGroup();
        VBox navBox = new VBox(4);
        for (int i = 0; i < items.size(); i++) {
            NavItem item = items.get(i);
            ToggleButton btn = new ToggleButton();
            Label glyph = new Label(item.symbol());
            glyph.getStyleClass().add("nav-glyph");
            StackPane chip = new StackPane(glyph);
            chip.getStyleClass().addAll("nav-chip", item.chipStyle());
            Label text = new Label(item.label());
            HBox row = new HBox(10, chip, text);
            row.setAlignment(Pos.CENTER_LEFT);
            btn.setGraphic(row);
            btn.getStyleClass().add("nav-item");
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setToggleGroup(group);
            btn.setUserData(i);
            navBox.getChildren().add(btn);
        }
        if (!navBox.getChildren().isEmpty()) {
            group.selectToggle((ToggleButton) navBox.getChildren().get(0));
        }
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                group.selectToggle(oldToggle); // 回弹：保持始终有一项选中
                return;
            }
            selectedIndex.set((Integer) newToggle.getUserData());
        });

        Label waiting = new Label("打印机状态：等待检测");
        waiting.getStyleClass().add("sidebar-subtitle");
        printerStatusBox.getChildren().add(waiting);
        ScrollPane statusScroll = new ScrollPane(printerStatusBox);
        statusScroll.setFitToWidth(true);
        statusScroll.setPrefViewportHeight(120);
        statusScroll.setMaxHeight(160);
        statusScroll.getStyleClass().add("config-scroll");

        Label runtime = new Label("● 本地运行");
        runtime.getStyleClass().add("sidebar-subtitle");
        Label version = new Label("v" + AppInfo.version());
        version.getStyleClass().add("sidebar-subtitle");
        VBox footer = new VBox(4, statusScroll, runtime, version);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("mes-card");
        footer.setMaxWidth(Double.MAX_VALUE);

        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);

        box = new VBox(0, header, navBox, filler, footer);
        box.getStyleClass().add("sidebar");
    }

    public Parent node() {
        return box;
    }

    public void setPrinterStatuses(List<PrinterProfile> profiles, Map<String, PrinterProbe> statuses) {
        printerStatusBox.getChildren().clear();
        for (PrinterProfile profile : profiles) {
            PrinterProbe probe = statuses.get(profile.id());
            String text;
            String style;
            if (probe == null) {
                text = "等待检测";
                style = "status-pending";
            } else if (!probe.tcpConnected()) {
                text = "未连接";
                style = "mes-fail";
            } else if (probe.status() == null) {
                text = "已连接（无应答）";
                style = "status-warn";
            } else if (probe.status() == 0) {
                text = "就绪";
                style = "mes-ok";
            } else {
                text = "已连接·" + PrinterStatus.describe(probe.status());
                style = "status-warn";
            }
            Label label = new Label("● " + profile.name() + "：" + text);
            label.getStyleClass().addAll("sidebar-subtitle", style);
            label.setWrapText(true);
            label.setMaxWidth(185);
            label.setTooltip(new javafx.scene.control.Tooltip(label.getText()));
            printerStatusBox.getChildren().add(label);
        }
    }

    public ReadOnlyIntegerProperty selectedIndexProperty() {
        return selectedIndex.getReadOnlyProperty();
    }
}

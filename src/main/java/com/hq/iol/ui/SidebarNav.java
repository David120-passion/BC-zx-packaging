package com.hq.iol.ui;

import com.hq.iol.config.AppInfo;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
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

/**
 * iOS 设置风格侧边栏导航：顶部应用标识（圆角图标 + 名称）、
 * 中部导航项（彩色圆角图标块 + 文字，选中为白色胶囊）、底部本地运行状态与版本号。
 * 页面切换通过 {@link #selectedIndexProperty()} 暴露，由主界面监听后切换内容区。
 */
public class SidebarNav {

    /** 导航项：symbol 为 Unicode 符号（如 ⚖ 🖨），label 为中文名称，chipStyle 为图标块配色 */
    public record NavItem(String symbol, String label, String chipStyle) {
    }

    private final ReadOnlyIntegerWrapper selectedIndex = new ReadOnlyIntegerWrapper(0);
    private final VBox box;

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

        // 底部：本地运行状态 + 版本号（仅本机运行时信息，不展示设备在线状态）
        Label runtime = new Label("● 本地运行");
        runtime.getStyleClass().add("sidebar-subtitle");
        Label version = new Label("v" + AppInfo.version());
        version.getStyleClass().add("sidebar-subtitle");
        VBox footer = new VBox(2, runtime, version);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setStyle("-fx-padding: 8 6 2 6;");
        footer.setMaxWidth(Double.MAX_VALUE);

        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);

        box = new VBox(0, header, navBox, filler, footer);
        box.getStyleClass().add("sidebar");
    }

    public Parent node() {
        return box;
    }

    public ReadOnlyIntegerProperty selectedIndexProperty() {
        return selectedIndex.getReadOnlyProperty();
    }
}

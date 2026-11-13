package com.hq.iol.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.BarcodeSupport;
import com.hq.iol.label.DesignElements;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 标签尺寸与组件类型化属性表单，直接编辑当前 design JSON。 */
public class ElementPropertiesPanel {

    private final Runnable onChange;
    private final Runnable onDelete;
    private final Consumer<String> onError;
    private final VBox root = new VBox(12);
    private final VBox labelSettings = new VBox(10);
    private final VBox elementSettings = new VBox(8);
    private final Label elementTitle = new Label("组件属性");
    private final Button deleteButton = new Button("删除组件");
    private final Button chooseImageButton = new Button("选择图片");

    private ObjectNode design;
    private ObjectNode element;
    private Label dotsHint;

    public ElementPropertiesPanel(Runnable onChange, Runnable onDelete, Consumer<String> onError) {
        this.onChange = onChange;
        this.onDelete = onDelete;
        this.onError = onError;
    }

    public Parent build() {
        Label labelTitle = new Label("标签设置");
        labelTitle.getStyleClass().add("card-title");
        VBox labelCard = new VBox(10, labelTitle, labelSettings);
        labelCard.getStyleClass().add("card");

        elementTitle.getStyleClass().add("card-title");
        deleteButton.getStyleClass().add("btn-danger");
        deleteButton.setMaxWidth(Double.MAX_VALUE);
        deleteButton.setOnAction(e -> onDelete.run());
        chooseImageButton.setOnAction(e -> chooseImage());
        VBox elementCard = new VBox(10, elementTitle, new Separator(), elementSettings, deleteButton);
        elementCard.getStyleClass().add("card");
        VBox.setVgrow(elementCard, Priority.ALWAYS);
        root.getChildren().addAll(labelCard, elementCard);
        return root;
    }

    public void setDesign(ObjectNode design) {
        this.design = design;
        labelSettings.getChildren().clear();
        if (design == null) {
            return;
        }
        dotsHint = new Label();
        dotsHint.getStyleClass().add("hint");
        dotsHint.setWrapText(true);
        addRootNumber("宽度 (mm)", "widthMm", 20, 150);
        addRootNumber("高度 (mm)", "heightMm", 10, 100);
        addRootChoice("DPI", "dpi", options("203", 203, "300", 300, "600", 600));
        labelSettings.getChildren().add(dotsHint);
        updateDots();
    }

    public void setElement(ObjectNode element) {
        this.element = element;
        elementSettings.getChildren().clear();
        deleteButton.setDisable(element == null);
        if (element == null) {
            elementTitle.setText("组件属性");
            elementSettings.getChildren().add(empty("请选择画布中的组件"));
            return;
        }
        String type = element.path("type").asText();
        elementTitle.setText(DesignElements.displayName(type) + " · 属性");
        addNumber("X 坐标 (mm)", "x", 0, 150, false);
        addNumber("Y 坐标 (mm)", "y", 0, 100, false);
        switch (type) {
            case "text" -> textProperties();
            case "datamatrix" -> dataMatrixProperties();
            case "barcode" -> barcodeProperties();
            case "qrcode" -> {
                addNumber("尺寸 (mm)", "sizeMm", 2, 100, false);
                addText("内容", "content");
                addChoice("容错级别", "errorLevel", options(
                        "L (7%)", "L", "M (15%)", "M", "Q (25%)", "Q", "H (30%)", "H"));
                addRotation();
            }
            case "line" -> {
                addNumber("长度 (mm)", "lengthMm", 1, 150, false);
                addNumber("粗细 (mm)", "thicknessMm", 0.1, 10, false);
                addChoice("角度", "angle", options("0°", 0, "90°", 90, "180°", 180, "270°", 270));
            }
            case "rect", "circle" -> {
                addSize();
                addNumber("边框 (mm)", "thicknessMm", 0.1, 5, false);
                addBoolean("填充", "fill");
                addRotation();
            }
            case "image" -> {
                addSize();
                addText("来源", "source");
                elementSettings.getChildren().add(wrap("图片文件（≤5 MB）", chooseImageButton));
                addRotation();
            }
            case "datetime" -> {
                addSize();
                addChoice("格式", "format", options(
                        "yyyy-MM-dd", "yyyy-MM-dd",
                        "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm",
                        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss",
                        "HH:mm:ss", "HH:mm:ss"));
                textStyleProperties();
                addRotation();
            }
            case "serial" -> {
                addSize();
                addText("前缀", "prefix");
                addNumber("起始值", "startNum", 1, 999999999, true);
                addNumber("位数", "digits", 1, 10, true);
                textStyleProperties();
                addRotation();
            }
            case "table" -> {
                addNumber("行数", "rows", 1, 20, true);
                addNumber("列数", "cols", 1, 10, true);
                addNumber("单元格宽 (mm)", "cellWidthMm", 1, 50, false);
                addNumber("单元格高 (mm)", "cellHeightMm", 1, 30, false);
                addNumber("边框 (mm)", "thicknessMm", 0.1, 2, false);
                addRotation();
            }
            default -> elementSettings.getChildren().add(empty("未知组件类型：" + type));
        }
    }

    private void textProperties() {
        addSize();
        addText("内容", "content");
        textStyleProperties();
        addChoice("对齐", "textAlign", options("左对齐", "left", "居中", "center", "右对齐", "right"));
        addNumber("字间距 (mm)", "letterSpacingMm", 0, 5, false);
        addRotation();
    }

    private void dataMatrixProperties() {
        addNumber("尺寸 (mm)", "sizeMm", 2, 100, false);
        addNumber("模块宽 (mm)", "moduleMm", 0.1, 5, false);
        if (!element.path("gs1").asBoolean(false)) {
            addText("内容", "content");
        }
        addBoolean("GS1 模式", "gs1", true);
        if (element.path("gs1").asBoolean(false)) {
            addChoice("GS1 模板", "gs1Mode", options("A 线晶体", "lens", "B 线推注器", "injector"));
            elementSettings.getChildren().add(empty("GS1 固定 0°，打印使用原生 c126"));
        } else {
            addRotation();
        }
    }

    private void barcodeProperties() {
        addSize();
        addText("内容", "content");
        Map<String, String> types = new LinkedHashMap<>();
        BarcodeSupport.types().forEach(type -> types.put(type, type));
        addChoice("条码类型", "barcodeType", types);
        addNumber("模块宽 (mm)", "moduleMm", 0.1, 5, false);
        addBoolean("显示文本", "displayText");
        addRotation();
    }

    private void textStyleProperties() {
        Map<String, String> fonts = new LinkedHashMap<>();
        fonts.put("默认", "默认");
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            fonts.put(family, family);
        }
        addChoice("字体", "fontFamily", fonts);
        addNumber("字号 (mm)", "fontSizeMm", 1, 50, false);
        addBoolean("粗体", "bold");
        addBoolean("斜体", "italic");
    }

    private void addSize() {
        addNumber("宽度 (mm)", "widthMm", 1, 150, false);
        addNumber("高度 (mm)", "heightMm", 1, 100, false);
    }

    private void addRotation() {
        addChoice("旋转", "rotation", options("0°", 0, "90°", 90, "180°", 180, "270°", 270));
    }

    private void addText(String label, String field) {
        TextField input = new TextField(element.path(field).asText(""));
        Runnable apply = () -> {
            String value = input.getText();
            if (!value.equals(element.path(field).asText(""))) {
                element.put(field, value);
                onChange.run();
            }
        };
        input.setOnAction(e -> apply.run());
        input.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (!focused) {
                apply.run();
            }
        });
        elementSettings.getChildren().add(wrap(label, input));
    }

    private void addNumber(String label, String field, double min, double max, boolean integer) {
        TextField input = new TextField(element.path(field).asText());
        Runnable apply = () -> {
            try {
                double value = Double.parseDouble(input.getText().trim());
                if (value < min || value > max || (integer && value != Math.rint(value))) {
                    throw new NumberFormatException();
                }
                double previous = element.path(field).asDouble();
                if (previous != value) {
                    if (integer) {
                        element.put(field, (int) value);
                    } else {
                        element.put(field, value);
                    }
                    onChange.run();
                }
            } catch (NumberFormatException e) {
                input.setText(element.path(field).asText());
                onError.accept(label + " 须为 " + min + "–" + max + (integer ? " 的整数" : " 之间的数字"));
            }
        };
        input.setOnAction(e -> apply.run());
        input.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (!focused) {
                apply.run();
            }
        });
        elementSettings.getChildren().add(wrap(label, input));
    }

    private void addBoolean(String label, String field) {
        addBoolean(label, field, false);
    }

    private void addBoolean(String label, String field, boolean rebuild) {
        CheckBox check = new CheckBox();
        check.setSelected(element.path(field).asBoolean(false));
        check.selectedProperty().addListener((obs, oldValue, value) -> {
            element.put(field, value);
            if ("gs1".equals(field) && value) {
                element.put("rotation", 0);
            }
            onChange.run();
            if (rebuild) {
                setElement(element);
            }
        });
        elementSettings.getChildren().add(wrap(label, check));
    }

    private void addChoice(String label, String field, Map<String, ?> options) {
        addChoice(label, field, options, false);
    }

    private void addChoice(String label, String field, Map<String, ?> options, boolean rootField) {
        ComboBox<Choice> combo = new ComboBox<>();
        options.forEach((text, value) -> combo.getItems().add(new Choice(text, value)));
        Object current = rootField ? design.path(field).asInt() : element.path(field).isInt()
                ? element.path(field).asInt() : element.path(field).asText();
        combo.getItems().stream().filter(choice -> choice.value.toString().equals(String.valueOf(current)))
                .findFirst().ifPresentOrElse(combo::setValue, () -> {
                    if (!combo.getItems().isEmpty()) {
                        combo.getSelectionModel().selectFirst();
                    }
                });
        combo.valueProperty().addListener((obs, oldValue, choice) -> {
            if (choice == null) {
                return;
            }
            if (rootField) {
                if (choice.value instanceof Number number) {
                    design.put(field, number.intValue());
                } else {
                    design.put(field, choice.value.toString());
                }
                updateDots();
            } else if (choice.value instanceof Number number) {
                element.put(field, number.intValue());
            } else {
                element.put(field, choice.value.toString());
            }
            onChange.run();
        });
        elementSettings.getChildren().add(wrap(label, combo));
    }

    private void addRootNumber(String label, String field, double min, double max) {
        TextField input = new TextField(design.path(field).asText());
        Runnable apply = () -> {
            try {
                double value = Double.parseDouble(input.getText().trim());
                if (value < min || value > max) {
                    throw new NumberFormatException();
                }
                double stepped = Math.round(value * 2) / 2.0;
                design.put(field, stepped);
                input.setText(String.valueOf(stepped));
                updateDots();
                onChange.run();
            } catch (NumberFormatException e) {
                input.setText(design.path(field).asText());
                onError.accept(label + " 须为 " + min + "–" + max + " 之间的数字");
            }
        };
        input.setOnAction(e -> apply.run());
        input.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (!focused) {
                apply.run();
            }
        });
        labelSettings.getChildren().add(wrap(label, input));
    }

    private void addRootChoice(String label, String field, Map<String, Object> options) {
        ComboBox<Choice> combo = new ComboBox<>();
        options.forEach((text, value) -> combo.getItems().add(new Choice(text, value)));
        int current = design.path(field).asInt(600);
        combo.getItems().stream().filter(choice -> choice.value.equals(current))
                .findFirst().ifPresent(combo::setValue);
        combo.valueProperty().addListener((obs, oldValue, choice) -> {
            if (choice != null) {
                design.put(field, (Integer) choice.value);
                updateDots();
                onChange.run();
            }
        });
        labelSettings.getChildren().add(wrap(label, combo));
    }

    private void updateDots() {
        if (dotsHint == null || design == null) {
            return;
        }
        int dpi = design.path("dpi").asInt(600);
        long width = Math.round(design.path("widthMm").asDouble() * dpi / 25.4);
        long height = Math.round(design.path("heightMm").asDouble() * dpi / 25.4);
        dotsHint.setText("打印点阵：" + width + " × " + height + " dots · 预览固定 4 px/mm");
    }

    private void chooseImage() {
        if (element == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择标签图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片文件", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File file = chooser.showOpenDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            if (file.length() > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("图片大小不能超过 5 MB");
            }
            String mime = Files.probeContentType(file.toPath());
            if (mime == null || !mime.startsWith("image/")) {
                mime = "image/png";
            }
            String source = "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            element.put("source", source);
            setElement(element);
            onChange.run();
        } catch (Exception e) {
            onError.accept("图片加载失败：" + e.getMessage());
        }
    }

    private static <T extends javafx.scene.control.Control> HBox wrap(String label, T input) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        caption.setMinWidth(105);
        caption.setWrapText(true);
        HBox row = new HBox(8, caption, input);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static Label empty(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("empty-state");
        label.setWrapText(true);
        return label;
    }

    private static Map<String, Object> options(Object... values) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            options.put((String) values[i], values[i + 1]);
        }
        return options;
    }

    private record Choice(String label, Object value) {
        @Override
        public String toString() {
            return label;
        }
    }
}

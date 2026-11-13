# IOL UI Rebrand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有四个 JavaFX 功能页重构为参考项目的 Apple 式浅色工业桌面 UI，并交付启动动画、原创 IOL/DataMatrix 图标和完整验证。

**Architecture:** 保留 Spring 服务、SQLite 和设备驱动，以 `MainView` 组合缓存页面、侧边导航和日志抽屉；共享 CSS 与小型 UI 组件承载视觉和状态，启动入口单独协调 Splash 与 Spring 生命周期。图标由 JDK AWT/ImageIO 离线生成，字体复用参考项目资源，不增加运行时依赖。

**Tech Stack:** JDK 21、JavaFX 21.0.2、Spring Boot 3.3.0、JavaFX CSS、JUnit 5、AWT/ImageIO、Maven。

**Spec:** `docs/superpowers/specs/2026-11-13-ui-rebrand-design.md`

## Global Constraints

- 仅重构打印测试、称重测试、系统配置、标签设计，不新增 A/B 线生产页面。
- 不修改设备协议、数据库表结构、模板 JSON 格式和 `PrintService`/`ScaleService` 公开签名。
- 不引入 FXML、WebView、前端构建链、Python、TestFX 或新的运行时/UI 依赖。
- 默认窗口 `1360×900`，最小窗口 `1180×720`；后台 I/O 不进入 FX 应用线程。
- 不显示虚构的 MES、打印机或电子秤连接状态，不伪造重量或操作成功。
- 真实打印、读取稳定重量、去皮/清零不在桌面验收中执行。
- 保留并不覆盖 `docs/superpowers/plans/2026-11-13-label-designer.md` 的未提交修改和 `通讯对接/` 未跟踪资料。
- 不执行 `git commit`、`git reset`、`git rebase` 或 `git push`，除非用户另行明确要求。
- 可独立测试的逻辑先写或扩展失败测试，再实现并运行对应测试；纯 JavaFX 布局保留业务回归并以真实桌面验证，不添加脆弱的截图断言。

## File Map

- `pom.xml`：仅增加 `version.properties` 的定向资源过滤。
- `src/main/resources/app.css`：全局设计令牌和控件/页面样式。
- `src/main/resources/version.properties`：构建期版本注入。
- `src/main/resources/fonts/*`：参考项目的 Medium/Bold 思源黑体。
- `src/main/resources/branding/*`、`icon*.png`、`icon.ico`：原创图标源文件与生成资源。
- `src/main/java/com/hq/iol/config/AppInfo.java`：版本读取及非法占位符回退。
- `src/main/java/com/hq/iol/ui/SidebarNav.java`：应用标识、四个导航项和本地版本信息。
- `src/main/java/com/hq/iol/ui/ResultBanner.java`：跨线程操作状态横幅。
- `src/main/java/com/hq/iol/ui/LogDrawer.java`：全局日志展示，不拥有日志数据。
- `src/main/java/com/hq/iol/ui/SplashScreen.java`：启动动画及其生命周期。
- `src/main/java/com/hq/iol/ui/ConfigInputValidator.java`：纯 Java 配置格式校验。
- `MainApp.java`、`MainView.java`、四个现有 Panel：启动、主框架与页面接入。
- `src/test/java/com/hq/iol/config/AppInfoTest.java`、`src/test/java/com/hq/iol/ui/UiResourcesTest.java`、`ResultBannerTest.java`、`ConfigInputValidatorTest.java`：新增回归测试。

---

### Task 1: 构建期版本与 AppInfo

**Files:**
- Modify: `pom.xml:76-94`
- Create: `src/main/resources/version.properties`
- Create: `src/main/java/com/hq/iol/config/AppInfo.java`
- Test: `src/test/java/com/hq/iol/config/AppInfoTest.java`

**Interfaces:**
- Consumes: Maven `project.version`，当前为 `0.1.0`。
- Produces: `public static String AppInfo.version()`；包级 `static String normalizeVersion(String)` 供测试。

- [ ] **Step 1: 写失败测试**

```java
package com.hq.iol.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppInfoTest {
    @Test
    void readsMavenFilteredVersion() {
        assertEquals("0.1.0", AppInfo.version());
    }

    @Test
    void normalizesMissingAndUnfilteredVersions() {
        assertEquals("dev", AppInfo.normalizeVersion(null));
        assertEquals("dev", AppInfo.normalizeVersion(" @project.version@ "));
        assertEquals("dev", AppInfo.normalizeVersion("${project.version}"));
        assertEquals("1.2.3", AppInfo.normalizeVersion(" 1.2.3 "));
    }
}
```

- [ ] **Step 2: 验证测试因 AppInfo 不存在而失败**

Run: `mvn -Dtest=AppInfoTest test`
Expected: 编译失败，提示 `AppInfo` 不存在。

- [ ] **Step 3: 添加版本资源和读取实现**

`src/main/resources/version.properties`：

```properties
app.version=@project.version@
```

`AppInfo` 使用以下完整实现：

```java
package com.hq.iol.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class AppInfo {
    private static final String VERSION = loadVersion();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    static String normalizeVersion(String value) {
        if (value == null || value.isBlank() || value.contains("@") || value.contains("$")) {
            return "dev";
        }
        return value.trim();
    }

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return normalizeVersion(properties.getProperty("app.version"));
        } catch (java.io.IOException e) {
            return "dev";
        }
    }
}
```

在 `pom.xml` 的 `<build>` 内、`<plugins>` 前加入：

```xml
<resources>
  <resource>
    <directory>src/main/resources</directory>
    <filtering>false</filtering>
    <excludes><exclude>version.properties</exclude></excludes>
  </resource>
  <resource>
    <directory>src/main/resources</directory>
    <filtering>true</filtering>
    <includes><include>version.properties</include></includes>
  </resource>
</resources>
```

- [ ] **Step 4: 运行版本测试和全量回归**

Run: `mvn -Dtest=AppInfoTest test && mvn test`
Expected: 两个命令均 `BUILD SUCCESS`。

---

### Task 2: 原创图标与内嵌字体资源

**Files:**
- Create: `src/main/resources/branding/app-icon.svg`
- Create: `src/main/resources/branding/app-icon-master.png`
- Create: `src/main/resources/icon.png`, `icon-16.png`, `icon-32.png`, `icon-64.png`, `icon-256.png`, `icon.ico`
- Create: `src/main/resources/fonts/NotoSansCJKsc-Medium.otf`, `NotoSansCJKsc-Bold.otf`
- Test: `src/test/java/com/hq/iol/ui/UiResourcesTest.java`

**Interfaces:**
- Consumes: 参考项目两个 OTF 的只读副本；JDK `java.awt` 与 `javax.imageio`。
- Produces: `/icon.png` 为 512×512；`/icon-16.png`、`32`、`64`、`256` 为对应尺寸；`/icon.ico` 包含 16/32/64/256 四张 PNG 编码图像。

- [ ] **Step 1: 写失败的资源测试**

```java
package com.hq.iol.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.DataInputStream;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class UiResourcesTest {
    @Test
    void bundledFontsAndMasterIconExist() throws Exception {
        assertNotNull(getClass().getResource("/fonts/NotoSansCJKsc-Medium.otf"));
        assertNotNull(getClass().getResource("/fonts/NotoSansCJKsc-Bold.otf"));
        assertNotNull(getClass().getResource("/branding/app-icon.svg"));
        var master = ImageIO.read(getClass().getResource("/branding/app-icon-master.png"));
        assertEquals(512, master.getWidth());
        assertEquals(512, master.getHeight());
    }

    @Test
    void windowIconsAndIcoHaveDeclaredSizes() throws Exception {
        for (int size : new int[]{16, 32, 64, 256}) {
            var image = ImageIO.read(getClass().getResource("/icon-" + size + ".png"));
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
        }
        assertEquals(512, ImageIO.read(getClass().getResource("/icon.png")).getWidth());
        try (InputStream in = getClass().getResourceAsStream("/icon.ico");
             DataInputStream data = new DataInputStream(in)) {
            assertEquals(0, Short.reverseBytes(data.readShort()));
            assertEquals(1, Short.reverseBytes(data.readShort()));
            assertEquals(4, Short.reverseBytes(data.readShort()));
            for (int expected : new int[]{16, 32, 64, 256}) {
                int width = data.readUnsignedByte();
                int height = data.readUnsignedByte();
                assertEquals(expected, width == 0 ? 256 : width);
                assertEquals(expected, height == 0 ? 256 : height);
                assertEquals(14, data.skipBytes(14));
            }
        }
    }
}
```

- [ ] **Step 2: 验证资源测试失败**

Run: `mvn -Dtest=UiResourcesTest test`
Expected: `bundledFontsAndMasterIconExist` 因资源不存在而失败。

- [ ] **Step 3: 复制字体并创建 SVG 主稿**

```bash
mkdir -p src/main/resources/fonts src/main/resources/branding
cp "D:/Code/java/hqjx/iot-hq-sd-luxshareI-bg/src/main/resources/fonts/NotoSansCJKsc-Medium.otf" src/main/resources/fonts/
cp "D:/Code/java/hqjx/iot-hq-sd-luxshareI-bg/src/main/resources/fonts/NotoSansCJKsc-Bold.otf" src/main/resources/fonts/
```

SVG 使用 1024 视图框、圆角蓝色底板、中央镜片和右下标签；实际文件内容为：

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#55A7FF"/>
      <stop offset="0.52" stop-color="#1478F2"/>
      <stop offset="1" stop-color="#0757C8"/>
    </linearGradient>
    <linearGradient id="glass" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.98"/>
      <stop offset="1" stop-color="#DDF1FF" stop-opacity="0.90"/>
    </linearGradient>
    <filter id="shadow" x="-30%" y="-30%" width="160%" height="160%">
      <feDropShadow dx="0" dy="22" stdDeviation="24" flood-color="#003E91" flood-opacity="0.25"/>
    </filter>
  </defs>
  <rect x="48" y="48" width="928" height="928" rx="210" fill="url(#bg)"/>
  <g fill="none" stroke="#EAF7FF" stroke-width="34" stroke-linecap="round" opacity="0.92">
    <path d="M270 390 C145 265 105 490 230 548"/>
    <path d="M655 390 C780 265 820 490 695 548"/>
  </g>
  <ellipse cx="462" cy="425" rx="252" ry="176" fill="url(#glass)" filter="url(#shadow)"/>
  <ellipse cx="410" cy="365" rx="118" ry="62" fill="#FFFFFF" opacity="0.42"/>
  <g filter="url(#shadow)">
    <rect x="466" y="492" width="348" height="330" rx="66" fill="#FFFFFF"/>
    <rect x="508" y="535" width="264" height="244" rx="30" fill="#EAF4FF"/>
    <g fill="#0868D8">
      <path d="M532 559h84v24h-60v60h-24z"/>
      <path d="M644 559h24v24h-24zM692 559h24v24h-24zM620 607h24v24h-24zM668 607h48v24h-48zM532 667h24v24h-24zM580 667h24v24h-24zM628 667h24v24h-24zM692 667h24v48h-24zM532 715h24v24h-24zM580 715h60v24h-60zM644 715h24v24h-24z"/>
    </g>
  </g>
</svg>
```

- [ ] **Step 4: 用临时 JDK 生成器输出 PNG/ICO**

创建 `target/GenerateIcons.java`，运行 `java target/GenerateIcons.java`。生成器仅在 `target` 保留，不是项目依赖。完整实现：

```java
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class GenerateIcons {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/resources");
        Files.createDirectories(root.resolve("branding"));
        Map<Integer, byte[]> encoded = new LinkedHashMap<>();
        for (int size : new int[]{16, 32, 64, 256, 512}) {
            BufferedImage image = draw(size);
            String name = size == 512 ? "icon.png" : "icon-" + size + ".png";
            ImageIO.write(image, "png", root.resolve(name).toFile());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            encoded.put(size, bytes.toByteArray());
            if (size == 512) {
                ImageIO.write(image, "png", root.resolve("branding/app-icon-master.png").toFile());
            }
        }
        writeIco(root.resolve("icon.ico"), encoded, new int[]{16, 32, 64, 256});
    }

    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = size / 1024.0;
        g.scale(scale, scale);
        g.setPaint(new GradientPaint(80, 60, new Color(0x55A7FF), 900, 960, new Color(0x0757C8)));
        g.fill(new RoundRectangle2D.Double(48, 48, 928, 928, 210, 210));
        if (size > 32) {
            g.setColor(new Color(234, 247, 255, 235));
            g.setStroke(new BasicStroke(34, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(110, 270, 260, 330, 75, 250, Arc2D.OPEN));
            g.draw(new Arc2D.Double(555, 270, 260, 330, 55, -250, Arc2D.OPEN));
        }
        g.setColor(new Color(238, 248, 255, 248));
        g.fill(new Ellipse2D.Double(210, 249, 504, 352));
        g.setColor(new Color(255, 255, 255, 105));
        g.fill(new Ellipse2D.Double(292, 303, 236, 124));
        g.setColor(new Color(0, 62, 145, 45));
        g.fill(new RoundRectangle2D.Double(466, 514, 348, 330, 66, 66));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(466, 492, 348, 330, 66, 66));
        g.setColor(new Color(0xEAF4FF));
        g.fill(new RoundRectangle2D.Double(508, 535, 264, 244, 30, 30));
        g.setColor(new Color(0x0868D8));
        int cell = size <= 32 ? 48 : 24;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if (row == 0 || col == 0 || (row + col) % 3 == 0) {
                    int x = 532 + col * 48;
                    int y = 559 + row * 40;
                    g.fillRect(x, y, cell, Math.min(cell, 32));
                }
            }
        }
        g.dispose();
        return image;
    }

    private static void writeIco(Path path, Map<Integer, byte[]> images, int[] sizes) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            le16(out, 0); le16(out, 1); le16(out, sizes.length);
            int offset = 6 + sizes.length * 16;
            for (int size : sizes) {
                byte[] png = images.get(size);
                out.write(size == 256 ? 0 : size); out.write(size == 256 ? 0 : size);
                out.write(0); out.write(0); le16(out, 1); le16(out, 32);
                le32(out, png.length); le32(out, offset); offset += png.length;
            }
            for (int size : sizes) out.write(images.get(size));
        }
    }

    private static void le16(DataOutputStream out, int value) throws IOException {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }

    private static void le32(DataOutputStream out, int value) throws IOException {
        le16(out, value & 65535); le16(out, value >>> 16);
    }
}
```

- [ ] **Step 5: 检查实际图像并运行资源测试**

使用 `ReadMediaFile` 查看 `src/main/resources/icon.png` 和 `icon-32.png`，确认无裁切、乱码和不可辨认的小图标。

Run: `mvn -Dtest=UiResourcesTest,AppInfoTest test`
Expected: `BUILD SUCCESS`。

---

### Task 3: 全局主题与共享 UI 组件

**Files:**
- Create: `src/main/resources/app.css`
- Create: `src/main/java/com/hq/iol/ui/ResultBanner.java`
- Create: `src/main/java/com/hq/iol/ui/SidebarNav.java`
- Create: `src/main/java/com/hq/iol/ui/LogDrawer.java`
- Test: `src/test/java/com/hq/iol/ui/ResultBannerTest.java`
- Modify: `src/test/java/com/hq/iol/ui/UiResourcesTest.java`

**Interfaces:**
- Produces: `new ResultBanner()`、`Node node()`、`running/success/fail/clear(String 或无参)`。
- Produces: `new SidebarNav(Image, List<NavItem>)`、`Parent node()`、`ReadOnlyIntegerProperty selectedIndexProperty()`；`NavItem(String symbol, String label, String chipStyle)`。
- Produces: `new LogDrawer(UiLogService)`、`Parent build()`；继续使用 `UiLogService.entries()`。

- [ ] **Step 1: 写横幅状态与线程切换失败测试**

```java
package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ResultBannerTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    void backgroundFailureUpdatesFxLabel() throws Exception {
        AtomicReference<ResultBanner> ref = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        Platform.runLater(() -> { ref.set(new ResultBanner()); created.countDown(); });
        assertTrue(created.await(5, TimeUnit.SECONDS));
        ref.get().fail("打印机未就绪");
        CountDownLatch updated = new CountDownLatch(1);
        Platform.runLater(updated::countDown);
        assertTrue(updated.await(5, TimeUnit.SECONDS));
        Label label = (Label) ref.get().node();
        assertEquals("✗ 打印机未就绪", label.getText());
        assertTrue(label.getStyleClass().contains("result-fail"));
        assertEquals("打印机未就绪", label.getTooltip().getText());
    }
}
```

在 `UiResourcesTest` 增加：

```java
@Test
void themeStylesheetExists() {
    assertNotNull(getClass().getResource("/app.css"));
}
```

- [ ] **Step 2: 验证测试失败**

Run: `mvn -Dtest=ResultBannerTest,UiResourcesTest test`
Expected: `ResultBanner` 不存在导致编译失败；加入组件但未加 CSS 时资源测试必须失败。

- [ ] **Step 3: 建立 CSS 设计基础**

复制参考样式作为基础，再保留本项目需要的类并追加 `page-header`、`page-title`、`weight-value`、`designer-*`、`log-drawer` 样式：

```bash
cp "D:/Code/java/hqjx/iot-hq-sd-luxshareI-bg/src/main/resources/app.css" src/main/resources/app.css
```

追加的关键样式必须为：

```css
.page-title { -fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: -hq-text; }
.page-subtitle { -fx-font-size: 12px; -fx-text-fill: -hq-text-2; }
.page-content { -fx-background-color: transparent; }
.log-drawer { -fx-background-color: -hq-surface; -fx-background-radius: 14; -fx-padding: 8 12 10 12; }
.weight-value { -fx-font-family: "Consolas", "Noto Sans CJK SC Medium", monospace; -fx-font-size: 52px; -fx-font-weight: bold; -fx-text-fill: -hq-text; }
.weight-unit { -fx-font-size: 20px; -fx-text-fill: -hq-text-2; }
.designer-canvas, .designer-canvas > .viewport { -fx-background-color: #E9EAF0; }
.designer-paper { -fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.16), 18, 0, 0, 4); }
.template-badge { -fx-background-color: -hq-accent-tint; -fx-text-fill: -hq-accent; -fx-background-radius: 999; -fx-padding: 2 7 2 7; -fx-font-size: 10px; }
.empty-state { -fx-text-fill: -hq-text-3; -fx-font-size: 13px; -fx-alignment: center; }
.field-error { -fx-text-fill: -hq-red-text; -fx-font-size: 11px; }
```

- [ ] **Step 4: 实现共享组件**

`ResultBanner` 复用参考实现的固定高度、省略号、Tooltip 与 `Platform.runLater` 行为，公开接口保持：

```java
public Node node();
public void running(String text);
public void success(String text);
public void fail(String text);
public void clear();
```

状态文本分别使用 `● `、`✓ `、`✗ `，样式类为 `result-running`、`result-success`、`result-fail`。

`SidebarNav` 使用 `ToggleGroup`，`newToggle == null` 时回选旧项，导航记录为：

```java
public record NavItem(String symbol, String label, String chipStyle) {}
public ReadOnlyIntegerProperty selectedIndexProperty();
```

底部显示 `● 本地运行` 与 `v` + `AppInfo.version()`，不显示设备在线状态。

`LogDrawer` 的构建骨架为：

```java
public Parent build() {
    Label title = new Label("操作日志（最新 500 条）");
    title.getStyleClass().add("card-title");
    ListView<String> list = new ListView<>(logs.entries());
    list.getStyleClass().add("log-list");
    list.setMinHeight(0);
    list.setPrefHeight(150);
    Button toggle = new Button("收起");
    Button clear = new Button("清空");
    clear.setOnAction(e -> logs.entries().clear());
    toggle.setOnAction(e -> {
        boolean show = !list.isVisible();
        list.setVisible(show);
        list.setManaged(show);
        toggle.setText(show ? "收起" : "展开");
    });
    logs.entries().addListener((javafx.collections.ListChangeListener<String>) change ->
            Platform.runLater(() -> list.scrollTo(logs.entries().size() - 1)));
    HBox actions = new HBox(8, toggle, clear);
    BorderPane header = new BorderPane(null, null, actions, null, title);
    VBox root = new VBox(8, header, list);
    root.getStyleClass().add("log-drawer");
    return root;
}
```

为日志单元格添加 `log-error`/`log-warn`、Tooltip 和按最长文本计算的 `prefWidth`，不能省略长错误内容且无横向查看手段。

- [ ] **Step 5: 运行组件与资源测试**

Run: `mvn -Dtest=ResultBannerTest,UiResourcesTest test`
Expected: `BUILD SUCCESS`，FX 测试后 JVM 正常退出。

---

### Task 4: 启动动画与 MainApp 生命周期

**Files:**
- Create: `src/main/java/com/hq/iol/ui/SplashScreen.java`
- Modify: `src/main/java/com/hq/iol/MainApp.java:27-57`

**Interfaces:**
- Consumes: `AppInfo.version()`、Task 2 图标与字体、Task 3 `app.css`。
- Produces: `new SplashScreen(Image)`、`show()`、`updateStatus(String)`、`finish(Runnable)`、`close()`。

- [ ] **Step 1: 添加启动生命周期契约失败测试**

在 `UiResourcesTest` 增加：

```java
@Test
void splashExposesLifecycleContract() throws Exception {
    Class<?> type = Class.forName("com.hq.iol.ui.SplashScreen");
    type.getConstructor(javafx.scene.image.Image.class);
    type.getMethod("show");
    type.getMethod("updateStatus", String.class);
    type.getMethod("finish", Runnable.class);
    type.getMethod("close");
}
```

Run: `mvn -Dtest=UiResourcesTest test`
Expected: `ClassNotFoundException: com.hq.iol.ui.SplashScreen`；实现后通过且不启动 FX Toolkit。

- [ ] **Step 2: 实现 SplashScreen**

使用参考项目透明 Stage、24px 白色卡片、88px 图标、1.0–1.045 呼吸、3px 进度条和任意线程 `updateStatus`。标题固定为 `IOL 包装线上位机`，副标题固定为 `智能包装与标签工作站`，版本为 `v` + `AppInfo.version()`。

必须实现：

```java
public void show();
public void updateStatus(String text);
public void finish(Runnable onFinished);
public void close();
```

`show()` 使用 420ms 淡入/缩放；`finish()` 使用 320ms 淡出，停止 `ScaleTransition`、关闭 Stage 后执行回调；`close()` 幂等并停止无限动画。

- [ ] **Step 3: 重构 MainApp 启动与失败路径**

`start()` 按以下精确顺序：

```java
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
```

`loadIcons()` 依次读取 `/icon-16.png`、`/icon-32.png`、`/icon-64.png`、`/icon-256.png`、`/icon.png`；`prepareMainStage` 设置 `1360×900`、`1180×720`、标题 `IOL 包装线上位机 v` + 版本、Scene 背景 `#F5F5F7` 和 `app.css`。`failStartup` 记录异常、关闭 Splash、显示错误并 `Platform.exit()`；`stop()` 关闭 Spring 并 `Platform.exit()`。

- [ ] **Step 4: 编译并回归**

Run: `mvn test`
Expected: `BUILD SUCCESS`。完整启动动画在 Task 10 用真实桌面检查，不在单元测试中依赖时间睡眠。

---

### Task 5: MainView 侧边导航与全局日志抽屉

**Files:**
- Modify: `src/main/java/com/hq/iol/ui/MainView.java`
- Uses: `SidebarNav`, `LogDrawer`, `ResultBanner` 的 Task 3 接口。

**Interfaces:**
- Consumes: 四个现有 Panel 的 `Parent build()` 和 `UiLogService.entries()`。
- Produces: `MainView.build()` 仍返回 `Parent`，Spring 构造器只增加 UI 组件所需依赖，不增加业务服务依赖。

- [ ] **Step 1: 保留行为回归基线**

Run: `mvn -Dtest=PrintServiceTemplateTest,ConfigServiceTest test`
Expected: 修改前 `BUILD SUCCESS`，用于确认后续只改变 UI 组合。

- [ ] **Step 2: 用缓存页面替换 TabPane**

核心实现采用：

```java
List<Parent> pages = List.of(
        printPanel.build(), scalePanel.build(), configPanel.build(), designerPanel.build());
List<String[]> headings = List.of(
        new String[]{"打印测试", "选择模板、录入变量并验证标签输出"},
        new String[]{"称重测试", "读取 EK-i 稳定重量并执行去皮/清零"},
        new String[]{"系统配置", "管理打印机与电子秤连接参数"},
        new String[]{"标签设计", "编辑模板布局、变量与打印效果"});
StackPane content = new StackPane();
content.getChildren().setAll(pages.get(0));
nav.selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> {
    int index = newIndex.intValue();
    title.setText(headings.get(index)[0]);
    subtitle.setText(headings.get(index)[1]);
    Parent page = pages.get(index);
    content.getChildren().setAll(page);
    FadeTransition fade = new FadeTransition(Duration.millis(180), page);
    fade.setFromValue(0.25);
    fade.setToValue(1);
    fade.play();
});
```

四个导航项为 `▣ 打印测试/chip-blue`、`⚖ 称重测试/chip-green`、`⚙ 系统配置/chip-gray`、`⌗ 标签设计/chip-blue`。若字体缺少符号，改用 `打`、`秤`、`设`、`签` 四字 glyph，不下载图标库。

- [ ] **Step 3: 接入页面标题和 LogDrawer**

`BorderPane.left = nav.node()`；右侧为 `VBox(topBar, content, new LogDrawer(uiLog).build())`。`content` 设置 `Vgrow(ALWAYS)`，页面外边距 `16,18,12,18`，日志抽屉外边距 `0,18,12,18`。移除 `TabPane` 和原全局 `ListView`，不改 `UiLogService` 数据所有权。

- [ ] **Step 4: 编译回归**

Run: `mvn test`
Expected: `BUILD SUCCESS`，无 `TabPane` 重复构建或 Spring 注入错误。

---

### Task 6: 打印测试页重构

**Files:**
- Modify: `src/main/java/com/hq/iol/ui/PrintPanel.java`
- Test: `src/test/java/com/hq/iol/service/PrintServiceTemplateTest.java`（保持不删不改业务断言）

**Interfaces:**
- Consumes: `PrintService.listTemplates()`、`templateVariables(String)`、`queryPrinterStatus()`、`print(String,Map<String,String>)`。
- Produces: 原 `Parent build()`；后台打印只使用名称和变量副本。

- [ ] **Step 1: 先验证业务基线**

Run: `mvn -Dtest=PrintServiceTemplateTest test`
Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 重构卡片与动态变量表单**

左侧 `VBox` 放模板卡和变量卡，右侧约 280px 放操作卡。`ComboBox<LabelTemplate>` 继续使用现有 `cell()`；变量表单保留 `LinkedHashMap<String,TextField>`，每次 `rebuildVars` 清空旧字段并按 `templateVariables()` 重建。空模板使用 `empty-state` 并禁用打印。

- [ ] **Step 3: 加入忙碌状态和不可变快照**

使用 `BooleanProperty busy = new SimpleBooleanProperty(false)`。查询和打印共用 `runAsync`，但每次操作前设置忙碌、结束在 FX 线程清除。打印事件必须执行：

```java
String templateName = t.name();
Map<String, String> vars = new LinkedHashMap<>();
varFields.forEach((key, field) -> vars.put(key, field.getText()));
Map<String, String> snapshot = Map.copyOf(vars);
busy.set(true);
banner.running("正在打印…");
runAsync(() -> {
    try {
        printService.print(templateName, snapshot);
        banner.success("打印任务已下发");
        uiLog.info("已下发打印: " + templateName);
    } catch (Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        banner.fail(message);
        uiLog.error(message);
    } finally {
        Platform.runLater(() -> busy.set(false));
    }
});
```

模板框、变量字段和打印按钮在 `busy` 时禁用；查询状态使用独立后台任务和真实结果，不显示持久“已连接”。

- [ ] **Step 4: 回归打印业务与全量测试**

Run: `mvn -Dtest=PrintServiceTemplateTest test && mvn test`
Expected: `BUILD SUCCESS`，打印服务公开签名未变。

---

### Task 7: 称重测试页重构

**Files:**
- Modify: `src/main/java/com/hq/iol/ui/ScalePanel.java`

**Interfaces:**
- Consumes: `readStableWeight()` 返回 `BigDecimal` 克值；`tare()` 无返回。
- Produces: 原 `Parent build()`；初始重量为 `—`，成功读取后才显示数字。

- [ ] **Step 1: 运行秤协议基线**

Run: `mvn -Dtest=ScaleReadingTest,LineBufferTest test`
Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 实现重量卡和操作状态**

主卡标题为 `稳定重量`，数值 Label 使用 `weight-value`、初始 `—`，单位 Label 固定 `g` 和 `weight-unit`。下方显示串口与超时配置值，注入 `ConfigService` 只用于读取 `SCALE_PORT`、`SCALE_READ_TIMEOUT_MS`，不推断在线状态。

- [ ] **Step 3: 串行化操作并处理结果**

两个操作共用 `BooleanProperty busy`，按钮均绑定 `busy`。读取成功：

```java
BigDecimal weight = scaleService.readStableWeight();
Platform.runLater(() -> weightValue.setText(weight.toPlainString()));
banner.success("稳定重量读取成功");
uiLog.info("稳定重量: " + weight + " g");
```

去皮成功后将重量恢复 `—`，横幅显示 `去皮/清零成功，请重新读取重量`。任何异常显示原始异常信息，重量恢复 `—`，两个按钮在 `finally` 中通过 FX 线程恢复。

- [ ] **Step 4: 回归协议与全量测试**

Run: `mvn -Dtest=ScaleReadingTest,LineBufferTest test && mvn test`
Expected: `BUILD SUCCESS`。

---

### Task 8: 系统配置页与纯 Java 校验

**Files:**
- Create: `src/main/java/com/hq/iol/ui/ConfigInputValidator.java`
- Modify: `src/main/java/com/hq/iol/ui/ConfigPanel.java`
- Test: `src/test/java/com/hq/iol/ui/ConfigInputValidatorTest.java`

**Interfaces:**
- Produces: `static Optional<String> ConfigInputValidator.error(String key, String raw)`。
- Consumes: 现有 `ConfigKeys` 五键和 `ConfigService.get/saveAll`。

- [ ] **Step 1: 写失败校验测试**

```java
package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigInputValidatorTest {
    @Test
    void validatesNumericRanges() {
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "0").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "65536").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "abc").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_PORT, "9100").isEmpty());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_DPI, "-1").isPresent());
        assertTrue(ConfigInputValidator.error(ConfigKeys.PRINTER_DPI, "600").isEmpty());
        assertTrue(ConfigInputValidator.error(ConfigKeys.SCALE_READ_TIMEOUT_MS, "3000").isEmpty());
        assertTrue(ConfigInputValidator.error(ConfigKeys.SCALE_PORT, "COM3").isEmpty());
    }
}
```

- [ ] **Step 2: 验证编译失败**

Run: `mvn -Dtest=ConfigInputValidatorTest test`
Expected: `ConfigInputValidator` 不存在导致编译失败。

- [ ] **Step 3: 实现校验器**

```java
package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import java.util.Optional;

final class ConfigInputValidator {
    private ConfigInputValidator() {
    }

    static Optional<String> error(String key, String raw) {
        if (!ConfigKeys.PRINTER_PORT.equals(key)
                && !ConfigKeys.PRINTER_DPI.equals(key)
                && !ConfigKeys.SCALE_READ_TIMEOUT_MS.equals(key)) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            if (ConfigKeys.PRINTER_PORT.equals(key) && (value < 1 || value > 65535)) {
                return Optional.of("端口须为 1–65535 的整数");
            }
            if (!ConfigKeys.PRINTER_PORT.equals(key) && value <= 0) {
                return Optional.of(ConfigKeys.PRINTER_DPI.equals(key) ? "DPI 须为正整数" : "读取超时须为正整数");
            }
            return Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.of("请输入有效整数");
        }
    }
}
```

- [ ] **Step 4: 重构配置卡并接入校验**

使用 `LinkedHashMap<String,TextField>` 和 `LinkedHashMap<String,Label>` 保存字段及错误标签。打印机卡包含主机、端口、DPI；电子秤卡包含串口、超时。每个字段显示中文名、配置键和单位；错误 Label 使用 `field-error`，无错误时 `visible/managed=false`。

保存时先构造 `LinkedHashMap<String,String> values` 并逐项调用 `error()`；有错误则定位首个错误、设置红色横幅并直接返回，不调用 `saveAll()`。无错误时捕获 `values` 快照并后台保存。还原按钮将每个字段设为 `configService.get(key)` 并清除错误，不写入默认值。

- [ ] **Step 5: 运行校验、配置持久化和全量测试**

Run: `mvn -Dtest=ConfigInputValidatorTest,ConfigServiceTest test && mvn test`
Expected: `BUILD SUCCESS`。

---

### Task 9: 标签设计器工作区与变量打印对话框

**Files:**
- Modify: `src/main/java/com/hq/iol/ui/DesignerPanel.java`
- Test: `src/test/java/com/hq/iol/service/PrintServiceTemplateTest.java`、`TemplateRepositoryTest.java`（沿用现有行为）

**Interfaces:**
- Consumes: 现有 `TemplateRepository`、`PrintService.templateVariables()` 和 `print()`。
- Produces: 原 `Parent build()`；保存与测试打印仍使用 `repo.update(name,json)` 和 `printService.print(name,vars)`。

- [ ] **Step 1: 运行模板业务基线**

Run: `mvn -Dtest=TemplateRepositoryTest,PrintServiceTemplateTest test`
Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 重构三栏工作区**

左栏 200px 为 `ListView<LabelTemplate>` 和新建/删除按钮，单元格显示名称及内置徽标；中栏为保存/测试打印工具栏和 `ScrollPane` 画布；右栏 280px 为属性 `ScrollPane`。`Canvas` 外套 `StackPane paper`，使用 `designer-paper`，画布背景使用 `designer-canvas`。保留 `PX_PER_MM=4.0` 和现有拖拽坐标转换。

`loadTemplate(LabelTemplate template)` 接收列表选中记录，以 `template.name()` 设置 `currentName`、以 `template.designJson()` 解析设计并读取 `template.builtin()` 更新删除按钮；模板切换仍更新画布宽高、清除选中并渲染。空列表显示 `暂无模板，请新建`，空属性显示 `请选择画布中的元素`。

- [ ] **Step 3: 保留保存快照并增加删除确认**

保存使用现有 `designSnapshot/nameSnapshot/json` 模式。删除前：

```java
Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
        "确定删除模板「" + name + "」吗？此操作不可撤销。", ButtonType.CANCEL, ButtonType.OK);
confirm.setTitle("删除模板");
confirm.setHeaderText(null);
if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
    return;
}
```

内置模板在 UI 禁用删除，仓储仍保留最终拒绝。

- [ ] **Step 4: 实现测试打印变量对话框**

点击测试打印时先取 `currentName`，在 FX 线程构建 `Dialog<ButtonType>`；根据 `printService.templateVariables(name)` 创建 `LinkedHashMap<String,TextField>`。确认按钮文本为 `打印`，取消按钮为 `取消`。确认后捕获：

```java
String nameSnapshot = currentName;
Map<String, String> snapshot = new LinkedHashMap<>();
fields.forEach((key, field) -> snapshot.put(key, field.getText()));
Map<String, String> vars = Map.copyOf(snapshot);
```

再后台调用 `printService.print(nameSnapshot, vars)`，结果进入 `ResultBanner` 和日志。无模板时禁用按钮；取消时不创建后台线程、不执行打印。

- [ ] **Step 5: 回归模板与全量测试**

Run: `mvn -Dtest=TemplateRepositoryTest,PrintServiceTemplateTest test && mvn test`
Expected: `BUILD SUCCESS`，模板 JSON 格式与仓储规则未变。

---

### Task 10: 全量回归与真实桌面验收

**Files:**
- Verify only；截图和临时运行文件写入 `target/ui-verification/`，不创建新的需求或报告文件。

**Interfaces:**
- Consumes: 全部前序任务；标准命令 `mvn test`、`mvn javafx:run`。

- [ ] **Step 1: 检查工作区差异与注释一致性**

Run: `git diff --check && git status --short`
Expected: 无空白错误；未覆盖原 `2026-11-13-label-designer.md` 修改和 `通讯对接/` 资料；代码注释不再描述 TabPane 主界面或旧测试打印空变量行为。

- [ ] **Step 2: 执行完整标准测试**

Run: `mvn test`
Expected: `BUILD SUCCESS`，所有既有与新增测试通过；记录实际测试数，不根据计划数字臆造。

- [ ] **Step 3: 启动真实 JavaFX 应用**

Run: `mvn javafx:run`（后台任务）
Expected: 先显示透明启动卡片，再显示主窗口；无 Maven 编译错误、JavaFX 字体错误或 Spring 注入错误。使用 Windows GUI 工具观察，不仅检查进程存在。

- [ ] **Step 4: 检查启动、导航和日志**

实际查看并截图：启动页、打印测试、称重测试、系统配置、标签设计。反复切换四页，确认选中项唯一、输入状态保留、页面无空白。展开/收起并清空日志，确认新日志仍可追加；长错误文本有 Tooltip 或横向滚动。

- [ ] **Step 5: 检查表单与设计器交互**

在配置页输入端口 `0`，确认出现错误且不写库；点击还原文本。设计器打开内置模板、选择元素并小幅拖拽后回到原坐标，打开测试打印变量弹窗并取消；不保存演练改动，不删除模板，不执行真实打印。

- [ ] **Step 6: 检查两种窗口尺寸和退出**

分别在 `1360×900`、`1180×720` 检查四页，无关键按钮遮挡、中文乱码、标签画布挤压整个窗口或全局横向滚动。关闭应用并确认进程退出、无残留 Splash 或后台 UI 线程。

- [ ] **Step 7: 最终核对交付物**

确认以下均存在且已读取检查：`app.css`、字体、SVG、512px 主 PNG、16/32/64/256 PNG、ICO、四个重构页面、Splash、共享组件和测试。最终说明自动化测试与实际桌面验证结果，明确“未执行真实打印和电子秤硬件验收”。

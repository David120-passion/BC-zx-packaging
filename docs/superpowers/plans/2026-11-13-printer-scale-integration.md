# TSC 打印机 + EK-i 电子秤对接 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 Spring Boot + JavaFX 上位机骨架，实现 TSC 打印机（TSPL over TCP:9100，含 GS1 DataMatrix）与 EK-i 电子秤（RS-232）两个设备的对接功能，附最小验证 UI。

**Architecture:** 单模块 Maven 应用。借鉴 `D:\Code\java\hqjx\iot-hq-sd-luxshareI-bg`：MainApp（JavaFX）后台线程引导 Spring、SQLite 配置中心（ConfigKeys/ConfigService/DbInitializer）、纯代码构建 UI。设备层为"接口 + 驱动 + 业务封装"三层，纯逻辑（指令拼装、帧解析、状态字节）全部可脱离硬件单测。

**Tech Stack:** JDK 21 · Spring Boot 3.3.0（web-application-type: none）· JavaFX 21.0.2（win classifier）· jSerialComm 2.11.0 · SQLite（org.xerial:sqlite-jdbc）· spring-jdbc · JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-11-13-iol-packaging-line-host-design.md`

## Global Constraints

- 包根：`com.hq.iol`，子包 `config` / `device.printer` / `device.scale` / `service` / `ui`
- 标签指令 TSPL/TSPL2，走 TCP 9100；打印前必须查 `<ESC>!?` 状态，非 0x00 不下发（防 SN 断号）
- **打印机型号 TSC MH641，600 DPI（1mm ≈ 23.62 dot）**：所有坐标/字高由 PrintService 按 `printer.dpi` 配置的 mm→dot 换算，业务代码不出现裸 dot 值
- GS1：码流开头必须 1 个 FNC1（TSPL 转义 `~1`）；可变长 AI 在末位，中间不需要 FNC1；字面 `~` 转义 `~~`
- TSPL 含中文必须 `CODEPAGE UTF-8`；中文文本必须用 TTF 字体
- 电子秤：9600/8N1、CR LF 分帧（`readLine`）、单位只接受 `g`、US 帧重试至 ST 超时 3s、OL 抛超载
- 所有配置经 ConfigService（SQLite kv），键名 `设备.参数` 风格
- 测试：`mvn test` 全绿才允许提交；设备 IO 层无法单测的部分在任务内给出人工验证步骤
- 提交信息用中文 conventional commits（如 `feat: 打印机状态字节解析`）
- 不做：jpackage 发行 profile、PLC/激光/视觉、数据库对接（后续计划）

---

### Task 1: Git 初始化与项目骨架

**Files:**
- Create: `pom.xml`、`src/main/java/com/hq/iol/MainApp.java`、`src/main/java/com/hq/iol/SpringMain.java`、`src/main/resources/application.yml`、`src/main/resources/logback-spring.xml`、`.gitignore`

**Interfaces:**
- Produces: 可启动的空壳应用；后续任务在 `com.hq.iol` 下加包

- [ ] **Step 1: git init 并把 spec/plan 作为首个提交**

```bash
cd "D:/方案/打印机上位机"
git init
git add docs/
git commit -m "docs: 晶体包装线上位机设计文档与实施计划"
```

`.gitignore` 内容：

```
target/
data/
logs/
*.iml
.idea/
.vscode/
```

- [ ] **Step 2: 编写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
  </parent>

  <groupId>com.hq</groupId>
  <artifactId>iol-packaging-host</artifactId>
  <version>0.1.0</version>
  <name>iol-packaging-host</name>

  <properties>
    <java.version>21</java.version>
    <javafx.version>21.0.2</javafx.version>
    <javafx.platform>win</javafx.platform>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.46.0.0</version>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
      <classifier>${javafx.platform}</classifier>
    </dependency>
    <dependency>
      <groupId>com.fazecast</groupId>
      <artifactId>jSerialComm</artifactId>
      <version>2.11.0</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <release>21</release>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.8</version>
        <configuration>
          <mainClass>com.hq.iol.MainApp</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 编写入口类（借鉴 luxshareI-bg 的桥接方式）**

`src/main/java/com/hq/iol/SpringMain.java`：

```java
package com.hq.iol;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring 启动类，仅由 MainApp 在后台线程引导，不直接运行。 */
@SpringBootApplication
public class SpringMain {
}
```

`src/main/java/com/hq/iol/MainApp.java`：

```java
package com.hq.iol;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** JavaFX 入口：main 启动 FX，后台 daemon 线程引导 Spring，完成后挂载主界面。 */
public class MainApp extends Application {

    private ConfigurableApplicationContext ctx;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("IOL 包装线上位机");
        BorderPane root = new BorderPane(new Label("Spring 启动中…"));
        stage.setScene(new Scene(root, 960, 640));
        stage.show();

        Thread boot = new Thread(() -> {
            try {
                ctx = new SpringApplicationBuilder(SpringMain.class)
                        .web(WebApplicationType.NONE)
                        .headless(false)
                        .run();
                Platform.runLater(() ->
                        root.setCenter(new Label("骨架启动成功（Task 10 替换为主界面）")));
            } catch (Exception e) {
                Platform.runLater(() ->
                        root.setCenter(new Label("Spring 启动失败: " + e.getMessage())));
            }
        });
        boot.setDaemon(true);
        boot.start();
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }
}
```

- [ ] **Step 4: application.yml 与 logback-spring.xml**

`src/main/resources/application.yml`：

```yaml
spring:
  main:
    web-application-type: none
  datasource:
    url: jdbc:sqlite:./data/app.db
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
```

`src/main/resources/logback-spring.xml`：

```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n</pattern></encoder>
  </appender>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>50MB</maxFileSize>
      <maxHistory>30</maxHistory>
      <totalSizeCap>500MB</totalSizeCap>
    </rollingPolicy>
    <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <logger name="com.hq" level="INFO"/>
  <logger name="org.springframework" level="WARN"/>
  <logger name="com.zaxxer.hikari" level="WARN"/>
  <root level="WARN">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="FILE"/>
  </root>
</configuration>
```

- [ ] **Step 5: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/ .gitignore
git commit -m "feat: Spring Boot + JavaFX 项目骨架"
```

---

### Task 2: SQLite 配置中心（ConfigKeys / DbInitializer / ConfigService）

**Files:**
- Create: `src/main/java/com/hq/iol/config/ConfigKeys.java`、`src/main/java/com/hq/iol/config/DbInitializer.java`、`src/main/java/com/hq/iol/config/ConfigService.java`
- Test: `src/test/java/com/hq/iol/config/ConfigServiceTest.java`

**Interfaces:**
- Produces: `ConfigService.get(String key)`、`getInt(String key)`、`saveAll(Map<String,String>)`；键常量 `ConfigKeys.PRINTER_HOST` 等，后续任务全部使用

- [ ] **Step 1: 编写键常量**

`src/main/java/com/hq/iol/config/ConfigKeys.java`：

```java
package com.hq.iol.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配置键常量与默认值。键名风格：设备.参数。 */
public final class ConfigKeys {

    public static final String PRINTER_HOST = "printer.host";
    public static final String PRINTER_PORT = "printer.port";
    public static final String PRINTER_DPI = "printer.dpi";
    public static final String SCALE_PORT = "scale.port";
    public static final String SCALE_READ_TIMEOUT_MS = "scale.readTimeoutMs";

    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(PRINTER_HOST, "192.168.1.100");
        DEFAULTS.put(PRINTER_PORT, "9100");
        DEFAULTS.put(PRINTER_DPI, "600"); // MH641 为 600 DPI
        DEFAULTS.put(SCALE_PORT, "COM3");
        DEFAULTS.put(SCALE_READ_TIMEOUT_MS, "3000");
    }

    private ConfigKeys() {
    }
}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/com/hq/iol/config/ConfigServiceTest.java`：

```java
package com.hq.iol.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigServiceTest {

    @TempDir
    Path dir;

    private ConfigService newService() {
        String url = "jdbc:sqlite:" + dir.resolve("test.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        DbInitializer dbInitializer = new DbInitializer(new JdbcTemplate(ds));
        return new ConfigService(new JdbcTemplate(ds), dbInitializer);
    }

    @Test
    void 未入库的键返回默认值() {
        ConfigService cs = newService();
        assertThat(cs.get(ConfigKeys.PRINTER_HOST)).isEqualTo("192.168.1.100");
        assertThat(cs.getInt(ConfigKeys.PRINTER_PORT)).isEqualTo(9100);
    }

    @Test
    void 保存后覆盖默认值并重建后可读回() {
        ConfigService cs = newService();
        cs.saveAll(Map.of(ConfigKeys.PRINTER_HOST, "10.0.0.8"));
        assertThat(cs.get(ConfigKeys.PRINTER_HOST)).isEqualTo("10.0.0.8");
        assertThat(newService().get(ConfigKeys.PRINTER_HOST)).isEqualTo("10.0.0.8");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=ConfigServiceTest`
Expected: 编译失败，ConfigService/DbInitializer 不存在

- [ ] **Step 4: 实现 DbInitializer 与 ConfigService**

`src/main/java/com/hq/iol/config/DbInitializer.java`：

```java
package com.hq.iol.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 建表先于其他 Bean：ConfigService 以构造器注入本类保证顺序。 */
@Component
public class DbInitializer {

    public DbInitializer(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_config (cfg_key TEXT PRIMARY KEY, cfg_value TEXT)");
    }
}
```

`src/main/java/com/hq/iol/config/ConfigService.java`：

```java
package com.hq.iol.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SQLite kv 配置中心：启动全量加载进内存，键不存在回退 ConfigKeys.DEFAULTS。 */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public ConfigService(JdbcTemplate jdbc, DbInitializer initializer) {
        this.jdbc = jdbc;
        jdbc.query("SELECT cfg_key, cfg_value FROM app_config",
                rs -> cache.put(rs.getString(1), rs.getString(2)));
    }

    public String get(String key) {
        String v = cache.get(key);
        return v != null ? v : ConfigKeys.DEFAULTS.get(key);
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    public void saveAll(Map<String, String> entries) {
        entries.forEach((k, v) -> {
            jdbc.update("INSERT INTO app_config(cfg_key, cfg_value) VALUES(?,?) "
                    + "ON CONFLICT(cfg_key) DO UPDATE SET cfg_value=excluded.cfg_value", k, v);
            cache.put(k, v);
        });
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=ConfigServiceTest`
Expected: 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hq/iol/config/ src/test/java/com/hq/iol/config/
git commit -m "feat: SQLite 配置中心"
```

---

### Task 3: GS1 DataMatrix 码流构造器

**Files:**
- Create: `src/main/java/com/hq/iol/device/printer/Gs1DataMatrix.java`
- Test: `src/test/java/com/hq/iol/device/printer/Gs1DataMatrixTest.java`

**Interfaces:**
- Produces: `Gs1DataMatrix.forLens(String udiDi, LocalDate produced, LocalDate expiry, String serialNo)`、`forInjector(String udiDi, LocalDate produced, LocalDate expiry, String lotNo)`，返回含 FNC1 转义的码流字符串；Task 4 的 TsplLabel 使用

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/device/printer/Gs1DataMatrixTest.java`：

```java
package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gs1DataMatrixTest {

    @Test
    void A线码流_前导FNC1加四段AI_中间无分隔符() {
        String s = Gs1DataMatrix.forLens("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "2605283500");
        assertThat(s).isEqualTo("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
    }

    @Test
    void B线码流_AI10批次号在末位() {
        String s = Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "LOT-7");
        assertThat(s).isEqualTo("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "10" + "LOT-7");
    }

    @Test
    void 内容中的波浪号转义为双波浪号() {
        String s = Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "A~B");
        assertThat(s).endsWith("10A~~B");
    }

    @Test
    void UDIDI非14位数字直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("ABC",
                LocalDate.now(), LocalDate.now(), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UDI-DI");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=Gs1DataMatrixTest`
Expected: 编译失败，Gs1DataMatrix 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/device/printer/Gs1DataMatrix.java`：

```java
package com.hq.iol.device.printer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * GS1 DataMatrix 码流构造器，输出 TSPL DMATRIX 指令所需转义字符串。
 * FNC1 规则：码流首位必须 FNC1；可变长 AI 之后需补 FNC1，定长 AI 之后不需要。
 * 本项目可变长 AI 均位于末位，故只有前导 FNC1。
 */
public final class Gs1DataMatrix {

    /** TSPL 中的 FNC1 转义，见手册 P50。 */
    private static final String FNC1 = "~1";
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Gs1DataMatrix() {
    }

    /** A 线晶体标签：(01) + (11) + (17) + (21)序列号。 */
    public static String forLens(String udiDi, LocalDate produced,
                                 LocalDate expiry, String serialNo) {
        requireDigits(udiDi, 14, "UDI-DI");
        return FNC1 + "01" + udiDi
                + "11" + produced.format(YYMMDD)
                + "17" + expiry.format(YYMMDD)
                + "21" + escape(serialNo);
    }

    /** B 线推注器标签：(01) + (11) + (17) + (10)批次号。 */
    public static String forInjector(String udiDi, LocalDate produced,
                                     LocalDate expiry, String lotNo) {
        requireDigits(udiDi, 14, "UDI-DI");
        return FNC1 + "01" + udiDi
                + "11" + produced.format(YYMMDD)
                + "17" + expiry.format(YYMMDD)
                + "10" + escape(lotNo);
    }

    /** TSPL 中 '~' 为转义前缀，内容里的字面 '~' 须写成 '~~'。 */
    private static String escape(String s) {
        return s.replace("~", "~~");
    }

    private static void requireDigits(String v, int len, String field) {
        if (v == null || v.length() != len || !v.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " 必须为 " + len + " 位数字，实际: " + v);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=Gs1DataMatrixTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/device/printer/Gs1DataMatrix.java src/test/java/com/hq/iol/device/printer/Gs1DataMatrixTest.java
git commit -m "feat: GS1 DataMatrix 码流构造器"
```

---

### Task 4: TSPL 标签指令构造器

**Files:**
- Create: `src/main/java/com/hq/iol/device/printer/TsplLabel.java`
- Test: `src/test/java/com/hq/iol/device/printer/TsplLabelTest.java`

**Interfaces:**
- Consumes: Task 3 `Gs1DataMatrix` 的输出字符串（本任务不直接调用，由 Task 9 PrintService 组装）
- Produces: `TsplLabel.of(w,h,gap)` → `textAscii` / `textCjk` / `gs1DataMatrix` / `box` → `print(sets,copies): String`；Task 9 使用

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/device/printer/TsplLabelTest.java`：

```java
package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TsplLabelTest {

    @Test
    void 标签头包含尺寸间隙方向UTF8与清屏() {
        String tspl = TsplLabel.of(60, 40, 2).print(1, 1);
        assertThat(tspl).startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\n"
                + "DIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void ASCII文本与GS1码与指令顺序() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textAscii(24, 20, "3", 1, "SN 2605283500")
                .gs1DataMatrix(24, 130, 160, 6, "~101123")
                .print(1, 1);
        assertThat(tspl).contains("TEXT 24,20,\"3\",0,1,1,\"SN 2605283500\"\r\n");
        assertThat(tspl).contains("DMATRIX 24,130,160,160,c126,x6,\"~101123\"\r\n");
    }

    @Test
    void 中文文本使用TTF字体() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textCjk(24, 300, "ARIALUNI.TTF", 8, "总直径:12.0")
                .print(1, 1);
        assertThat(tspl).contains("TEXT 24,300,\"ARIALUNI.TTF\",0,8,8,\"总直径:12.0\"\r\n");
    }

    @Test
    void 内容中的双引号被转义() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textAscii(0, 0, "2", 1, "A\"B")
                .print(1, 1);
        assertThat(tspl).contains("\"A\\\"B\"");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=TsplLabelTest`
Expected: 编译失败，TsplLabel 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/device/printer/TsplLabel.java`：

```java
package com.hq.iol.device.printer;

import java.util.ArrayList;
import java.util.List;

/**
 * TSPL2 标签指令构造器。生成的字符串直接写入打印机 Socket 即可。
 * 指令集见《TSPL/TSPL2 Programming Manual》。
 */
public final class TsplLabel {

    private final List<String> lines = new ArrayList<>();

    /**
     * @param widthMm 标签宽度
     * @param heightMm 标签高度
     * @param gapMm 标签间隙，参见手册 GAP 指令
     */
    public static TsplLabel of(double widthMm, double heightMm, double gapMm) {
        TsplLabel l = new TsplLabel();
        l.lines.add("SIZE %s mm,%s mm".formatted(fmt(widthMm), fmt(heightMm)));
        l.lines.add("GAP %s mm,0 mm".formatted(fmt(gapMm)));
        l.lines.add("DIRECTION 1");
        l.lines.add("CODEPAGE UTF-8"); // 标签含中文必须显式设置，手册 P16
        l.lines.add("CLS");
        return l;
    }

    /**
     * 中文文本。字体须用 TTF，点阵字体 "1"–"5" 只覆盖 ASCII，写中文会乱码。
     * 坐标单位 dot（200 DPI: 1mm=8 dot；300 DPI: 1mm=12 dot）。
     */
    public TsplLabel textCjk(int x, int y, String ttf, int heightPt, String content) {
        lines.add("TEXT %d,%d,\"%s\",0,%d,%d,\"%s\""
                .formatted(x, y, ttf, heightPt, heightPt, quote(content)));
        return this;
    }

    /** ASCII 文本，用点阵字体，速度快于 TTF。 */
    public TsplLabel textAscii(int x, int y, String font, int scale, String content) {
        lines.add("TEXT %d,%d,\"%s\",0,%d,%d,\"%s\""
                .formatted(x, y, font, scale, scale, quote(content)));
        return this;
    }

    /** GS1 DataMatrix。c126 启用 GS1 模式，x# 为模块边长（dot）。 */
    public TsplLabel gs1DataMatrix(int x, int y, int sizeDots, int moduleDots, String stream) {
        lines.add("DMATRIX %d,%d,%d,%d,c126,x%d,\"%s\""
                .formatted(x, y, sizeDots, sizeDots, moduleDots, stream));
        return this;
    }

    public TsplLabel box(int x1, int y1, int x2, int y2, int thickness) {
        lines.add("BOX %d,%d,%d,%d,%d".formatted(x1, y1, x2, y2, thickness));
        return this;
    }

    /** 收尾并输出完整指令流。sets 份数 × copies 张。 */
    public String print(int sets, int copies) {
        List<String> all = new ArrayList<>(lines);
        all.add("PRINT %d,%d".formatted(sets, copies));
        return String.join("\r\n", all) + "\r\n";
    }

    /** TSPL 字符串以双引号包裹，内部双引号须转义。 */
    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=TsplLabelTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/device/printer/TsplLabel.java src/test/java/com/hq/iol/device/printer/TsplLabelTest.java
git commit -m "feat: TSPL 标签指令构造器"
```

---

### Task 5: 打印机状态字节解析

**Files:**
- Create: `src/main/java/com/hq/iol/device/printer/PrinterStatus.java`
- Test: `src/test/java/com/hq/iol/device/printer/PrinterStatusTest.java`

**Interfaces:**
- Produces: `PrinterStatus.describe(int status): String`、`PrinterStatus.isReady(int status): boolean`；Task 6 驱动与 UI 使用

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/device/printer/PrinterStatusTest.java`：

```java
package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterStatusTest {

    @Test
    void 仅零状态为就绪() {
        assertThat(PrinterStatus.isReady(0x00)).isTrue();
        assertThat(PrinterStatus.isReady(0x20)).isFalse();
    }

    @Test
    void 手册P82状态表逐项描述() {
        assertThat(PrinterStatus.describe(0x00)).isEqualTo("就绪");
        assertThat(PrinterStatus.describe(0x01)).isEqualTo("打印头开启");
        assertThat(PrinterStatus.describe(0x02)).isEqualTo("卡纸");
        assertThat(PrinterStatus.describe(0x03)).isEqualTo("卡纸且打印头开启");
        assertThat(PrinterStatus.describe(0x04)).isEqualTo("缺纸");
        assertThat(PrinterStatus.describe(0x05)).isEqualTo("缺纸且打印头开启");
        assertThat(PrinterStatus.describe(0x08)).isEqualTo("缺碳带");
        assertThat(PrinterStatus.describe(0x09)).isEqualTo("缺碳带且打印头开启");
        assertThat(PrinterStatus.describe(0x0A)).isEqualTo("缺碳带且卡纸");
        assertThat(PrinterStatus.describe(0x0B)).isEqualTo("缺碳带、卡纸且打印头开启");
        assertThat(PrinterStatus.describe(0x0C)).isEqualTo("碳带用尽");
        assertThat(PrinterStatus.describe(0x0D)).isEqualTo("碳带用尽且打印头开启");
        assertThat(PrinterStatus.describe(0x10)).isEqualTo("暂停");
        assertThat(PrinterStatus.describe(0x20)).isEqualTo("打印中");
        assertThat(PrinterStatus.describe(0x80)).isEqualTo("其他错误");
    }

    @Test
    void 未知状态给出十六进制描述() {
        assertThat(PrinterStatus.describe(0x40)).isEqualTo("未知状态 0x40");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PrinterStatusTest`
Expected: 编译失败，PrinterStatus 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/device/printer/PrinterStatus.java`：

```java
package com.hq.iol.device.printer;

/** TSC 状态字节语义，见《TSPL/TSPL2 Programming Manual》P82（&lt;ESC&gt;!? 应答）。 */
public final class PrinterStatus {

    private PrinterStatus() {
    }

    public static boolean isReady(int status) {
        return status == 0x00;
    }

    public static String describe(int status) {
        return switch (status) {
            case 0x00 -> "就绪";
            case 0x01 -> "打印头开启";
            case 0x02 -> "卡纸";
            case 0x03 -> "卡纸且打印头开启";
            case 0x04 -> "缺纸";
            case 0x05 -> "缺纸且打印头开启";
            case 0x08 -> "缺碳带";
            case 0x09 -> "缺碳带且打印头开启";
            case 0x0A -> "缺碳带且卡纸";
            case 0x0B -> "缺碳带、卡纸且打印头开启";
            case 0x0C -> "碳带用尽";
            case 0x0D -> "碳带用尽且打印头开启";
            case 0x10 -> "暂停";
            case 0x20 -> "打印中";
            case 0x80 -> "其他错误";
            default -> "未知状态 0x%02X".formatted(status);
        };
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=PrinterStatusTest`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/device/printer/PrinterStatus.java src/test/java/com/hq/iol/device/printer/PrinterStatusTest.java
git commit -m "feat: 打印机状态字节解析"
```

---

### Task 6: TSC 打印机驱动（TCP 9100 + 打印前状态检查）

**Files:**
- Create: `src/main/java/com/hq/iol/device/printer/TscPrinterDriver.java`
- Test: `src/test/java/com/hq/iol/device/printer/TscPrinterDriverTest.java`、`src/test/java/com/hq/iol/device/printer/FakeTscPrinterServer.java`

**Interfaces:**
- Consumes: `PrinterStatus.isReady/describe`（Task 5）
- Produces: `new TscPrinterDriver(String host, int port, int connectTimeoutMs)`、`queryStatus(): int`、`print(String tspl)`、`PrinterNotReadyException.status(): int`；Task 9 PrintService 使用

- [ ] **Step 1: 写虚拟打印机测试工具**

`src/test/java/com/hq/iol/device/printer/FakeTscPrinterServer.java`：

```java
package com.hq.iol.device.printer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** 虚拟 TSC 打印机：9100 行为仿真，应答状态字节并记录收到的打印数据。 */
final class FakeTscPrinterServer implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0);
    private final Thread thread;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private volatile int statusToReturn = 0x00;

    FakeTscPrinterServer() {
        thread = new Thread(this::serve);
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void setStatus(int status) {
        this.statusToReturn = status;
    }

    byte[] received() {
        return received.toByteArray();
    }

    private void serve() {
        try {
            Socket s = server.accept();
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == 0x1B) { // ESC：按 3 字节读完 <ESC>!?，回应状态字节
                    in.read();
                    in.read();
                    out.write(statusToReturn);
                    out.flush();
                } else {
                    received.write(b);
                }
            }
        } catch (IOException ignored) {
            // 测试结束关闭时正常退出
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/com/hq/iol/device/printer/TscPrinterDriverTest.java`：

```java
package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TscPrinterDriverTest {

    @Test
    void 状态就绪时下发打印数据() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            d.print("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n");
            assertThat(fake.received())
                    .isEqualTo("SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 状态非就绪时拒绝下发并抛异常() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setStatus(0x04);
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            assertThatThrownBy(() -> d.print("PRINT 1\r\n"))
                    .isInstanceOf(TscPrinterDriver.PrinterNotReadyException.class)
                    .hasMessageContaining("缺纸");
            assertThat(fake.received()).isEmpty();
        }
    }

    @Test
    void 查询状态返回原始字节() throws Exception {
        try (FakeTscPrinterServer fake = new FakeTscPrinterServer()) {
            fake.setStatus(0x10);
            TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", fake.port(), 1000);
            assertThat(d.queryStatus()).isEqualTo(0x10);
        }
    }

    @Test
    void 连接不上抛IOException() {
        TscPrinterDriver d = new TscPrinterDriver("127.0.0.1", 1, 300);
        assertThatThrownBy(() -> d.print("PRINT 1\r\n")).isInstanceOf(IOException.class);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=TscPrinterDriverTest`
Expected: 编译失败，TscPrinterDriver 不存在

- [ ] **Step 4: 实现**

`src/main/java/com/hq/iol/device/printer/TscPrinterDriver.java`：

```java
package com.hq.iol.device.printer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TSC 打印机网口传输通道（RAW 9100）。
 * 打印前查询状态，避免缺纸空打导致序列号断号。非线程安全：调用方串行化打印动作。
 */
public final class TscPrinterDriver {

    /** 状态查询指令 &lt;ESC&gt;!? ，见手册 P82。 */
    private static final byte[] CMD_STATUS = {0x1B, 0x21, 0x3F};

    private final String host;
    private final int port;
    private final int connectTimeoutMs;

    public TscPrinterDriver(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 查询状态字节。 */
    public int queryStatus() throws IOException {
        try (Socket s = openSocket()) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(CMD_STATUS);
            out.flush();
            int status = in.read();
            if (status < 0) {
                throw new IOException("打印机无状态应答: " + host);
            }
            return status;
        }
    }

    /** 查询状态并打印。状态非就绪时抛 PrinterNotReadyException，不下发指令。 */
    public void print(String tspl) throws IOException, PrinterNotReadyException {
        try (Socket s = openSocket()) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(CMD_STATUS);
            out.flush();
            int status = in.read();
            if (status < 0) {
                throw new IOException("打印机无状态应答: " + host);
            }
            if (!PrinterStatus.isReady(status)) {
                throw new PrinterNotReadyException(status);
            }
            out.write(tspl.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private Socket openSocket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(2000);
        return s;
    }

    public static final class PrinterNotReadyException extends Exception {

        private final int status;

        PrinterNotReadyException(int status) {
            super("打印机未就绪: " + PrinterStatus.describe(status));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=TscPrinterDriverTest`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hq/iol/device/printer/TscPrinterDriver.java src/test/java/com/hq/iol/device/printer/
git commit -m "feat: TSC 打印机 TCP 驱动（打印前状态检查防断号）"
```

---

### Task 7: 电子秤帧解析器（纯逻辑）

**Files:**
- Create: `src/main/java/com/hq/iol/device/scale/ScaleReading.java`、`src/main/java/com/hq/iol/device/scale/ScaleProtocolException.java`
- Test: `src/test/java/com/hq/iol/device/scale/ScaleReadingTest.java`

**Interfaces:**
- Produces: `ScaleReading.parse(String raw): ScaleReading`、record 访问器 `status()` / `value()` / `unit()`；Task 8 驱动使用

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/device/scale/ScaleReadingTest.java`：

```java
package com.hq.iol.device.scale;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaleReadingTest {

    @Test
    void 解析稳定重量帧() throws Exception {
        ScaleReading r = ScaleReading.parse("ST,+001234.5   g\r\n");
        assertThat(r.status()).isEqualTo("ST");
        assertThat(r.value()).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(r.unit()).isEqualTo("g");
    }

    @Test
    void 解析负数与超载帧() throws Exception {
        assertThat(ScaleReading.parse("US,-000012.3   g\r\n").status()).isEqualTo("US");
        assertThat(ScaleReading.parse("OL,+999999.9   g\r\n").status()).isEqualTo("OL");
    }

    @Test
    void 小数点被配置为逗号时归一化() throws Exception {
        ScaleReading r = ScaleReading.parse("ST,+001234,5   g\r\n");
        assertThat(r.value()).isEqualByComparingTo(new BigDecimal("1234.5"));
    }

    @Test
    void 帧长度或格式非法抛协议异常() {
        assertThatThrownBy(() -> ScaleReading.parse("GARBAGE"))
                .isInstanceOf(ScaleProtocolException.class);
        assertThatThrownBy(() -> ScaleReading.parse("XX,+001234.5   g\r\n"))
                .isInstanceOf(ScaleProtocolException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ScaleReadingTest`
Expected: 编译失败，ScaleReading 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/device/scale/ScaleProtocolException.java`：

```java
package com.hq.iol.device.scale;

/** 秤返回帧无法解析（帧长度、状态头、数字格式非法）。 */
public class ScaleProtocolException extends Exception {

    public ScaleProtocolException(String raw, String reason) {
        super("秤数据帧非法: [" + raw + "]，原因: " + reason);
    }
}
```

`src/main/java/com/hq/iol/device/scale/ScaleReading.java`：

```java
package com.hq.iol.device.scale;

import java.math.BigDecimal;
import java.util.Set;

/**
 * EK-i 一帧称重数据：16 字符定长 + CR LF。
 * 布局：状态头(2) + ',' + 数据(9，含符号位与小数点，右对齐零填充) + 单位(4，左侧空格填充)。
 */
public record ScaleReading(String status, BigDecimal value, String unit) {

    private static final Set<String> KNOWN_STATUS = Set.of("ST", "QT", "US", "OL");

    public static ScaleReading parse(String raw) throws ScaleProtocolException {
        String line = raw.strip();
        if (line.length() != 16) {
            throw new ScaleProtocolException(raw, "长度应为 16，实际 " + line.length());
        }
        String status = line.substring(0, 2);
        if (!KNOWN_STATUS.contains(status)) {
            throw new ScaleProtocolException(raw, "未知状态头 " + status);
        }
        // 功能项 Pnt 可将小数点配置为逗号，统一归一化
        String numberPart = line.substring(3, 12).trim().replace(',', '.');
        try {
            return new ScaleReading(status, new BigDecimal(numberPart), line.substring(12).trim());
        } catch (NumberFormatException e) {
            throw new ScaleProtocolException(raw, "数据区不是数字: " + numberPart);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ScaleReadingTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/device/scale/ src/test/java/com/hq/iol/device/scale/
git commit -m "feat: EK-i 电子秤帧解析器"
```

---

### Task 8: EK-i 电子秤驱动（jSerialComm IO 层）

**Files:**
- Create: `src/main/java/com/hq/iol/device/scale/EkiScaleDriver.java`、`src/main/java/com/hq/iol/device/scale/ScaleOverloadException.java`、`src/main/java/com/hq/iol/device/scale/ScaleTimeoutException.java`

**Interfaces:**
- Consumes: `ScaleReading.parse`（Task 7）
- Produces: `EkiScaleDriver.open(String portName)`、`readStableGrams(Duration): BigDecimal`、`zero()`、`close()`；Task 9 ScaleService 使用

说明：jSerialComm 需要真实串口，无法纯单测。本任务交付可编译驱动 + 逐项人工验证清单；解析逻辑已在 Task 7 覆盖。

- [ ] **Step 1: 实现异常与驱动**

`src/main/java/com/hq/iol/device/scale/ScaleOverloadException.java`：

```java
package com.hq.iol.device.scale;

import java.math.BigDecimal;

/** 秤返回 OL（超载）。 */
public class ScaleOverloadException extends Exception {

    public ScaleOverloadException(BigDecimal value) {
        super("电子秤超载，读数: " + value);
    }
}
```

`src/main/java/com/hq/iol/device/scale/ScaleTimeoutException.java`：

```java
package com.hq.iol.device.scale;

import java.time.Duration;

/** 在限定时间内未读到稳定（ST/QT）帧。 */
public class ScaleTimeoutException extends Exception {

    public ScaleTimeoutException(Duration timeout) {
        super("等待稳定读数超时（" + timeout.toSeconds() + " 秒）");
    }
}
```

`src/main/java/com/hq/iol/device/scale/EkiScaleDriver.java`：

```java
package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * A&D EK-i / EW-i 系列电子秤，RS-232C 指令模式驱动。
 * 协议见《EK-i 电子秤》手册 9.2 / 9.4 节。非线程安全：每个物理秤一个实例，单线程独占。
 */
public final class EkiScaleDriver implements AutoCloseable {

    /** 单位字段允许值，本项目只接受克。 */
    private static final String UNIT_GRAM = "g";

    private final SerialPort port;
    private final BufferedReader in;
    private final OutputStream out;

    private EkiScaleDriver(SerialPort port) throws IOException {
        this.port = port;
        this.in = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.US_ASCII));
        this.out = port.getOutputStream();
    }

    /**
     * 打开串口。参数须与秤上功能项一致：bPS=2 (9600)、btPr=2 (8N1)。
     *
     * @param portName Windows 为 "COM3"，Linux 为 "/dev/ttyUSB0"
     */
    public static EkiScaleDriver open(String portName) throws IOException {
        SerialPort p = SerialPort.getCommPort(portName);
        p.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED); // 7/8 脚内部短接，无流控
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 1500);
        if (!p.openPort()) {
            throw new IOException("串口打开失败: " + portName);
        }
        return new EkiScaleDriver(p);
    }

    /**
     * 读取一个稳定重量值。内部轮询，跳过 US（不稳定）帧。
     *
     * @param timeout 稳定等待上限，建议 3 秒
     * @throws ScaleOverloadException 秤返回 OL（超载）
     * @throws ScaleTimeoutException 超时仍未稳定
     */
    public BigDecimal readStableGrams(Duration timeout)
            throws IOException, ScaleOverloadException, ScaleTimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ScaleReading r = query();
            if (r == null) {
                continue; // 读超时，重发 Q
            }
            switch (r.status()) {
                case "ST", "QT" -> {
                    if (!UNIT_GRAM.equals(r.unit())) {
                        throw new IOException("秤单位非克，当前为: " + r.unit()
                                + "（检查是否被误按 MODE 键）");
                    }
                    return r.value();
                }
                case "OL" -> throw new ScaleOverloadException(r.value());
                case "US" -> { /* 不稳定，继续轮询 */ }
                default -> throw new IOException("未知状态头: " + r.status());
            }
        }
        throw new ScaleTimeoutException(timeout);
    }

    /** 去皮 / 清零，等同按 RE-ZERO 键。 */
    public void zero() throws IOException {
        send("Z");
        String ack = in.readLine();
        if (ack == null || !"Z".equals(ack.trim())) {
            throw new IOException("清零指令未确认，收到: " + ack);
        }
    }

    /** 发一次 Q 指令并解析应答；读超时返回 null。 */
    private ScaleReading query() throws IOException {
        send("Q");
        String line = in.readLine();
        if (line == null) {
            return null;
        }
        try {
            return ScaleReading.parse(line);
        } catch (ScaleProtocolException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void send(String cmd) throws IOException {
        out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() {
        port.closePort();
    }
}
```

- [ ] **Step 2: 编译 + 全量单测回归**

Run: `mvn -q test`
Expected: 全部 PASS（本任务无新单测，IO 层靠人工验证）

- [ ] **Step 3: 人工验证清单（有真秤时执行，记录结果）**

1. 秤设置 bPS=2、btPr=2、Prt=1、Pnt=0（小数点为点）
2. 串口调试助手发 `Q\r\n`，应收形如 `ST,+00000.0   g\r\n`
3. 程序 open("COM3") → zero() → 放砝码 → readStableGrams(3s) 返回值与砝码一致
4. 称重中晃动使秤不稳 → 验证 US 帧被跳过、稳定后才返回
5. 超量程 → 抛 ScaleOverloadException

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hq/iol/device/scale/
git commit -m "feat: EK-i 电子秤串口驱动（Q 查询 + US 重试 + OL 报警）"
```

---

### Task 9: 业务编排服务（PrintService / ScaleService）

**Files:**
- Create: `src/main/java/com/hq/iol/service/PrintService.java`、`src/main/java/com/hq/iol/service/ScaleService.java`、`src/main/java/com/hq/iol/service/LensLabelData.java`、`src/main/java/com/hq/iol/service/InjectorLabelData.java`
- Test: `src/test/java/com/hq/iol/service/PrintServiceTest.java`

**Interfaces:**
- Consumes: `TscPrinterDriver`、`TsplLabel`、`Gs1DataMatrix`、`EkiScaleDriver`、`ConfigService`
- Produces: `PrintService.buildLensTspl(LensLabelData, int dpi): String`、`buildInjectorTspl(InjectorLabelData, int dpi): String`、`printLens(LensLabelData)`、`printInjector(InjectorLabelData)`、`queryPrinterStatus(): String`；`ScaleService.readStableWeight(): BigDecimal`、`tare()`；Task 10 UI 使用

- [ ] **Step 1: 编写数据记录类**

`src/main/java/com/hq/iol/service/LensLabelData.java`：

```java
package com.hq.iol.service;

import java.time.LocalDate;

/** A 线晶体标签数据。 */
public record LensLabelData(String type, String power, String serialNo,
                            LocalDate sterileDate, String totalDia, String bodyDia,
                            String diNo) {
}
```

`src/main/java/com/hq/iol/service/InjectorLabelData.java`：

```java
package com.hq.iol.service;

import java.time.LocalDate;

/** B 线推注器标签数据。 */
public record InjectorLabelData(String type, String lotNo, LocalDate productionDate,
                                LocalDate expiryDate, String cutDia, String registerNo,
                                String diNo) {
}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/com/hq/iol/service/PrintServiceTest.java`：

```java
package com.hq.iol.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PrintServiceTest {

    private final LensLabelData lens = new LensLabelData("AT2000", "+20.0D", "2605283500",
            LocalDate.of(2026, 5, 28), "12.0", "6.0", "06975897610400");

    private final InjectorLabelData injector = new InjectorLabelData("INJ-5", "B260513",
            LocalDate.of(2026, 5, 13), LocalDate.of(2029, 5, 13), "2.2",
            "国械注准20243160001", "06975897610400");

    @Test
    void A线标签包含文本字段与GS1码流() {
        String tspl = PrintService.buildLensTspl(lens, 600);
        assertThat(tspl).contains("SN 2605283500");
        assertThat(tspl).contains("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
        assertThat(tspl).contains("总直径:12.0");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void B线标签包含批次号AI10与注册号() {
        String tspl = PrintService.buildInjectorTspl(injector, 600);
        assertThat(tspl).contains("~1" + "01" + "06975897610400"
                + "11" + "260513" + "17" + "290513" + "10" + "B260513");
        assertThat(tspl).contains("国械注准20243160001");
    }

    @Test
    void 坐标按DPI换算_600dpi时1mm约24dot() {
        assertThat(PrintService.dot(1, 600)).isEqualTo(24);
        assertThat(PrintService.dot(60, 600)).isEqualTo(1417);
        assertThat(PrintService.dot(1, 200)).isEqualTo(8);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=PrintServiceTest`
Expected: 编译失败，PrintService 不存在

- [ ] **Step 4: 实现 PrintService 与 ScaleService**

`src/main/java/com/hq/iol/service/PrintService.java`：

```java
package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.printer.Gs1DataMatrix;
import com.hq.iol.device.printer.PrinterStatus;
import com.hq.iol.device.printer.TscPrinterDriver;
import com.hq.iol.device.printer.TsplLabel;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** 打印业务编排：按配置组装驱动、生成标签、下发。 */
@Service
public class PrintService {

    private final ConfigService config;

    public PrintService(ConfigService config) {
        this.config = config;
    }

    /** 查询打印机状态描述（"就绪"/"缺纸"/…），连接失败抛 IOException。 */
    public String queryPrinterStatus() throws IOException {
        return PrinterStatus.describe(newDriver().queryStatus());
    }

    public void printLens(LensLabelData data)
            throws IOException, TscPrinterDriver.PrinterNotReadyException {
        newDriver().print(buildLensTspl(data, config.getInt(ConfigKeys.PRINTER_DPI)));
    }

    public void printInjector(InjectorLabelData data)
            throws IOException, TscPrinterDriver.PrinterNotReadyException {
        newDriver().print(buildInjectorTspl(data, config.getInt(ConfigKeys.PRINTER_DPI)));
    }

    private TscPrinterDriver newDriver() {
        return new TscPrinterDriver(config.get(ConfigKeys.PRINTER_HOST),
                config.getInt(ConfigKeys.PRINTER_PORT), 2000);
    }

    /** mm → dot 换算。MH641 为 600 DPI，1mm ≈ 23.62 dot。 */
    static int dot(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    /** 点阵字体放大倍数随 DPI 取整，保证 600 DPI 下字高与 200 DPI 物理一致。 */
    private static int asciiScale(int dpi) {
        return Math.max(1, dpi / 200);
    }

    /** DataMatrix 模块边长：200 DPI 下 x6 ≈ 0.75mm，随 DPI 等比放大。 */
    private static int moduleDots(int dpi) {
        return Math.max(2, (int) Math.round(6.0 * dpi / 200));
    }

    /** A 线晶体标签（60×40mm）。坐标以 mm 书写，按 dpi 换算为 dot。真机微调只动这里的 mm 值。 */
    static String buildLensTspl(LensLabelData info, int dpi) {
        String qr = Gs1DataMatrix.forLens(info.diNo(), info.sterileDate(),
                info.sterileDate().plusYears(5), info.serialNo());
        int s = asciiScale(dpi);
        return TsplLabel.of(60, 40, 2)
                .textAscii(dot(3, dpi), dot(2.5, dpi), "3", s, "# " + info.type())
                .textAscii(dot(3, dpi), dot(7, dpi), "3", s, "D " + info.power())
                .textAscii(dot(3, dpi), dot(11.5, dpi), "3", s, "SN " + info.serialNo())
                .gs1DataMatrix(dot(3, dpi), dot(16, dpi), dot(20, dpi), moduleDots(dpi), qr)
                .textAscii(dot(25, dpi), dot(16.5, dpi), "2", s, "(01)" + info.diNo())
                .textAscii(dot(25, dpi), dot(20, dpi), "2", s, "(21)" + info.serialNo())
                .textCjk(dot(3, dpi), dot(37, dpi), "ARIALUNI.TTF", 8 * s,
                        "总直径:" + info.totalDia() + " 主体直径:" + info.bodyDia())
                .print(1, 1);
    }

    /** B 线推注器标签（60×40mm）。坐标以 mm 书写，按 dpi 换算为 dot。 */
    static String buildInjectorTspl(InjectorLabelData info, int dpi) {
        String qr = Gs1DataMatrix.forInjector(info.diNo(), info.productionDate(),
                info.expiryDate(), info.lotNo());
        int s = asciiScale(dpi);
        return TsplLabel.of(60, 40, 2)
                .textAscii(dot(3, dpi), dot(2.5, dpi), "3", s, "# " + info.type())
                .textAscii(dot(3, dpi), dot(7, dpi), "3", s, "LOT " + info.lotNo())
                .gs1DataMatrix(dot(3, dpi), dot(14, dpi), dot(20, dpi), moduleDots(dpi), qr)
                .textCjk(dot(3, dpi), dot(36, dpi), "ARIALUNI.TTF", 8 * s,
                        "开口:" + info.cutDia() + " 注册号:" + info.registerNo())
                .print(1, 1);
    }
}
```

`src/main/java/com/hq/iol/service/ScaleService.java`：

```java
package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.scale.EkiScaleDriver;
import com.hq.iol.device.scale.ScaleOverloadException;
import com.hq.iol.device.scale.ScaleTimeoutException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

/** 称重业务编排：短连接模式，读一次开一次秤（触发式称重，频率低）。 */
@Service
public class ScaleService {

    private final ConfigService config;

    public ScaleService(ConfigService config) {
        this.config = config;
    }

    public BigDecimal readStableWeight()
            throws IOException, ScaleOverloadException, ScaleTimeoutException {
        try (EkiScaleDriver scale = EkiScaleDriver.open(config.get(ConfigKeys.SCALE_PORT))) {
            return scale.readStableGrams(
                    Duration.ofMillis(config.getInt(ConfigKeys.SCALE_READ_TIMEOUT_MS)));
        }
    }

    public void tare() throws IOException {
        try (EkiScaleDriver scale = EkiScaleDriver.open(config.get(ConfigKeys.SCALE_PORT))) {
            // Task 8 修复轮后签名为 zero(Duration)：清零同样受有界读约束
            scale.zero(Duration.ofMillis(config.getInt(ConfigKeys.SCALE_READ_TIMEOUT_MS)));
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=PrintServiceTest`
Expected: 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hq/iol/service/ src/test/java/com/hq/iol/service/
git commit -m "feat: 打印与称重业务编排服务"
```

---

### Task 10: 最小验证 UI（打印测试 / 称重测试 / 系统配置）

**Files:**
- Create: `src/main/java/com/hq/iol/ui/UiLogService.java`、`src/main/java/com/hq/iol/ui/MainView.java`、`src/main/java/com/hq/iol/ui/PrintPanel.java`、`src/main/java/com/hq/iol/ui/ScalePanel.java`、`src/main/java/com/hq/iol/ui/ConfigPanel.java`
- Modify: `src/main/java/com/hq/iol/MainApp.java`（把占位 Label 换成 `ctx.getBean(MainView.class).build()`）

**Interfaces:**
- Consumes: `PrintService`、`ScaleService`、`ConfigService`、`ConfigKeys`
- Produces: 可人工验收的完整应用

- [ ] **Step 1: UiLogService（业务事件双写 slf4j + FX 列表）**

`src/main/java/com/hq/iol/ui/UiLogService.java`：

```java
package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 操作日志：slf4j 落盘 + FX ObservableList 上屏，上限 500 条。 */
@Service
public class UiLogService {

    private static final Logger log = LoggerFactory.getLogger("ui");
    private static final int MAX = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObservableList<String> entries = FXCollections.observableArrayList();

    public ObservableList<String> entries() {
        return entries;
    }

    public void info(String msg) {
        log.info(msg);
        append("[INFO] " + msg);
    }

    public void error(String msg) {
        log.error(msg);
        append("[ERROR] " + msg);
    }

    private void append(String line) {
        String stamped = LocalTime.now().format(TIME) + " " + line;
        Runnable r = () -> {
            entries.add(stamped);
            while (entries.size() > MAX) {
                entries.remove(0);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }
}
```

- [ ] **Step 2: 三个面板 + 主视图**

`src/main/java/com/hq/iol/ui/PrintPanel.java`：

```java
package com.hq.iol.ui;

import com.hq.iol.service.InjectorLabelData;
import com.hq.iol.service.LensLabelData;
import com.hq.iol.service.PrintService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 打印测试面板：A/B 线表单 + 状态查询 + 打印按钮。 */
@Component
public class PrintPanel {

    private final PrintService printService;
    private final UiLogService uiLog;

    public PrintPanel(PrintService printService, UiLogService uiLog) {
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        TextField type = new TextField("AT2000");
        TextField power = new TextField("+20.0D");
        TextField sn = new TextField("2605283500");
        TextField diNo = new TextField("06975897610400");
        TextField totalDia = new TextField("12.0");
        TextField bodyDia = new TextField("6.0");
        TextField lotNo = new TextField("B260513");
        g.addRow(0, new Label("型号"), type, new Label("度数"), power);
        g.addRow(1, new Label("序列号"), sn, new Label("UDI-DI"), diNo);
        g.addRow(2, new Label("总直径"), totalDia, new Label("主体直径"), bodyDia);
        g.addRow(3, new Label("批号(B线)"), lotNo);

        Button statusBtn = new Button("查询打印机状态");
        statusBtn.setOnAction(e -> runAsync(() ->
                uiLog.info("打印机状态: " + printService.queryPrinterStatus())));
        Button lensBtn = new Button("打印 A 线晶体标签");
        lensBtn.setOnAction(e -> runAsync(() -> {
            printService.printLens(new LensLabelData(type.getText(), power.getText(),
                    sn.getText(), LocalDate.now(), totalDia.getText(), bodyDia.getText(),
                    diNo.getText()));
            uiLog.info("A 线标签已下发: SN=" + sn.getText());
        }));
        Button injBtn = new Button("打印 B 线推注器标签");
        injBtn.setOnAction(e -> runAsync(() -> {
            printService.printInjector(new InjectorLabelData(type.getText(), lotNo.getText(),
                    LocalDate.now(), LocalDate.now().plusYears(3), "2.2",
                    "国械注准20243160001", diNo.getText()));
            uiLog.info("B 线标签已下发: LOT=" + lotNo.getText());
        }));
        g.addRow(4, statusBtn, lensBtn, injBtn);
        return g;
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
```

`src/main/java/com/hq/iol/ui/ScalePanel.java`：

```java
package com.hq.iol.ui;

import com.hq.iol.service.ScaleService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 称重测试面板：读稳定重量 + 去皮。 */
@Component
public class ScalePanel {

    private final ScaleService scaleService;
    private final UiLogService uiLog;

    public ScalePanel(ScaleService scaleService, UiLogService uiLog) {
        this.scaleService = scaleService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Button readBtn = new Button("读取稳定重量");
        readBtn.setOnAction(e -> runAsync(() -> {
            BigDecimal w = scaleService.readStableWeight();
            uiLog.info("稳定重量: " + w + " g");
        }));
        Button tareBtn = new Button("去皮/清零");
        tareBtn.setOnAction(e -> runAsync(() -> {
            scaleService.tare();
            uiLog.info("已去皮");
        }));
        return new HBox(8, new Label("EK-i:"), readBtn, tareBtn);
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
```

`src/main/java/com/hq/iol/ui/ConfigPanel.java`：

```java
package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统配置面板：展示并保存 ConfigKeys.DEFAULTS 中的全部键。 */
@Component
public class ConfigPanel {

    private final ConfigService configService;
    private final UiLogService uiLog;

    public ConfigPanel(ConfigService configService, UiLogService uiLog) {
        this.configService = configService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        Map<String, TextField> fields = new LinkedHashMap<>();
        int row = 0;
        for (String key : ConfigKeys.DEFAULTS.keySet()) {
            TextField tf = new TextField(configService.get(key));
            fields.put(key, tf);
            g.addRow(row++, new Label(key), tf);
        }
        Button save = new Button("保存");
        save.setOnAction(e -> {
            Map<String, String> values = new LinkedHashMap<>();
            fields.forEach((k, tf) -> values.put(k, tf.getText()));
            configService.saveAll(values);
            uiLog.info("配置已保存");
        });
        g.addRow(row, save, new Label());
        return g;
    }
}
```

`src/main/java/com/hq/iol/ui/MainView.java`：

```java
package com.hq.iol.ui;

import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

/** 主界面：上方 Tab 面板，下方操作日志列表。 */
@Component
public class MainView {

    private final PrintPanel printPanel;
    private final ScalePanel scalePanel;
    private final ConfigPanel configPanel;
    private final UiLogService uiLog;

    public MainView(PrintPanel printPanel, ScalePanel scalePanel,
                    ConfigPanel configPanel, UiLogService uiLog) {
        this.printPanel = printPanel;
        this.scalePanel = scalePanel;
        this.configPanel = configPanel;
        this.uiLog = uiLog;
    }

    public Parent build() {
        TabPane tabs = new TabPane(
                new Tab("打印测试", printPanel.build()),
                new Tab("称重测试", scalePanel.build()),
                new Tab("系统配置", configPanel.build()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        ListView<String> logView = new ListView<>(uiLog.entries());
        VBox.setVgrow(logView, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(tabs);
        root.setCenter(logView);
        return root;
    }
}
```

- [ ] **Step 3: MainApp 挂载主视图**

把 `MainApp.start()` 中 `Platform.runLater` 的占位 Label 替换为：

```java
Platform.runLater(() -> root.setCenter(ctx.getBean(MainView.class).build()));
```

- [ ] **Step 4: 全量回归 + 启动验证**

Run: `mvn -q test`
Expected: 全部 PASS

Run: `mvn javafx:run`
Expected: 窗口出现三个 Tab 与日志区；无真机时点击按钮在日志区看到连接异常信息（证明链路通）

- [ ] **Step 5: 人工验收清单**

1. 配置页改 `printer.host` 为真机 IP，保存后重启生效
2. 打印测试页点"查询打印机状态"→ 日志显示"就绪"；打印 A 线标签 → 出纸且**扫码枪回读 GS1 码 AI 解析正确**
3. 拔掉打印机网线再打印 → 日志报连接异常，不产生空打
4. 称重测试页：去皮 → 放砝码 → 读取值一致；晃动验证 US 重试
5. `mvn test` 全绿

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hq/iol/ui/ src/main/java/com/hq/iol/MainApp.java
git commit -m "feat: 打印/称重/配置最小验证 UI"
```

---

## 验收汇总

- `mvn test` 全绿（Task 2/3/4/5/6/7/9 共 24 个用例）
- `mvn javafx:run` 启动，三 Tab 可用
- 真机验收：Task 8 Step 3（秤）与 Task 10 Step 5（打印机 + 扫码枪）全部通过

# 标签设计器 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 BC-zx-packaging 增加可视化标签设计器（SQLite 模板存储 + 混合渲染管线 + JavaFX 设计器），接管现有硬编码 A/B 线标签。

**Architecture:** 唯一 `LabelRenderer` 同时产出预览图与打印 TSPL；文本/图片走共享位图 GRAPH，条码/二维码/DataMatrix 走原生指令；模板存 SQLite，变量 `{{var}}` 占位。

**Tech Stack:** JDK 21 · Spring Boot 3.3 · JavaFX 21 · Jackson · ZXing core 3.5.3 · JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-11-13-label-designer-design.md`

## Global Constraints

- 包 `com.hq.iol.label`（UI 在 `com.hq.iol.ui`）；坐标全部 mm，`dot(mm,dpi)` 换算集中在 LabelRenderer
- GS1 码流只能由 `Gs1DataMatrix` 拼（前导 FNC1、输入白名单校验），渲染器不自行拼 GS1
- 打印下发必须走 `TscPrinterDriver.print()`（含 `<ESC>!?` 状态检查），UI IO 走后台 daemon 线程
- 内置模板（builtin=1）不可删除；变量无值时保留 `{{var}}` 原样
- 条码只做 CODE128；元素 rotation 一期不支持
- 测试：`mvn test` 全绿才允许提交；提交信息用中文 conventional commits
- 设计澄清（spec 基础上的细化，执行时以此为准）：
  - datamatrix 元素 `gs1:true` 时增加属性 `gs1Mode:"lens"|"injector"`；lens 绑定变量 `diNo/sterileDate/serialNo`（有效期=灭菌日期+5年），injector 绑定 `diNo/productionDate/expiryDate/lotNo`，日期为 ISO 格式
  - 预览中 GS1 的 FNC1 用 `\u001d` 近似（ZXing 无法真编码 FNC1；打印路径是原生 c126，无此问题）
  - 预览缩放固定 4 px/mm

---

### Task 1: 依赖与模板存储（TemplateRepository）

**Files:**
- Modify: `pom.xml`（加依赖）
- Modify: `src/main/java/com/hq/iol/config/DbInitializer.java`
- Create: `src/main/java/com/hq/iol/label/LabelTemplate.java`、`src/main/java/com/hq/iol/label/TemplateRepository.java`
- Test: `src/test/java/com/hq/iol/label/TemplateRepositoryTest.java`

**Interfaces:**
- Produces: `LabelTemplate(long id, String name, String designJson, boolean builtin, String updatedAt)` record；`TemplateRepository.findAll(): List<LabelTemplate>`、`findByName(String)`、`insert(String name, String designJson, boolean builtin)`、`update(String name, String designJson)`、`delete(String name)`（拒绝内置）、`seed(String name, String designJson)`（不存在才插入）

- [ ] **Step 1: pom 加依赖**

在 `<dependencies>` 末尾追加：

```xml
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.google.zxing</groupId>
      <artifactId>core</artifactId>
      <version>3.5.3</version>
    </dependency>
```

（jackson 版本由 spring-boot parent 管理，不写版本号）

- [ ] **Step 2: DbInitializer 加建表**

`DbInitializer` 构造器内追加：

```java
        jdbc.execute("CREATE TABLE IF NOT EXISTS label_template ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT UNIQUE NOT NULL,"
                + "design_json TEXT NOT NULL,"
                + "builtin INTEGER NOT NULL DEFAULT 0,"
                + "updated_at TEXT NOT NULL)");
```

- [ ] **Step 3: 写失败测试**

`src/test/java/com/hq/iol/label/TemplateRepositoryTest.java`：

```java
package com.hq.iol.label;

import com.hq.iol.config.DbInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRepositoryTest {

    @TempDir
    Path dir;

    private TemplateRepository newRepo() {
        String url = "jdbc:sqlite:" + dir.resolve("t.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        DbInitializer init = new DbInitializer(new JdbcTemplate(ds));
        return new TemplateRepository(new JdbcTemplate(ds), init);
    }

    @Test
    void 插入后可按名查询且字段完整() {
        TemplateRepository repo = newRepo();
        repo.insert("A线晶体标签", "{\"widthMm\":60}", true);
        LabelTemplate t = repo.findByName("A线晶体标签");
        assertThat(t.name()).isEqualTo("A线晶体标签");
        assertThat(t.designJson()).isEqualTo("{\"widthMm\":60}");
        assertThat(t.builtin()).isTrue();
        assertThat(t.updatedAt()).isNotBlank();
    }

    @Test
    void 更新会刷新设计稿与同名冲突抛错() {
        TemplateRepository repo = newRepo();
        repo.insert("t1", "{}", false);
        repo.update("t1", "{\"v\":2}");
        assertThat(repo.findByName("t1").designJson()).isEqualTo("{\"v\":2}");
        assertThatThrownBy(() -> repo.insert("t1", "{}", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 内置模板禁止删除_seed幂等() {
        TemplateRepository repo = newRepo();
        repo.seed("内置", "{}", true);
        assertThatThrownBy(() -> repo.delete("内置"))
                .isInstanceOf(IllegalStateException.class);
        repo.seed("内置", "{\"changed\":1}", true);
        assertThat(repo.findByName("内置").designJson()).isEqualTo("{}");
        repo.delete("t1_none"); // 不存在也不抛错
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `mvn test -Dtest=TemplateRepositoryTest`
Expected: 编译失败，LabelTemplate/TemplateRepository 不存在

- [ ] **Step 5: 实现**

`src/main/java/com/hq/iol/label/LabelTemplate.java`：

```java
package com.hq.iol.label;

/** 标签模板记录。designJson 为权威设计稿。 */
public record LabelTemplate(long id, String name, String designJson,
                            boolean builtin, String updatedAt) {
}
```

`src/main/java/com/hq/iol/label/TemplateRepository.java`：

```java
package com.hq.iol.label;

import com.hq.iol.config.DbInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 标签模板 CRUD。内置模板（builtin=1）禁止删除；seed 幂等。 */
@Repository
public class TemplateRepository {

    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc, DbInitializer initializer) {
        this.jdbc = jdbc;
    }

    public List<LabelTemplate> findAll() {
        return jdbc.query("SELECT id,name,design_json,builtin,updated_at FROM label_template ORDER BY id",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)));
    }

    public LabelTemplate findByName(String name) {
        List<LabelTemplate> list = jdbc.query(
                "SELECT id,name,design_json,builtin,updated_at FROM label_template WHERE name=?",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)), name);
        if (list.isEmpty()) {
            throw new IllegalStateException("模板不存在: " + name);
        }
        return list.get(0);
    }

    public void insert(String name, String designJson, boolean builtin) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM label_template WHERE name=?",
                Integer.class, name) > 0) {
            throw new IllegalStateException("模板已存在: " + name);
        }
        jdbc.update("INSERT INTO label_template(name,design_json,builtin,updated_at) VALUES(?,?,?,?)",
                name, designJson, builtin ? 1 : 0, Instant.now().toString());
    }

    public void update(String name, String designJson) {
        int n = jdbc.update("UPDATE label_template SET design_json=?, updated_at=? WHERE name=?",
                designJson, Instant.now().toString(), name);
        if (n == 0) {
            throw new IllegalStateException("模板不存在: " + name);
        }
    }

    /** 同名模板已存在则拒绝（含内置模板，内置模板内容升级用迁移而非 seed）。 */
    public void seed(String name, String designJson, boolean builtin) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM label_template WHERE name=?",
                Integer.class, name) == 0) {
            jdbc.update("INSERT INTO label_template(name,design_json,builtin,updated_at) VALUES(?,?,?,?)",
                    name, designJson, builtin ? 1 : 0, Instant.now().toString());
        }
    }

    public void delete(String name) {
        LabelTemplate t = findByNameOrNull(name);
        if (t == null) {
            return;
        }
        if (t.builtin()) {
            throw new IllegalStateException("内置模板不可删除: " + name);
        }
        jdbc.update("DELETE FROM label_template WHERE name=?", name);
    }

    private LabelTemplate findByNameOrNull(String name) {
        List<LabelTemplate> list = jdbc.query(
                "SELECT id,name,design_json,builtin,updated_at FROM label_template WHERE name=?",
                (rs, n) -> new LabelTemplate(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4) == 1, rs.getString(5)), name);
        return list.isEmpty() ? null : list.get(0);
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=TemplateRepositoryTest`
Expected: 3 tests PASS

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/hq/iol/config/DbInitializer.java src/main/java/com/hq/iol/label/ src/test/java/com/hq/iol/label/
git commit -m "feat: 标签模板 SQLite 存储"
```

---

### Task 2: 变量提取与替换（VariableSupport）

**Files:**
- Create: `src/main/java/com/hq/iol/label/VariableSupport.java`
- Test: `src/test/java/com/hq/iol/label/VariableSupportTest.java`

**Interfaces:**
- Produces: `VariableSupport.extract(String designJson): Set<String>`（扫全部 content/source 字段）、`VariableSupport.replace(String text, Map<String,String> vars): String`（无值保留原样）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/label/VariableSupportTest.java`：

```java
package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VariableSupportTest {

    @Test
    void 提取designJson中全部变量() {
        String json = "{\"elements\":["
                + "{\"type\":\"text\",\"content\":\"SN {{serialNo}}\"},"
                + "{\"type\":\"image\",\"source\":\"{{logoPath}}\"},"
                + "{\"type\":\"text\",\"content\":\"{{serialNo}} 和 {{type}}\"}]}";
        assertThat(VariableSupport.extract(json))
                .isEqualTo(Set.of("serialNo", "logoPath", "type"));
    }

    @Test
    void 替换有值变量_无值保留原样() {
        String out = VariableSupport.replace("SN {{serialNo}} / {{missing}}",
                Map.of("serialNo", "2605283500"));
        assertThat(out).isEqualTo("SN 2605283500 / {{missing}}");
    }

    @Test
    void 花括号不成对与普通文本不受影响() {
        assertThat(VariableSupport.replace("100% {ok}", Map.of())).isEqualTo("100% {ok}");
        assertThat(VariableSupport.extract("[]")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=VariableSupportTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/label/VariableSupport.java`：

```java
package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模板变量 {{var}}：提取与替换。无值保留原样（与 MES 对接约定一致）。 */
public final class VariableSupport {

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)\\}\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariableSupport() {
    }

    /** 提取 design JSON 全部元素 content/source 字段中的变量名。 */
    public static Set<String> extract(String designJson) {
        Set<String> vars = new LinkedHashSet<>();
        try {
            JsonNode elements = MAPPER.readTree(designJson).path("elements");
            for (JsonNode el : elements) {
                collect(el.path("content").asText(""), vars);
                collect(el.path("source").asText(""), vars);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
        return vars;
    }

    private static void collect(String text, Set<String> vars) {
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            vars.add(m.group(1));
        }
    }

    /** 替换单段文本中的变量；vars 中无此键时保留 {{var}} 原样。 */
    public static String replace(String text, Map<String, String> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb,
                    v != null ? Matcher.quoteReplacement(v) : m.group(0));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=VariableSupportTest`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/label/VariableSupport.java src/test/java/com/hq/iol/label/VariableSupportTest.java
git commit -m "feat: 模板变量提取与替换"
```

---

### Task 3: 内置模板播种（LabelSeed）

**Files:**
- Create: `src/main/java/com/hq/iol/label/LabelSeed.java`
- Test: `src/test/java/com/hq/iol/label/LabelSeedTest.java`

**Interfaces:**
- Consumes: `TemplateRepository.seed`
- Produces: `LabelSeed.LENS_NAME = "A线晶体标签"`、`LabelSeed.INJECTOR_NAME = "B线推注器标签"`、`LabelSeed.seedAll(TemplateRepository)`；两个 design JSON 常量（Task 7 播种调用、Task 5 测试使用）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/label/LabelSeedTest.java`：

```java
package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabelSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 两个内置模板JSON可解析且结构完整() throws Exception {
        for (String json : new String[]{LabelSeed.LENS_JSON, LabelSeed.INJECTOR_JSON}) {
            JsonNode root = MAPPER.readTree(json);
            assertThat(root.path("dpi").asInt()).isEqualTo(600);
            assertThat(root.path("widthMm").asDouble()).isEqualTo(60);
            assertThat(root.path("heightMm").asDouble()).isEqualTo(40);
            assertThat(root.path("elements").isArray()).isTrue();
            assertThat(root.path("elements").size()).isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void A线模板含GS1镜头模式DataMatrix与变量() {
        assertThat(LabelSeed.LENS_JSON).contains("\"type\":\"datamatrix\"");
        assertThat(LabelSeed.LENS_JSON).contains("\"gs1Mode\":\"lens\"");
        assertThat(VariableSupport.extract(LabelSeed.LENS_JSON))
                .contains("serialNo", "diNo", "sterileDate", "type", "power", "totalDia", "bodyDia");
    }

    @Test
    void B线模板含injector模式与注册号变量() {
        assertThat(LabelSeed.INJECTOR_JSON).contains("\"gs1Mode\":\"injector\"");
        assertThat(VariableSupport.extract(LabelSeed.INJECTOR_JSON))
                .contains("lotNo", "diNo", "productionDate", "expiryDate", "registerNo", "cutDia", "type");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=LabelSeedTest`
Expected: 编译失败，LabelSeed 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/label/LabelSeed.java`：

```java
package com.hq.iol.label;

/** 内置模板（A/B 线标签），布局复刻原硬编码 PrintService 的 mm 坐标。 */
public final class LabelSeed {

    public static final String LENS_NAME = "A线晶体标签";
    public static final String INJECTOR_NAME = "B线推注器标签";

    public static final String LENS_JSON = """
            {"version":1,"dpi":600,"widthMm":60,"heightMm":40,"elements":[
            {"id":"e1","type":"text","x":3,"y":2.5,"fontSizeMm":3.5,"fontFamily":"默认","bold":false,"content":"# {{type}}"},
            {"id":"e2","type":"text","x":3,"y":7,"fontSizeMm":3.5,"fontFamily":"默认","bold":false,"content":"D {{power}}"},
            {"id":"e3","type":"text","x":3,"y":11.5,"fontSizeMm":3.5,"fontFamily":"默认","bold":false,"content":"SN {{serialNo}}"},
            {"id":"e4","type":"datamatrix","x":3,"y":16,"sizeMm":20,"moduleMm":0.75,"gs1":true,"gs1Mode":"lens","content":""},
            {"id":"e5","type":"text","x":25,"y":16.5,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"(01){{diNo}}"},
            {"id":"e6","type":"text","x":25,"y":20,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"(21){{serialNo}}"},
            {"id":"e7","type":"text","x":3,"y":37,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"总直径:{{totalDia}} 主体直径:{{bodyDia}}"}
            ]}""";

    public static final String INJECTOR_JSON = """
            {"version":1,"dpi":600,"widthMm":60,"heightMm":40,"elements":[
            {"id":"e1","type":"text","x":3,"y":2.5,"fontSizeMm":3.5,"fontFamily":"默认","bold":false,"content":"# {{type}}"},
            {"id":"e2","type":"text","x":3,"y":7,"fontSizeMm":3.5,"fontFamily":"默认","bold":false,"content":"LOT {{lotNo}}"},
            {"id":"e3","type":"datamatrix","x":3,"y":14,"sizeMm":20,"moduleMm":0.75,"gs1":true,"gs1Mode":"injector","content":""},
            {"id":"e4","type":"text","x":3,"y":36,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"开口:{{cutDia}} 注册号:{{registerNo}}"},
            {"id":"e5","type":"text","x":25,"y":14.5,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"(01){{diNo}}"},
            {"id":"e6","type":"text","x":25,"y":18,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"(10){{lotNo}}"},
            {"id":"e7","type":"text","x":25,"y":21.5,"fontSizeMm":3,"fontFamily":"默认","bold":false,"content":"{{productionDate}}~{{expiryDate}}"}
            ]}""";

    private LabelSeed() {
    }

    /** 幂等播种两个内置模板。 */
    public static void seedAll(TemplateRepository repo) {
        repo.seed(LENS_NAME, LENS_JSON, true);
        repo.seed(INJECTOR_NAME, INJECTOR_JSON, true);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=LabelSeedTest`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/label/LabelSeed.java src/test/java/com/hq/iol/label/LabelSeedTest.java
git commit -m "feat: A/B 线内置模板播种"
```

---

### Task 4: 位图编码器（TsplBitmapEncoder）

**Files:**
- Create: `src/main/java/com/hq/iol/label/TsplBitmapEncoder.java`
- Test: `src/test/java/com/hq/iol/label/TsplBitmapEncoderTest.java`

**Interfaces:**
- Produces: `TsplBitmapEncoder.toGraphCommand(BufferedImage img, int x, int y): String`（`GRAPH x,y,wBytes,hDots,<hex>\r\n`）；`toMonoBytes(BufferedImage): byte[]`（包级，供测试）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/label/TsplBitmapEncoderTest.java`：

```java
package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class TsplBitmapEncoderTest {

    /** 16×8 图像：第一行全黑，其余全白。 */
    private BufferedImage sample() {
        BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, y == 0 ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    @Test
    void 位图按行打包_MSB在前_行对齐到字节() {
        byte[] bytes = TsplBitmapEncoder.toMonoBytes(sample());
        // 16 宽 × 8 高 = 2 字节/行 × 8 行 = 16 字节；仅第一行全 1
        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xFF);
        for (int i = 2; i < 16; i++) {
            assertThat(bytes[i]).isEqualTo((byte) 0x00);
        }
    }

    @Test
    void GRAPH指令含字节宽_点高_大写hex() {
        String cmd = TsplBitmapEncoder.toGraphCommand(sample(), 10, 20);
        assertThat(cmd).startsWith("GRAPH 10,20,2,8,");
        assertThat(cmd).endsWith("\r\n");
        assertThat(cmd).contains("FFFF");
        assertThat(cmd).contains("0000000000000000000000000000");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=TsplBitmapEncoderTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/label/TsplBitmapEncoder.java`：

```java
package com.hq.iol.label;

import java.awt.image.BufferedImage;

/**
 * BufferedImage → TSPL GRAPH 指令。
 * 1bpp、行内按字节对齐、每字节高位（MSB）在前、灰度 <128 为黑点。
 */
public final class TsplBitmapEncoder {

    private TsplBitmapEncoder() {
    }

    /** 打包为 1bpp 字节流（包级可见供测试）。 */
    static byte[] toMonoBytes(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int rowBytes = (w + 7) / 8;
        byte[] out = new byte[rowBytes * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114;
                if (lum / 1000 < 128) {
                    out[y * rowBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return out;
    }

    /** GRAPH x,y,字节宽,点高,&lt;大写hex&gt; + CR LF。 */
    public static String toGraphCommand(BufferedImage img, int x, int y) {
        byte[] bytes = toMonoBytes(img);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
               .append(Character.forDigit(b & 0xF, 16));
        }
        return "GRAPH %d,%d,%d,%d,%s\r\n".formatted(
                x, y, (img.getWidth() + 7) / 8, img.getHeight(),
                hex.toString().toUpperCase());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=TsplBitmapEncoderTest`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/label/TsplBitmapEncoder.java src/test/java/com/hq/iol/label/TsplBitmapEncoderTest.java
git commit -m "feat: 位图转 TSPL GRAPH 编码器"
```

---

### Task 5: 渲染器打印路径（LabelRenderer.renderTspl）

**Files:**
- Create: `src/main/java/com/hq/iol/label/LabelRenderer.java`
- Test: `src/test/java/com/hq/iol/label/LabelRendererTsplTest.java`

**Interfaces:**
- Consumes: `VariableSupport`、`TsplBitmapEncoder`、`Gs1DataMatrix`
- Produces: `LabelRenderer.renderTspl(String designJson, Map<String,String> vars, int dpi): String`、`LabelRenderer.renderPreview(String designJson, Map<String,String> vars): BufferedImage`（本任务先实现打印路径与共享骨架，预览在 Task 6）；`LabelRenderer.dot(double mm, int dpi): int`（包级 static）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/label/LabelRendererTsplTest.java`：

```java
package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelRendererTsplTest {

    private static final Map<String, String> LENS_VARS = Map.of(
            "diNo", "06975897610400", "sterileDate", "2026-05-28",
            "serialNo", "2605283500", "type", "AT2000", "power", "+20.0D",
            "totalDia", "12.0", "bodyDia", "6.0");

    @Test
    void 渲染A线模板含标签头_GS1码流与PRINT收尾() {
        String tspl = LabelRenderer.renderTspl(LabelSeed.LENS_JSON, LENS_VARS, 600);
        assertThat(tspl).startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\n"
                + "DIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");
        assertThat(tspl).contains("DMATRIX 71,378,472,472,c126,x18,"
                + "\"~1" + "01" + "06975897610400" + "11" + "260528"
                + "17" + "310528" + "21" + "2605283500\"");
        assertThat(tspl).contains("GRAPH 0,0,");
        // 文本元素渲染进位图 hex，TSPL 字符串中不再出现文本内容，只验证位图指令存在
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void 坐标按600dpi换算() {
        assertThat(LabelRenderer.dot(3, 600)).isEqualTo(71);
        assertThat(LabelRenderer.dot(16, 600)).isEqualTo(378);
    }

    @Test
    void 变量无值保留原样进入指令() {
        // 用条码元素验证（文本元素进位图，TSPL 字符串中不可见）
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":10,\"y\":5,\"heightMm\":8,\"moduleMm\":0.3,\"displayText\":true,\"content\":\"{{sn}}\"}]}";
        String tspl = LabelRenderer.renderTspl(json, Map.of(), 600);
        assertThat(tspl).contains("\"{{sn}}\"");
    }

    @Test
    void 非GS1元素分流正确() {
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":10,\"y\":5,\"heightMm\":8,\"moduleMm\":0.3,\"displayText\":true,\"content\":\"{{sn}}\"},"
                + "{\"id\":\"q\",\"type\":\"qrcode\",\"x\":30,\"y\":5,\"sizeMm\":15,\"errorLevel\":\"M\",\"content\":\"http://x\"},"
                + "{\"id\":\"l\",\"type\":\"line\",\"x\":0,\"y\":30,\"lengthMm\":60,\"thicknessMm\":0.4,\"angle\":0},"
                + "{\"id\":\"r\",\"type\":\"rect\",\"x\":1,\"y\":1,\"widthMm\":20,\"heightMm\":10,\"thicknessMm\":0.5,\"fill\":false}]}";
        String tspl = LabelRenderer.renderTspl(json, Map.of("sn", "ABC123"), 600);
        assertThat(tspl).contains("BARCODE 236,118,\"128\",189,1,0,7,7,\"ABC123\"");
        assertThat(tspl).containsPattern("QRCODE 709,118,M,\\d+,A,0,M2,\"http://x\"");
        assertThat(tspl).contains("BAR 0,709,1417,9");
        assertThat(tspl).contains("BOX 24,24,496,260,12");
    }
}
```

（换算依据：dot(3,600)=71、dot(16,600)=378、dot(20,600)=472、module 0.75mm→17.7≈18；barcode x10→236、y5→118、h8→189、module 0.3→7；qrcode x30→709；line y30→709、length 60→1417、thickness 0.4→9；rect x1→24、x2=(1+20)→496、y2=(1+10)→260、thickness 0.5→12）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=LabelRendererTsplTest`
Expected: 编译失败，LabelRenderer 不存在

- [ ] **Step 3: 实现**

`src/main/java/com/hq/iol/label/LabelRenderer.java`：

```java
package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.device.printer.Gs1DataMatrix;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.Map;

/**
 * 唯一标签渲染器：design JSON + 变量 → ①混合管线 TSPL（打印）②BufferedImage（预览）。
 * 打印分流：text/image → 共享位图 GRAPH；barcode/qrcode/datamatrix → 原生指令；line/rect → BAR/BOX。
 */
public final class LabelRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LabelRenderer() {
    }

    /** mm → dot（600 DPI：1mm ≈ 23.62 dot）。 */
    static int dot(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    /** 打印路径：design JSON + 变量 → 完整 TSPL。 */
    public static String renderTspl(String designJson, Map<String, String> vars, int dpi) {
        JsonNode root = parse(designJson);
        double widthMm = root.path("widthMm").asDouble();
        double heightMm = root.path("heightMm").asDouble();

        StringBuilder tspl = new StringBuilder();
        tspl.append("SIZE %s mm,%s mm\r\n".formatted(fmt(widthMm), fmt(heightMm)));
        tspl.append("GAP 2 mm,0 mm\r\nDIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");

        BufferedImage bitmap = null;
        Graphics2D g2d = null;
        StringBuilder nativeCmds = new StringBuilder();

        for (JsonNode el : root.path("elements")) {
            String type = el.path("type").asText();
            switch (type) {
                case "text", "image" -> {
                    if (bitmap == null) {
                        bitmap = new BufferedImage(dot(widthMm, dpi), dot(heightMm, dpi),
                                BufferedImage.TYPE_INT_RGB);
                        g2d = bitmap.createGraphics();
                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                        g2d.setColor(java.awt.Color.BLACK);
                    }
                    drawBitmapElement(el, vars, dpi, g2d);
                }
                case "datamatrix" -> nativeCmds.append(renderDataMatrix(el, vars, dpi));
                case "barcode" -> nativeCmds.append(renderBarcode(el, vars, dpi));
                case "qrcode" -> nativeCmds.append(renderQrcode(el, vars, dpi));
                case "line" -> nativeCmds.append(renderLine(el, dpi));
                case "rect" -> nativeCmds.append(renderRect(el, dpi));
                default -> throw new IllegalArgumentException("未知元素类型: " + type);
            }
        }
        if (bitmap != null) {
            g2d.dispose();
            tspl.append(TsplBitmapEncoder.toGraphCommand(bitmap, 0, 0));
        }
        tspl.append(nativeCmds);
        tspl.append("PRINT 1,1\r\n");
        return tspl.toString();
    }

    /** 位图元素绘制（文本/图片），打印与预览共用。 */
    private static void drawBitmapElement(JsonNode el, Map<String, String> vars,
                                          int dpi, Graphics2D g) {
        String type = el.path("type").asText();
        if ("text".equals(type)) {
            String content = VariableSupport.replace(el.path("content").asText(""), vars);
            int px = Math.max(1, dot(el.path("fontSizeMm").asDouble(3), dpi));
            int style = el.path("bold").asBoolean(false) ? Font.BOLD : Font.PLAIN;
            g.setFont(new Font(el.path("fontFamily").asText("默认").equals("默认")
                    ? Font.SANS_SERIF : el.path("fontFamily").asText(), style, px));
            // AWT 文本基线在字高上方，y 按元素顶边 + 字高绘制
            g.drawString(content, dot(el.path("x").asDouble(), dpi),
                    dot(el.path("y").asDouble(), dpi) + px);
        } else {
            String source = VariableSupport.replace(el.path("source").asText(""), vars);
            try {
                BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(source));
                g.drawImage(img, dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                        dot(el.path("widthMm").asDouble(), dpi),
                        dot(el.path("heightMm").asDouble(), dpi), null);
            } catch (Exception e) {
                throw new IllegalArgumentException("图片加载失败: " + source, e);
            }
        }
    }

    private static String renderDataMatrix(JsonNode el, Map<String, String> vars, int dpi) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int size = dot(el.path("sizeMm").asDouble(), dpi);
        int module = Math.max(1, dot(el.path("moduleMm").asDouble(0.75), dpi));
        String stream;
        if (el.path("gs1").asBoolean(false)) {
            stream = buildGs1Stream(el, vars);
        } else {
            stream = VariableSupport.replace(el.path("content").asText(""), vars);
        }
        return "DMATRIX %d,%d,%d,%d,c126,x%d,\"%s\"\r\n".formatted(x, y, size, size, module, stream);
    }

    /** GS1 码流只能由 Gs1DataMatrix 拼（含 FNC1 与输入白名单校验）。 */
    static String buildGs1Stream(JsonNode el, Map<String, String> vars) {
        String mode = el.path("gs1Mode").asText("lens");
        if ("lens".equals(mode)) {
            LocalDate sterile = LocalDate.parse(required(vars, "sterileDate"));
            return Gs1DataMatrix.forLens(required(vars, "diNo"), sterile,
                    sterile.plusYears(5), required(vars, "serialNo"));
        }
        return Gs1DataMatrix.forInjector(required(vars, "diNo"),
                LocalDate.parse(required(vars, "productionDate")),
                LocalDate.parse(required(vars, "expiryDate")),
                required(vars, "lotNo"));
    }

    private static String renderBarcode(JsonNode el, Map<String, String> vars, int dpi) {
        int module = Math.max(1, dot(el.path("moduleMm").asDouble(0.3), dpi));
        return "BARCODE %d,%d,\"128\",%d,%d,0,%d,%d,\"%s\"\r\n".formatted(
                dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                dot(el.path("heightMm").asDouble(8), dpi),
                el.path("displayText").asBoolean(true) ? 1 : 0, module, module,
                VariableSupport.replace(el.path("content").asText(""), vars));
    }

    private static String renderQrcode(JsonNode el, Map<String, String> vars, int dpi) {
        int cell = Math.max(1, (int) Math.round(
                el.path("sizeMm").asDouble(15) * dpi / 25.4 / 29));
        return "QRCODE %d,%d,%s,%d,A,0,M2,\"%s\"\r\n".formatted(
                dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                el.path("errorLevel").asText("M"), cell,
                VariableSupport.replace(el.path("content").asText(""), vars));
    }

    private static String renderLine(JsonNode el, int dpi) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int len = dot(el.path("lengthMm").asDouble(), dpi);
        int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.4), dpi));
        int angle = el.path("angle").asInt(0);
        if (angle == 90 || angle == 270) {
            return "BAR %d,%d,%d,%d\r\n".formatted(x, y, thick, len);
        }
        return "BAR %d,%d,%d,%d\r\n".formatted(x, y, len, thick);
    }

    private static String renderRect(JsonNode el, int dpi) {
        int x1 = dot(el.path("x").asDouble(), dpi);
        int y1 = dot(el.path("y").asDouble(), dpi);
        int x2 = x1 + dot(el.path("widthMm").asDouble(), dpi);
        int y2 = y1 + dot(el.path("heightMm").asDouble(), dpi);
        int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.5), dpi));
        return "BOX %d,%d,%d,%d,%d\r\n".formatted(x1, y1, x2, y2, thick);
    }

    private static String required(Map<String, String> vars, String key) {
        String v = vars.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("GS1 元素缺少必需变量: " + key);
        }
        return v;
    }

    private static JsonNode parse(String designJson) {
        try {
            return MAPPER.readTree(designJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=LabelRendererTsplTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/label/LabelRenderer.java src/test/java/com/hq/iol/label/LabelRendererTsplTest.java
git commit -m "feat: 标签渲染器混合管线打印路径"
```

---

### Task 6: 渲染器预览路径（renderPreview + ZXing 解码回读）

**Files:**
- Modify: `src/main/java/com/hq/iol/label/LabelRenderer.java`
- Test: `src/test/java/com/hq/iol/label/LabelRendererPreviewTest.java`

**Interfaces:**
- Produces: `LabelRenderer.renderPreview(String designJson, Map<String,String> vars): BufferedImage`（4 px/mm，Task 8 画布使用）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/hq/iol/label/LabelRendererPreviewTest.java`：

```java
package com.hq.iol.label;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelRendererPreviewTest {

    @Test
    void 预览尺寸按4px每毫米() {
        BufferedImage img = LabelRenderer.renderPreview(LabelSeed.LENS_JSON, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28", "serialNo", "2605283500"));
        assertThat(img.getWidth()).isEqualTo(240);
        assertThat(img.getHeight()).isEqualTo(160);
    }

    @Test
    void 预览中的DataMatrix可被ZXing解码回GS1流() throws Exception {
        BufferedImage img = LabelRenderer.renderPreview(LabelSeed.LENS_JSON, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28", "serialNo", "2605283500"));
        LuminanceSource source = new BufferedImageLuminanceSource(img);
        String decoded = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertThat(decoded).isEqualTo("\u001d01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
    }

    @Test
    void 预览中的CODE128条码可解码() throws Exception {
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":5,\"y\":5,\"widthMm\":40,\"heightMm\":15,\"moduleMm\":0.3,\"displayText\":false,\"content\":\"ABC123\"}]}";
        BufferedImage img = LabelRenderer.renderPreview(json, Map.of());
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(img)))).getText();
        assertThat(decoded).isEqualTo("ABC123");
    }
}
```

注意：第一个解码断言中的 `"01"` 前缀的 `` 是 `\u001d`（GS1 FNC1 预览近似，见 Global Constraints）。测试里直接写 `"\u001d01..."`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=LabelRendererPreviewTest`
Expected: 编译失败（renderPreview 不存在）

- [ ] **Step 3: 实现（在 LabelRenderer 中追加预览路径）**

类常量追加：

```java
    /** 预览固定 4 px/mm。 */
    private static final int PREVIEW_PX_PER_MM = 4;
```

公开方法追加：

```java
    /** 预览路径：design JSON + 变量 → BufferedImage（4 px/mm）。 */
    public static BufferedImage renderPreview(String designJson, Map<String, String> vars) {
        JsonNode root = parse(designJson);
        double widthMm = root.path("widthMm").asDouble();
        double heightMm = root.path("heightMm").asDouble();
        int dpi = (int) Math.round(PREVIEW_PX_PER_MM * 25.4);
        BufferedImage img = new BufferedImage((int) Math.round(widthMm * PREVIEW_PX_PER_MM),
                (int) Math.round(heightMm * PREVIEW_PX_PER_MM), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(java.awt.Color.BLACK);
        for (JsonNode el : root.path("elements")) {
            switch (el.path("type").asText()) {
                case "text", "image" -> drawBitmapElement(el, vars, dpi, g);
                case "datamatrix" -> drawPreviewDataMatrix(el, vars, dpi, g);
                case "barcode" -> drawPreviewBarcode(el, vars, dpi, g);
                case "qrcode" -> drawPreviewQr(el, vars, dpi, g);
                case "line", "rect" -> drawPreviewShape(el, dpi, g);
                default -> throw new IllegalArgumentException("未知元素类型: " + el.path("type").asText());
            }
        }
        g.dispose();
        return img;
    }
```

私有方法追加：

```java
    private static void drawPreviewDataMatrix(JsonNode el, Map<String, String> vars,
                                              int dpi, Graphics2D g) {
        try {
            String stream = el.path("gs1").asBoolean(false)
                    ? buildGs1Stream(el, vars)
                    : VariableSupport.replace(el.path("content").asText(""), vars);
            // ZXing 无法真编码 FNC1，预览用 GS 字符近似（打印为原生 c126）
            String zxingContent = stream.replace("~1", "\u001d");
            int x = dot(el.path("x").asDouble(), dpi);
            int y = dot(el.path("y").asDouble(), dpi);
            int size = dot(el.path("sizeMm").asDouble(), dpi);
            com.google.zxing.datamatrix.DataMatrixWriter writer =
                    new com.google.zxing.datamatrix.DataMatrixWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    zxingContent, com.google.zxing.BarcodeFormat.DATA_MATRIX, size, size);
            for (int my = 0; my < matrix.getHeight(); my++) {
                for (int mx = 0; mx < matrix.getWidth(); mx++) {
                    if (matrix.get(mx, my)) {
                        g.fillRect(x + mx, y + my, 1, 1);
                    }
                }
            }
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalArgumentException("DataMatrix 预览编码失败", e);
        }
    }

    private static void drawPreviewBarcode(JsonNode el, Map<String, String> vars,
                                           int dpi, Graphics2D g) {
        try {
            String content = VariableSupport.replace(el.path("content").asText(""), vars);
            int x = dot(el.path("x").asDouble(), dpi);
            int y = dot(el.path("y").asDouble(), dpi);
            int w = el.has("widthMm") ? dot(el.path("widthMm").asDouble(), dpi)
                    : dot(el.path("moduleMm").asDouble(0.3), dpi) * Math.max(1, content.length() * 11);
            int h = dot(el.path("heightMm").asDouble(8), dpi);
            com.google.zxing.oned.Code128Writer writer = new com.google.zxing.oned.Code128Writer();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    content, com.google.zxing.BarcodeFormat.CODE_128, w, h);
            for (int mx = 0; mx < matrix.getWidth(); mx++) {
                for (int my = 0; my < matrix.getHeight(); my++) {
                    if (matrix.get(mx, my)) {
                        g.fillRect(x + mx, y + my, 1, 1);
                    }
                }
            }
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalArgumentException("条码预览编码失败", e);
        }
    }

    private static void drawPreviewQr(JsonNode el, Map<String, String> vars,
                                      int dpi, Graphics2D g) {
        try {
            String content = VariableSupport.replace(el.path("content").asText(""), vars);
            int x = dot(el.path("x").asDouble(), dpi);
            int y = dot(el.path("y").asDouble(), dpi);
            int size = dot(el.path("sizeMm").asDouble(15), dpi);
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    content, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            for (int my = 0; my < matrix.getHeight(); my++) {
                for (int mx = 0; mx < matrix.getWidth(); mx++) {
                    if (matrix.get(mx, my)) {
                        g.fillRect(x + mx, y + my, 1, 1);
                    }
                }
            }
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalArgumentException("二维码预览编码失败", e);
        }
    }

    private static void drawPreviewShape(JsonNode el, int dpi, Graphics2D g) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        if ("line".equals(el.path("type").asText())) {
            int len = dot(el.path("lengthMm").asDouble(), dpi);
            int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.4), dpi));
            int angle = el.path("angle").asInt(0);
            if (angle == 90 || angle == 270) {
                g.fillRect(x, y, thick, len);
            } else {
                g.fillRect(x, y, len, thick);
            }
        } else {
            int w = dot(el.path("widthMm").asDouble(), dpi);
            int h = dot(el.path("heightMm").asDouble(), dpi);
            if (el.path("fill").asBoolean(false)) {
                g.fillRect(x, y, w, h);
            } else {
                int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.5), dpi));
                g.fillRect(x, y, w, thick);
                g.fillRect(x, y + h - thick, w, thick);
                g.fillRect(x, y, thick, h);
                g.fillRect(x + w - thick, y, thick, h);
            }
        }
    }
```

注意：drawPreviewBarcode 用到 `el.has("widthMm")` 与测试里 barcode 元素带 `widthMm:40` 对应；`dot(...)` 以预览 dpi（≈102）换算。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=LabelRendererPreviewTest`
Expected: 3 tests PASS（解码回读是全计划最有价值的测试）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hq/iol/label/LabelRenderer.java src/test/java/com/hq/iol/label/LabelRendererPreviewTest.java
git commit -m "feat: 标签预览渲染与 ZXing 解码回读验证"
```

---

### Task 7: PrintService 接管（模板打印接口 + 播种 + 打印测试 Tab 改造）

**Files:**
- Modify: `src/main/java/com/hq/iol/service/PrintService.java`
- Modify: `src/main/java/com/hq/iol/ui/PrintPanel.java`
- Create: `src/main/java/com/hq/iol/label/TemplateSeeder.java`
- Test: `src/test/java/com/hq/iol/service/PrintServiceTemplateTest.java`
- Delete（随提交移除）: `src/test/java/com/hq/iol/service/PrintServiceTest.java` 中对 `buildLensTspl/buildInjectorTspl` 的旧断言（整文件替换为新测试）

**Interfaces:**
- Consumes: `TemplateRepository`、`LabelSeed.seedAll`、`LabelRenderer.renderTspl`
- Produces: `PrintService.print(String templateName, Map<String,String> vars)`、`PrintService.listTemplates(): List<LabelTemplate>`、`PrintService.templateVariables(String templateName): Set<String>`；Task 8 依赖

- [ ] **Step 1: 创建 TemplateSeeder（启动播种）**

`src/main/java/com/hq/iol/label/TemplateSeeder.java`：

```java
package com.hq.iol.label;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时幂等播种 A/B 线内置模板。 */
@Component
public class TemplateSeeder {

    private final TemplateRepository repo;

    public TemplateSeeder(TemplateRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void seed() {
        LabelSeed.seedAll(repo);
    }
}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/com/hq/iol/service/PrintServiceTemplateTest.java`：

```java
package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.label.LabelSeed;
import com.hq.iol.label.TemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrintServiceTemplateTest {

    @TempDir
    Path dir;

    private PrintService newService() {
        String url = "jdbc:sqlite:" + dir.resolve("t.db");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DbInitializer init = new DbInitializer(jdbc);
        ConfigService config = new ConfigService(jdbc, init);
        TemplateRepository repo = new TemplateRepository(jdbc, init);
        LabelSeed.seedAll(repo);
        return new PrintService(config, repo);
    }

    @Test
    void 模板变量列表合并GS1必需变量() {
        PrintService ps = newService();
        Set<String> vars = ps.templateVariables(LabelSeed.LENS_NAME);
        assertThat(vars).contains("serialNo", "diNo", "sterileDate", "type");
    }

    @Test
    void buildTspl从仓储取模板并渲染() {
        PrintService ps = newService();
        String tspl = ps.buildTspl(LabelSeed.LENS_NAME, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28",
                "serialNo", "2605283500", "type", "AT2000", "power", "+20.0D",
                "totalDia", "12.0", "bodyDia", "6.0"));
        assertThat(tspl).contains("DMATRIX");
        assertThat(tspl).contains("2605283500");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void 模板不存在抛异常() {
        PrintService ps = newService();
        assertThatThrownBy(() -> ps.buildTspl("不存在", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=PrintServiceTemplateTest`
Expected: 编译失败（PrintService 构造器与新方法不存在）

- [ ] **Step 4: 改造 PrintService**

整体替换 `src/main/java/com/hq/iol/service/PrintService.java`：

```java
package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.printer.PrinterStatus;
import com.hq.iol.device.printer.TscPrinterDriver;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.label.VariableSupport;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 打印业务编排：模板渲染 + 下发（打印前状态检查在驱动层）。 */
@Service
public class PrintService {

    private final ConfigService config;
    private final TemplateRepository templates;

    public PrintService(ConfigService config, TemplateRepository templates) {
        this.config = config;
        this.templates = templates;
    }

    public List<LabelTemplate> listTemplates() {
        return templates.findAll();
    }

    /** 模板变量 = design 中的 {{var}} ∪ GS1 模式必需变量。 */
    public Set<String> templateVariables(String templateName) {
        LabelTemplate t = templates.findByName(templateName);
        Set<String> vars = new LinkedHashSet<>(VariableSupport.extract(t.designJson()));
        if (t.designJson().contains("\"gs1Mode\":\"lens\"")) {
            vars.addAll(Set.of("diNo", "sterileDate", "serialNo"));
        }
        if (t.designJson().contains("\"gs1Mode\":\"injector\"")) {
            vars.addAll(Set.of("diNo", "productionDate", "expiryDate", "lotNo"));
        }
        return vars;
    }

    /** 查模板并渲染 TSPL（包级可见供测试）。 */
    String buildTspl(String templateName, Map<String, String> vars) {
        LabelTemplate t = templates.findByName(templateName);
        return LabelRenderer.renderTspl(t.designJson(), vars,
                config.getInt(ConfigKeys.PRINTER_DPI));
    }

    /** 查询打印机状态描述。 */
    public synchronized String queryPrinterStatus() throws IOException {
        return PrinterStatus.describe(newDriver().queryStatus());
    }

    /** 按模板打印（synchronized 串行化，防同 SN 双标签）。 */
    public synchronized void print(String templateName, Map<String, String> vars)
            throws IOException, TscPrinterDriver.PrinterNotReadyException {
        newDriver().print(buildTspl(templateName, vars));
    }

    private TscPrinterDriver newDriver() {
        return new TscPrinterDriver(config.get(ConfigKeys.PRINTER_HOST),
                config.getInt(ConfigKeys.PRINTER_PORT), 2000);
    }
}
```

同时删除 `LensLabelData.java`、`InjectorLabelData.java`、旧 `PrintServiceTest.java`、`PrintServiceTest` 引用的 buildLensTspl/buildInjectorTspl 及其测试（gs1/dot 等纯逻辑已由 label 包测试覆盖）。

- [ ] **Step 5: 改造 PrintPanel（模板下拉 + 动态变量表单）**

整体替换 `src/main/java/com/hq/iol/ui/PrintPanel.java`：

```java
package com.hq.iol.ui;

import com.hq.iol.label.LabelTemplate;
import com.hq.iol.service.PrintService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 打印测试面板：选模板 → 按变量生成表单 → 打印。 */
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

        ComboBox<LabelTemplate> templateBox = new ComboBox<>();
        templateBox.setCellFactory(lv -> cell());
        templateBox.setButtonCell(cell());
        Map<String, TextField> varFields = new LinkedHashMap<>();
        GridPane varGrid = new GridPane();
        varGrid.setHgap(8);
        varGrid.setVgap(8);

        Runnable rebuildVars = () -> {
            varFields.clear();
            varGrid.getChildren().clear();
            LabelTemplate t = templateBox.getValue();
            if (t == null) {
                return;
            }
            Set<String> vars = printService.templateVariables(t.name());
            int row = 0;
            for (String v : vars) {
                TextField tf = new TextField();
                varFields.put(v, tf);
                varGrid.addRow(row++, new Label(v), tf);
            }
        };
        templateBox.setOnAction(e -> rebuildVars.run());

        Button reloadBtn = new Button("刷新模板");
        reloadBtn.setOnAction(e -> runAsync(() -> {
            var list = printService.listTemplates();
            javafx.application.Platform.runLater(() -> {
                templateBox.getItems().setAll(list);
                if (!list.isEmpty()) {
                    templateBox.getSelectionModel().selectFirst();
                    rebuildVars.run();
                }
            });
        }));

        Button statusBtn = new Button("查询打印机状态");
        statusBtn.setOnAction(e -> runAsync(() ->
                uiLog.info("打印机状态: " + printService.queryPrinterStatus())));

        Button printBtn = new Button("打印");
        printBtn.setOnAction(e -> {
            LabelTemplate t = templateBox.getValue();
            if (t == null) {
                uiLog.error("请先选择模板");
                return;
            }
            Map<String, String> vars = new LinkedHashMap<>();
            varFields.forEach((k, tf) -> vars.put(k, tf.getText()));
            runAsync(() -> {
                printService.print(t.name(), vars);
                uiLog.info("已下发打印: " + t.name());
            });
        });

        g.addRow(0, new Label("模板"), templateBox, reloadBtn, statusBtn, printBtn);
        g.add(varGrid, 0, 1, 5, 1);
        runAsync(() -> {
            var list = printService.listTemplates();
            javafx.application.Platform.runLater(() -> {
                templateBox.getItems().setAll(list);
                if (!list.isEmpty()) {
                    templateBox.getSelectionModel().selectFirst();
                    rebuildVars.run();
                }
            });
        });
        return g;
    }

    private static ListCell<LabelTemplate> cell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LabelTemplate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage() != null ? ex.getMessage() : ex.toString());
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

- [ ] **Step 6: 全量回归**

Run: `mvn -q test`
Expected: 全部 PASS（旧 PrintServiceTest 已删，新测试通过）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hq/iol/service/ src/main/java/com/hq/iol/ui/PrintPanel.java src/main/java/com/hq/iol/label/TemplateSeeder.java src/test/java/com/hq/iol/service/
git commit -m "feat: PrintService 模板化接管，旧硬编码标签退役"
```

---

### Task 8: 设计器 UI（DesignerPanel + 模板管理）

**Files:**
- Create: `src/main/java/com/hq/iol/ui/DesignerPanel.java`
- Modify: `src/main/java/com/hq/iol/ui/MainView.java`（新增"标签设计"Tab）

**Interfaces:**
- Consumes: `TemplateRepository`、`PrintService`、`LabelRenderer.renderPreview`、`VariableSupport`、`UiLogService`

- [ ] **Step 1: 实现 DesignerPanel**

`src/main/java/com/hq/iol/ui/DesignerPanel.java`（完整实现，纯代码 JavaFX）：

```java
package com.hq.iol.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.service.PrintService;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 标签设计器：左模板列表 / 中画布（预览 + 拖拽）/ 右属性表单。
 * 拖拽只改 x/y，松手后重新渲染预览；保存写 design JSON 回仓储。
 */
@Component
public class DesignerPanel {

    private static final double PX_PER_MM = 4.0;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository repo;
    private final PrintService printService;
    private final UiLogService uiLog;

    private final ListView<String> templateList = new ListView<>();
    private final Canvas canvas = new Canvas();
    private final VBox propsBox = new VBox(6);
    private final Label varsLabel = new Label();

    private ObjectNode design;
    private String currentName;
    private JsonNode selected;

    public DesignerPanel(TemplateRepository repo, PrintService printService, UiLogService uiLog) {
        this.repo = repo;
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Button newBtn = new Button("新建");
        newBtn.setOnAction(e -> newTemplate());
        Button delBtn = new Button("删除");
        delBtn.setOnAction(e -> deleteTemplate());
        Button saveBtn = new Button("保存");
        saveBtn.setOnAction(e -> save());
        Button printBtn = new Button("测试打印");
        printBtn.setOnAction(e -> testPrint());
        HBox actions = new HBox(6, newBtn, delBtn, saveBtn, printBtn);

        templateList.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, name) -> loadTemplate(name));
        reloadList();

        canvas.setOnMousePressed(e -> selectAt(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> dragTo(e.getX(), e.getY()));
        canvas.setOnMouseReleased(e -> renderPreview());

        VBox left = new VBox(6, templateList, actions);
        VBox right = new VBox(6, new Label("属性"), propsBox, new Label("变量"), varsLabel);

        BorderPane root = new BorderPane(canvas);
        root.setLeft(left);
        root.setRight(right);
        return root;
    }

    private void reloadList() {
        templateList.getItems().setAll(
                repo.findAll().stream().map(t -> t.name()).toList());
    }

    private void loadTemplate(String name) {
        if (name == null) {
            return;
        }
        try {
            currentName = name;
            design = (ObjectNode) MAPPER.readTree(repo.findByName(name).designJson());
            canvas.setWidth(design.path("widthMm").asDouble() * PX_PER_MM);
            canvas.setHeight(design.path("heightMm").asDouble() * PX_PER_MM);
            selected = null;
            renderPreview();
            rebuildProps();
            varsLabel.setText(String.join(", ", com.hq.iol.label.VariableSupport
                    .extract(design.toString())));
        } catch (Exception e) {
            uiLog.error("模板加载失败: " + e.getMessage());
        }
    }

    private void renderPreview() {
        if (design == null) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        try {
            Image img = SwingFXUtils.toFXImage(
                    LabelRenderer.renderPreview(design.toString(), Map.of()), null);
            g.drawImage(img, 0, 0);
        } catch (Exception e) {
            uiLog.error("预览渲染失败: " + e.getMessage());
        }
        if (selected != null) {
            g.setStroke(javafx.scene.paint.Color.RED);
            g.strokeRect(selected.path("x").asDouble() * PX_PER_MM,
                    selected.path("y").asDouble() * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM);
        }
    }

    private void selectAt(double px, double py) {
        if (design == null) {
            return;
        }
        ArrayNode elements = (ArrayNode) design.path("elements");
        selected = null;
        for (JsonNode el : elements) {
            double ex = el.path("x").asDouble() * PX_PER_MM;
            double ey = el.path("y").asDouble() * PX_PER_MM;
            if (px >= ex && px <= ex + 40 && py >= ey && py <= ey + 40) {
                selected = el;
            }
        }
        rebuildProps();
        renderPreview();
    }

    private void dragTo(double px, double py) {
        if (selected == null) {
            return;
        }
        ((ObjectNode) selected).put("x", Math.round(px / PX_PER_MM * 2) / 2.0);
        ((ObjectNode) selected).put("y", Math.round(py / PX_PER_MM * 2) / 2.0);
        renderPreview();
    }

    private void rebuildProps() {
        propsBox.getChildren().clear();
        if (selected == null) {
            propsBox.getChildren().add(new Label("未选中元素"));
            return;
        }
        propsBox.getChildren().add(new Label("类型: " + selected.path("type").asText()));
        addNumberField("x", selected.path("x").asDouble());
        addNumberField("y", selected.path("y").asDouble());
        selected.fieldNames().forEachRemaining(f -> {
            if (!"id".equals(f) && !"type".equals(f) && !"x".equals(f) && !"y".equals(f)) {
                JsonNode v = selected.path(f);
                TextField tf = new TextField(v.isNumber() ? String.valueOf(v.asDouble())
                        : v.asText());
                tf.setOnAction(e -> applyProp(f, tf.getText()));
                propsBox.getChildren().addAll(new Label(f), tf);
            }
        });
    }

    private void addNumberField(String field, double value) {
        TextField tf = new TextField(String.valueOf(value));
        tf.setOnAction(e -> applyProp(field, tf.getText()));
        propsBox.getChildren().addAll(new Label(field), tf);
    }

    private void applyProp(String field, String raw) {
        if (selected == null) {
            return;
        }
        try {
            double d = Double.parseDouble(raw);
            ((ObjectNode) selected).put(field, d);
        } catch (NumberFormatException e) {
            ((ObjectNode) selected).put(field, raw);
        }
        renderPreview();
    }

    private void save() {
        if (design == null || currentName == null) {
            uiLog.error("无打开的模板");
            return;
        }
        runAsync(() -> {
            repo.update(currentName, design.toString());
            uiLog.info("模板已保存: " + currentName);
        });
    }

    private void newTemplate() {
        TextInputDialog dialog = new TextInputDialog("新模板");
        dialog.setHeaderText("模板名称");
        dialog.showAndWait().ifPresent(name -> runAsync(() -> {
            repo.insert(name, "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,"
                    + "\"elements\":[]}", false);
            javafx.application.Platform.runLater(this::reloadList);
            uiLog.info("模板已创建: " + name);
        }));
    }

    private void deleteTemplate() {
        String name = templateList.getSelectionModel().getSelectedItem();
        if (name == null) {
            return;
        }
        runAsync(() -> {
            repo.delete(name);
            javafx.application.Platform.runLater(this::reloadList);
            uiLog.info("模板已删除: " + name);
        });
    }

    private void testPrint() {
        if (currentName == null) {
            uiLog.error("请先打开模板");
            return;
        }
        runAsync(() -> {
            printService.print(currentName, Map.of());
            uiLog.info("测试打印已下发: " + currentName + "（变量无值时保留原样）");
        });
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage() != null ? ex.getMessage() : ex.toString());
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

（注意：javafx.swing 模块需加入 pom：`org.openjfx:javafx-swing`（win classifier），否则 SwingFXUtils 不可用。）

- [ ] **Step 2: pom 加 javafx-swing**

```xml
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-swing</artifactId>
      <version>${javafx.version}</version>
      <classifier>${javafx.platform}</classifier>
    </dependency>
```

- [ ] **Step 3: MainView 加 Tab**

`MainView.build()` 的 TabPane 追加：

```java
                new Tab("标签设计", designerPanel.build()),
```

并在 MainView 构造器注入 `DesignerPanel designerPanel`。

- [ ] **Step 4: 编译 + 全量回归**

Run: `mvn -q test`
Expected: 全部 PASS

- [ ] **Step 5: 人工验收清单**

1. `mvn javafx:run` → "标签设计"Tab 打开内置 A 线模板，预览显示各元素
2. 拖拽 SN 文本换位置 → 保存 → 重启应用确认持久化
3. 测试打印（真机）→ 出纸且扫码枪回读 GS1 正确
4. 新建模板 → 加元素 → 删除（内置模板删除按钮应报"内置模板不可删除"）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hq/iol/ui/ pom.xml
git commit -m "feat: 标签设计器 UI（画布拖拽 + 属性表单 + 测试打印）"
```

---

## 验收汇总

- `mvn test` 全绿（新增约 18 个用例）
- ZXing 解码回读测试自动验证 GS1 码正确性
- 人工验收：Task 8 Step 5 全部通过

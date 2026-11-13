# 标签设计器 · 系统设计文档

- 日期：2026-11-13
- 状态：待评审
- 参照实现：`D:\Code\java\clone\label-data-service`（架构思路与踩坑清单）
- UI 风格参照：`D:\Code\java\hqjx\iot-hq-sd-luxshareI-bg`（纯代码 JavaFX 视图、`@Component + build()`、侧栏导航）
- 所属项目：BC-zx-packaging（`D:\Code\java\BC\BC-zx-packaging`）

## 1. 需求与决策

在已交付的"打印/称重/配置"最小验证 UI 基础上，增加**可视化标签设计器**，接管现有硬编码的 A/B 线标签。

| 决策点 | 结论 |
|---|---|
| 元素类型（六类） | 文本、GS1 DataMatrix、Code128 条码、二维码、直线/矩形、图片 Logo |
| 渲染管线 | **混合管线**：文本/图片 → GRAPH 位图；条码/二维码/DataMatrix → 打印机原生指令；线框 → BAR/BOX |
| 编辑交互 | 画布拖拽移动 + 右侧属性表单改数字（不做缩放句柄/旋转/撤销重做） |
| 老模板处置 | 设计器接管：`buildLensTspl/buildInjectorTspl` 退役，迁移为两个内置模板 |
| 模板存储 | 本地 SQLite `label_template` 表，design JSON 为权威数据 |
| 变量 | `{{var}}` 占位，无值保留原样（与未来 MES 字段映射衔接） |
| 坐标单位 | 全部 mm，打印时按 `printer.dpi` 换算 dot（沿用 600 DPI 纪律） |
| 旋转 | 一期不支持（属性不建模） |
| 条码类型 | 只做 CODE128（保证预览与打印指令一致） |

## 2. 参照项目的经验与规避项

label-data-service 同为 TSC+TSPL 标签平台，其教训逐条规避：

| 它的坑 | 我们的规避 |
|---|---|
| 两条渲染管线不一致（条码设了类型打印永远是 CODE128） | **唯一 `LabelRenderer`**，预览与打印共用；条码只做 CODE128 |
| 整版位图导致条码二值化、扫码质量差、报文 ~82KB/张 | 混合管线，条码走原生指令 |
| image/logo 元素打印静默丢失 | image 元素在渲染器有完整打印分支（GRAPH 画布） |
| ZPL/TSPL 命名混乱 | 命名一律 TSPL |
| GAP/SIZE 写死 | 由模板 mm 尺寸驱动 |

## 3. 数据模型

SQLite `label_template` 表：

| 列 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | |
| name | TEXT UNIQUE | 模板名（"A线晶体标签"等） |
| design_json | TEXT | 权威设计稿（下述 JSON） |
| builtin | INTEGER | 1=内置模板（A/B 线），不允许删除 |
| updated_at | TEXT | ISO 时间 |

design JSON 根结构：`{version, dpi, widthMm, heightMm, elements: [...]}`。

元素通用属性：`id`、`type`、`x`、`y`（mm）。类型特有：

| type | 特有属性 |
|---|---|
| `text` | fontSizeMm、fontFamily、bold、content |
| `datamatrix` | sizeMm、moduleMm、gs1(bool)、content（GS1 时绑原始变量如 serialNo/sterileDate，码流由 Gs1DataMatrix 拼） |
| `barcode` | heightMm、moduleMm、displayText、content |
| `qrcode` | sizeMm、errorLevel(L/M/Q/H)、content |
| `line` | lengthMm、thicknessMm、angle(0/90/180/270) |
| `rect` | widthMm、heightMm、thicknessMm、fill(bool) |
| `image` | widthMm、heightMm、source（路径或 `{{var}}`） |

Java 侧用 Jackson `JsonNode` 读写（duck-typed，新增元素类型不改表结构）。

## 4. 模块划分（包 `com.hq.iol.label`）

| 类 | 职责 |
|---|---|
| `TemplateRepository` | 模板 CRUD（JdbcTemplate）；首次启动播种 A/B 线内置模板 |
| `VariableSupport` | `{{var}}` 提取（`\{\{(\w+)\}\}`）与替换（无值保留原样） |
| `LabelRenderer` | 唯一渲染器：design JSON + 变量数据 → ①预览 BufferedImage ②混合管线 TSPL |
| `TsplBitmapEncoder` | BufferedImage → 1bpp → `GRAPH x,y,w,h,<hex>` |
| `DesignerPanel` | 设计器（画布拖拽 + 属性表单 + 变量面板 + 测试打印） |
| `TemplateListPanel` | 模板列表（新建/复制/删除/设为默认） |
| `LabelSeed` | A/B 线内置模板的 design JSON（复刻现有硬编码布局） |

新增依赖：`com.google.zxing:core`（条码/DataMatrix 预览与位图绘制）。

## 5. 渲染管线

### 打印路径（design JSON + 变量 Map → TSPL 字符串）

1. `VariableSupport` 替换元素 content 中的 `{{var}}`，无值保留原样
2. 元素分流：
   - `datamatrix` → `DMATRIX x,y,s,s,c126,xN,"流"`；gs1=true 时码流由 `Gs1DataMatrix.forLens/forInjector` 在渲染时按绑定变量拼（UDI 合规逻辑复用，一行不改）
   - `barcode` → `BARCODE x,y,"128",h,readable,0,n,w,"内容"`
   - `qrcode` → `QRCODE x,y,E,cell,A,0,M2,"内容"`
   - `text` / `image` → 画入共享 BufferedImage（Java 字体，中文不依赖打印机字体）→ `TsplBitmapEncoder` → 一条 `GRAPH 0,0,W,H,<hex>`
   - `line` → `BAR`（水平/垂直）；`rect` → `BOX`（fill 时实心）
3. 拼装：`SIZE/GAP/DIRECTION 1/CODEPAGE UTF-8/CLS` + GRAPH（若有位图元素）+ 原生指令 + `PRINT 1,1`

### 预览路径

同一 `LabelRenderer` 把全部元素画到 BufferedImage（文本走 Java 字体、条码/DataMatrix 走 ZXing、线框走 Graphics2D、图片缩放贴入），按 mm 等比缩放到画布像素——预览即打印语义。

### 变量面板与测试打印

设计器实时列出模板全部 `{{var}}`；测试打印弹出变量录入表单（一期手工填），走 `TscPrinterDriver.print()`（含打印前状态检查红线）。

## 6. UI 布局

```
主界面新增「标签设计」Tab：
┌ 模板列表（左 200px）┬ 画布（中，预览图实时刷新）┬ 属性面板（右 260px）┐
│ 列表+新建/删除/复制   │ 点击选中→拖拽移动          │ 类型特有属性表单        │
│ 设为默认             │ px→mm 换算，松手重渲染      │ 尺寸/DPI + 变量列表      │
└────────────────────┴──────────────────────────┴──────────────────────┘
底部：保存 / 另存为 / 测试打印；操作日志复用 UiLogService
```

- 画布为 JavaFX Pane：元素以选中框 + 预览图叠加显示；`onMousePressed/Dragged` 拖拽改写 x/y（px→mm），松手后重新渲染预览
- 属性面板按元素类型动态切换表单
- 所有 IO（保存/打印）走后台 daemon 线程，复用 runAsync 模式与 UiLogService

## 7. 老代码迁移

- `PrintService` 新增 `print(String templateName, Map<String,String> vars)`：查模板 → `LabelRenderer` 渲染 TSPL → `TscPrinterDriver.print()`
- `buildLensTspl/buildInjectorTspl` 标记 `@Deprecated`，打印测试 Tab 改为调新接口（选模板 + 变量表单）
- 首次启动由 `TemplateRepository` 播种"A线晶体标签""B线推注器标签"两个内置模板（`LabelSeed`），布局复刻现有 mm 坐标；内置模板不可删除
- 原 `PrintServiceTest` 中硬编码布局断言改为针对 `LabelSeed` JSON 的快照测试

## 8. 验证策略

- 单测：变量提取/替换边界（无值保留、嵌套括号）、design JSON 序列化往返、混合管线 TSPL 快照（指令序列 + GRAPH hex 字节数校验）、**ZXing 解码回读测试**（渲染的 DataMatrix 位图用 ZXing 解码应得原 GS1 流——自动化扫码枪）
- 集成：播种模板渲染出的 A 线 TSPL 与现硬编码输出对比（元素级等价）
- 人工验收：设计器微调 A 线标签 → 测试打印 → 真扫码枪回读 + 与旧版打印效果对比

## 9. 里程碑

| 阶段 | 内容 | 出口标准 |
|---|---|---|
| D1 | TemplateRepository + VariableSupport + LabelSeed 播种 | CRUD 与变量单测绿，内置模板入库 |
| D2 | LabelRenderer 混合管线 + TsplBitmapEncoder + ZXing | 快照与解码回读单测绿 |
| D3 | PrintService 接管 + 打印测试 Tab 改造 | 老路径退役，全量回归绿 |
| D4 | DesignerPanel + TemplateListPanel | 拖拽编辑/保存/测试打印人工验收 |

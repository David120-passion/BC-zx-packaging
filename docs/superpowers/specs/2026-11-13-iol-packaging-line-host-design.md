# 人工晶体（IOL）自动包装线上位机 · 系统设计文档

- 初版：2026-11-03（打印上位机通用方案，已作废）
- 修订：2026-11-13（依据《对接规格》《MES通讯文档》《TSPL/TSPL2 Programming Manual》及客户/厂商沟通记录收窄重做）
- 状态：待评审

> 修订说明：经客户资料确认，本项目为人工晶体/推注器自动包装线上位机。MES 对接方式由客户定死为 **PostgreSQL 16 数据库直连**；打印机仅 **TSC（TSPL/TSPL2）**，原方案中的 Zebra 511、PEX-1100 打印引擎、打印机内嵌模板、自建中心服务端等设计整体移除。

## 1. 项目范围

两条产品线：

- **A 线 · 晶体包装（小盒子）**：扫描初级包装二维码取序列号 → 查库取产品信息 → 激光打标外盒 + TSC 打印标签 → 装盒称重（三次）与三道视觉检测 → 数据回写数据库。
- **B 线 · 推注器包装**：人工输入工单号与生产日期 → 查库取型号信息 → 打印标签 → 记录包装数量与不合格数量。

### 1.1 对接点清单

| 对接点 | 协议/方式 | Java 方案 | 规格状态 |
|---|---|---|---|
| MES 生产数据库 | PostgreSQL 16 直连 SQL（客户唯一硬约束） | pgjdbc 42.7.x + spring-jdbc + HikariCP | 规格完整（7 张表，4 读 3 写） |
| EK-i 电子秤 | RS-232C，ASCII 定长帧行协议 | jSerialComm 2.11.x | 规格完整（手册 P22–24） |
| TSC 标签打印机 | TSPL/TSPL2 over TCP 9100（RS-232 备选） | java.net.Socket，零依赖 | 规格完整（编程手册） |
| PLC | 建议 Modbus TCP（西门子则 S7） | digitalpetri/modbus 或 j2mod | 我方自选 |
| 激光打标软件 | 待厂商提供（多为 TCP Socket / 触发文件） | — | **规格缺失** |
| 视觉检测系统 | 待厂商提供（多为 TCP 字符串） | — | **规格缺失** |

### 1.2 非目标（一期）

- Zebra / 打印引擎等其他品牌型号接入（通信层保留 Driver 抽象，不实现）
- BarTender 等标签软件集成
- 激光打标、视觉的实接（拿到协议手册前只做接口隔离与模拟器）
- OEE 报表

## 2. 总体架构

**单体上位机 + 客户数据库为中心**。客户的 PostgreSQL 即数据中心，不自建服务端；上位机直连数据库与现场设备。断网/断库时本地落盘缓冲（SQLite 或 WAL 文件），恢复后补传——医疗器械产线追溯数据不允许丢失，这是硬性要求。

```
┌──────────────────────── 上位机（JDK 21 + Spring Boot 3 + JavaFX）────────────────────────┐
│  ui（JavaFX：A线监控界面 / B线录入界面 / 报警与补打）                                       │
│  line-core      工位状态机：WorkpieceContext 贯穿全流程，末端一次性事务落库                  │
│  integration    设备适配层：PgDataSource / EkiScaleDriver / TsplPrinterDriver /            │
│                 PlcAdapter(Modbus TCP) / LaserAdapter(待规格) / VisionAdapter(待规格)       │
│  buffer         本地落盘缓冲（SQLite/H2 或 WAL），断库时暂存 INSERT，恢复补传               │
└──────┬───────────────┬───────────────┬───────────────┬──────────────┬─────────────────────┘
       │ JDBC          │ TCP 9100      │ RS-232        │ Modbus TCP   │ TCP（待定）
       ▼               ▼               ▼               ▼              ▼
  PostgreSQL 16   TSC 打印机      EK-i 电子秤        PLC         激光打标 / 视觉
  （客户提供 7 表）
```

**架构要点**：

- 每设备独立连接，用有界队列解耦，单台设备卡死不拖垮整线；JDK 21 虚拟线程承载阻塞式 I/O。
- 状态机驱动工位流转：一个工件一个 `WorkpieceContext`，DefectCode 优先级判定集中一处。
- 设备适配层接口隔离，无规格设备（激光/视觉）先实现模拟器，不阻塞主线开发。
- UI 技术选型为 JavaFX（团队栈已定），界面与业务通过事件/服务层解耦，不阻塞产线线程。

## 3. 数据库对接（PostgreSQL 16）

### 3.1 读取表（4 张）

- `ProductInfo_LaserMark`（A 线入口）：按 ProductSN 查询，同 SN 多条时 `ORDER BY "FinishTime" DESC LIMIT 1` 取最新。字段：Type / ProductSN / Power / SterileDate / Weight / QRcodecheck / IOLinPosition / FinishTime。
- `TypeInfo`：按 型号+度数 查 TotalDia / BodyDia / OptDia / DINo（UDI-DI）。
- `OrderInfoInjector`（B 线）：按工单号查 型号、计划数量。
- `TypeInfoInjector`：按型号查 CutDia / DINo / RegisterNo。

### 3.2 写入表（3 张）

- `ProductInfo_PackWeight`：ProductSN / Type / Power / WeightPrimary（查库）/ WeightBox / WeightSecondary / WeightTotal。**无时间戳字段**——返工件无法定序，已向客户提出补 `RecordTime` 或以 ID 定序（待确认）。
- `ProductionInfo_PackVision`：3 个 Bool + DefectCode + RecordTime。
- `ProductInfo_InjectorPacking`（B 线）：批次号 / 型号 / 生产日期 / 包装日期 / 完成数量 / 不合格数量。

### 3.3 关键业务规则

- 序列号结构 `YYMMDDNNXX`（10 位）：前 8 位批次号，后 2 位流水号。
- 生产日期 = 灭菌日期；**有效期 = 灭菌日期 + 5 年**（按 `plusYears(5)` 存储，显示层再决定是否减一天，不从标签图片反推）。
- 产品推入前视觉判不合格 → **不记录重量数据**，只写视觉表。
- **PascalCase 字段名所有 SQL 一律加双引号**；开工第一天用 `psql \d` 确认实际大小写。
- A 线末端**同一事务**写称重表 + 视觉表。

### 3.4 DefectCode 编码表

| 码 | 含义 | 判定方 |
|---|---|---|
| 0 | 无缺陷 | — |
| 1–4 | 次级包装灭菌标识颜色/密封边异物/密封边通道/包装内异物 | 视觉 |
| 5 | 推入前视觉缺物料 | 视觉 |
| 6 | 推入前序列号不对应 | 视觉 / 上位机 |
| 7–8 | 最终视觉 QC 标签缺失 / 外观缺陷 | 视觉 |
| 9 | 装盒后重量超差 | **上位机**（阈值待客户提供，先做成可配置项） |

## 4. TSC 打印机对接（TSPL/TSPL2 over TCP 9100）

厂商工程师已确认：RS-232/网口只是载体，应用层统一 TSPL 指令，不用标签软件则 TSPL 是唯一途径。Java 侧 `java.net.Socket` 写字符串即可，无需任何 SDK/驱动/JNI。

### 4.1 标签生成（代码生成，非内嵌模板）

标签仅 2 种（A 线晶体、B 线推注器），用 Builder 拼完整 TSPL 指令流：

- 标签头：`SIZE` / `GAP` / `DIRECTION 1` / `CODEPAGE UTF-8`（含中文必须显式设置）/ `CLS`
- 中文文本必须用 TTF 字体（如 `ARIALUNI.TTF`），点阵字体 "1"–"5" 只覆盖 ASCII
- **机型确认为 TSC MH641（600 DPI，1mm ≈ 23.62 dot，24 点/毫米）**：600 DPI 适合高密度 GS1 DataMatrix 与小字；DPI 做成配置（`printer.dpi`），业务代码一律以 mm 书写坐标，由打印服务统一 mm→dot 换算（200 DPI 1mm=8dot，300 DPI 1mm=12dot，600 DPI 1mm≈24dot）

### 4.2 GS1 DataMatrix（UDI 合规，本对接点唯一有技术含量的部分）

- TSPL `DMATRIX` 指令加 `c126` 启用 GS1 模式，`~1` 为 FNC1 转义；内容中字面 `~` 须转义为 `~~`
- **FNC1 规则**：码流开头必须有 1 个 FNC1；可变长 AI 之后需补 FNC1 分隔，定长 AI 之后不需要
- A 线 AI 顺序 `(01)(11)(17)(21)`，B 线 `(01)(11)(17)(10)`：(01) 定长 14、(11)/(17) 定长 6，可变长的 (21)/(10) 均在末位 → **只需开头一个 FNC1，中间无分隔符**；若日后调整 AI 顺序把可变长字段挪到中间，其后必须补 `~1`
- 医疗器械 UDI 需法规核查，**必须用扫码枪实测验证 AI 解析**，不能只看打印效果

### 4.3 状态回读与防断号

- 打印前发 `<ESC>!?` 查询状态（1 字节应答），非 0x00 中止流程并报警，**宁可停线不可空打**（防 SN 断号）
- 状态字节：00 就绪 / 01 打印头开启 / 02 卡纸 / 04 缺纸 / 08 缺碳带 / 0C 碳带用尽 / 10 暂停 / 20 打印中 / 80 其他错误（组合按位或，如 09=缺碳带且打印头开启）
- TCP 写失败重连后**先查状态再决定是否重发**，防同 SN 重复打印
- 标签内容含"按型号固定的黄色字段"：随查库结果整体渲染，不依赖打印机内固定模板

## 5. EK-i 电子秤对接（RS-232C）

- 串口参数：9600 / 8N1（功能项 bPS=2、btPr=2，需与秤一致），CR LF 终止，无流控
- 帧格式：16 字符定长 + CR LF：`状态头(2) , 数据(9,含符号位小数点) 单位(4)`；功能项 Pnt 可能把小数点配成逗号，解析统一 `replace(",",".")`
- 模式：设 Prt 1（指令模式），程序侧一律主动 `Q` 查询，不用 10 帧/秒连续模式
- 状态头：`ST`/`QT` 可入库；`US` 不稳定须轮询重试至 ST（超时 3s）；`OL` 超载报警
- 单位只接受 `g`，出现其他单位视为误操作报警，不静默换算
- Windows 下 USB-RS232 换口 COM 号漂移：用 `SerialPort.getCommPorts()` 描述符匹配或配置项
- 台数多时可改 RS-232 转以太网串口服务器，流换成 Socket，解析逻辑完全复用

## 6. PLC / 激光打标 / 视觉

- **PLC**：建议 Modbus TCP（digitalpetri/modbus，调试工具成熟）；若西门子 S7 系列改用 S7 协议；不建议 OPC UA（过度设计且需 PLC 侧授权）
- **激光打标 / 视觉**：协议规格缺失，向厂商索要通讯手册前，适配层先出模拟器实现；不阻塞可行性，但阻塞详细设计

## 7. 端到端流程（A 线）

1. 扫码枪读初级包装 DataMatrix → 解析 ProductSN
2. 查 `ProductInfo_LaserMark`（取最新）→ 型号、度数、灭菌日期、初级包装重量
3. 查 `TypeInfo` → 直径参数、UDI-DI
4. **并行下发**激光打标（外盒）与 TSC 打印（先查 `<ESC>!?` 状态，缺纸中止）
5. 依次采集：次级包装视觉 → 次级包装称重 → 外盒称重 → 推入前视觉（不合格则不记重量）→ 装盒总重（超差置 DefectCode 9）→ 装盒完成视觉
6. 同一事务回写 `ProductInfo_PackWeight` + `ProductionInfo_PackVision`

B 线：人工输工单号+生产日期 → 查库 → 打印标签 → 记包装数/不合格数 → 写 `ProductInfo_InjectorPacking`。

## 8. 错误处理与追溯

- 所有设备异常归一为事件流（DeviceOffline / NotReady / Overload / Timeout），UI 报警，不静默吞错
- 数据库/网络闪断：失败 INSERT 落本地缓冲，恢复后补传；追溯数据不丢
- 打印前必查状态、重发前必查状态（防断号、防重号）

## 9. 验证策略

- 单点先行（开工第一周）：
  1. **串口调试助手打通电子秤**：收到 `ST,+00000.0   g` 帧，锁定 bPS/btPr/Prt/Pnt 实际值
  2. **Socket 工具打通打印机**：发 SIZE/GAP/CLS/TEXT/PRINT 五条指令验证通路；单独验证 `DMATRIX ... c126` GS1 码流，**扫码枪回读确认 AI 解析**
  3. **psql 打通数据库**：`\d` 确认 7 表字段大小写与类型，跑通三条查询 SQL
- 单测：TSPL 指令拼装快照测试、GS1 码流/FNC1 规则测试、秤帧解析 fixtures、状态字节解析测试
- 模拟器：虚拟打印机（9100 端口回放状态字节）、模拟视觉/激光 TCP 端，支撑无真机开发与 CI
- 集成验收：按第 7 节端到端流程在真机跑通，含拔线/断库恢复演练

## 10. 信息缺口（阻塞项，需尽早索要）

| # | 事项 | 对象 | 阻塞范围 |
|---|---|---|---|
| 1 | 激光打标软件通讯手册 | 打标设备厂商 | A 线打标环节详细设计 |
| 2 | 视觉系统输出协议 + DefectCode 1–8 映射 | 视觉设备厂商 | 视觉数据采集 |
| 3 | 各型号总重标称值与公差带（DefectCode 9 判定依据） | 客户质量部门 | 重量超差判定（先做成可配置） |
| 4 | 建表 DDL 或 `psql \d` 输出，确认字段大小写 | 客户 | 所有 SQL |
| 5 | `ProductInfo_PackWeight` 是否补 RecordTime | 客户 | 返工件记录定序 |
| 6 | PEX/其他机型是否存在于二期规划 | 内部 | Driver 抽象是否提前扩展 |

## 11. 里程碑

| 阶段 | 内容 | 出口标准 |
|---|---|---|
| M1 | 三链路单点验证（秤/打印机/数据库） | 第 9 节三项单点全部通过 |
| M2 | integration 层 + GS1 标签 + A 线状态机骨架（激光/视觉用模拟器） | 模拟环境端到端跑通 |
| M3 | A 线真机联调（接 PLC 联动） | A 线端到端验收 |
| M4 | B 线录入界面 + 写库 | B 线验收 |
| M5 | 激光/视觉按厂商手册实接 | 整线联调 |

## 12. 技术选型汇总

JDK 21（虚拟线程）· Spring Boot 3 · JavaFX · org.postgresql:postgresql:42.7.x · spring-jdbc + HikariCP · com.fazecast:jSerialComm:2.11.x · java.net.Socket（打印机/视觉/激光，零依赖）· com.digitalpetri.modbus:modbus-master-tcp（PLC）· SQLite/H2（本地缓冲）

参考实现：fintrace/tspl2-driver（Apache-2.0，TSPL 指令建模与 Ethernet 连接管理可借鉴）；开源结论是无现成整体方案，设备适配层按本设计自研。

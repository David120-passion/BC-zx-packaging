package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Ezcad2 激光打标驱动（通过 32 位桥接进程加载 MarkEzd.dll）。
 *
 * <p>典型流程：{@link #initialize(Path, boolean)} → {@link #buildDesign(LaserDesign)} →
 * {@link #mark(Duration)} → {@link #close()}。设计在内存中生成，无需 .ezd 模板文件。</p>
 *
 * <p>{@link #mark(Duration)} 会同步等待打标完成事件，但 {@link #stopMarking()} 可在
 * 其他线程并行调用以中止打标。没有控制卡/驱动的机器上 {@code initialize} 会失败
 * （真实错误码透传，绝不假装出光）。实例线程安全：mark 与 stopMarking 可并行。</p>
 */
public final class EzcadLaserDriver implements AutoCloseable {

    /** 常规命令（ping/load/entities/change/stop）的默认超时。 */
    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(15);
    /** SDK 初始化的默认超时；无驱动机器上 SDK 可能永不返回。 */
    public static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(20);

    private final Duration commandTimeout;
    private final Duration initTimeout;
    private final Consumer<String> initDiagnostics;

    private LaserBridgeClient client;
    private Path sdkPath;
    private boolean testMode;
    private final AtomicBoolean marking = new AtomicBoolean(false);
    private volatile List<LaserEntity> entities = List.of();

    public EzcadLaserDriver() {
        this(DEFAULT_COMMAND_TIMEOUT, DEFAULT_INIT_TIMEOUT);
    }

    public EzcadLaserDriver(Consumer<String> initDiagnostics) {
        this(DEFAULT_COMMAND_TIMEOUT, DEFAULT_INIT_TIMEOUT, initDiagnostics);
    }

    public EzcadLaserDriver(Duration commandTimeout, Duration initTimeout) {
        this(commandTimeout, initTimeout, message -> {});
    }

    private EzcadLaserDriver(Duration commandTimeout, Duration initTimeout, Consumer<String> initDiagnostics) {
        if (commandTimeout.isNegative() || commandTimeout.isZero()
                || initTimeout.isNegative() || initTimeout.isZero()) {
            throw new IllegalArgumentException("超时必须为正数");
        }
        this.commandTimeout = commandTimeout;
        this.initTimeout = initTimeout;
        this.initDiagnostics = initDiagnostics;
    }

    /**
     * 启动桥接进程并初始化 Ezcad2 SDK。
     *
     * @param sdkPath  Ezcad2 运行目录（包含 MarkEzd.dll、EZCAD.CFG、FONT、PARAM）
     * @param testMode 测试模式（{@code true} 时 SDK 模拟运行，不依赖控制卡）
     * @throws LaserException 桥接缺失、进程启动失败、路径非法或 SDK 返回错误
     */
    public synchronized void initialize(Path sdkPath, boolean testMode) throws LaserException {
        if (client != null) {
            throw new LaserException("ALREADY_INITIALIZED", "已初始化；请先 close()");
        }
        if (sdkPath == null || !Files.isDirectory(sdkPath)) {
            throw new LaserException("INVALID_PATH", "SDK 目录不存在: " + sdkPath);
        }
        if (!Files.isRegularFile(sdkPath.resolve("MarkEzd.dll"))) {
            throw new LaserException("INVALID_PATH", "SDK 目录缺少 MarkEzd.dll: " + sdkPath);
        }
        Path exe = LaserBridgeBinary.resolve();
        initDiagnostics.accept("SDK=" + sdkPath.toAbsolutePath().normalize()
                + "，EZCAD.CFG=" + Files.isRegularFile(sdkPath.resolve("EZCAD.CFG"))
                + "，FONT=" + Files.isDirectory(sdkPath.resolve("FONT"))
                + "，PARAM=" + Files.isDirectory(sdkPath.resolve("PARAM")));
        initDiagnostics.accept("桥接程序=" + exe.toAbsolutePath().normalize()
                + "，系统=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "，Java 架构=" + System.getProperty("os.arch") + "，testMode=" + testMode);
        LaserBridgeClient c = new LaserBridgeClient(exe, sdkPath, initDiagnostics);
        try {
            JsonNode ping = c.request("ping", null, commandTimeout);
            initDiagnostics.accept("桥接进程已响应，架构=" + ping.path("arch").asText("未知"));
            ObjectNode params = LaserBridgeJson.params();
            params.put("sdkPath", sdkPath.toAbsolutePath().toString());
            params.put("testMode", testMode);
            params.put("initTimeoutMs", (int) initTimeout.toMillis());
            c.request("init", params, initTimeout.plusSeconds(5));
        } catch (LaserException e) {
            c.close();
            throw e;
        }
        this.client = c;
        this.sdkPath = sdkPath.toAbsolutePath();
        this.testMode = testMode;
    }

    /** 当前 SDK 是否已初始化。 */
    public synchronized boolean isInitialized() {
        return client != null;
    }

    /** 打标是否正在进行（mark 已收到应答且完成事件未到达）。 */
    public boolean isMarking() {
        return marking.get();
    }

    /** 按内存中的设计动态创建全部打标对象，不读取或写入 .ezd 文件。 */
    public void buildDesign(LaserDesign design) throws LaserException {
        if (design == null) {
            throw new LaserException("INVALID_PARAM", "打标设计不能为空");
        }
        ObjectNode params = LaserBridgeJson.params();
        params.set("design", design.toJson());
        JsonNode result = requireClient().request("design", params, commandTimeout);
        int created = result.path("created").asInt(-1);
        if (created != design.objects().size()) {
            throw new LaserException("DESIGN_MISMATCH", "SDK 创建对象数量与设计不一致: "
                    + created + "/" + design.objects().size());
        }
        List<LaserEntity> createdEntities = new ArrayList<>();
        int index = 0;
        for (LaserObject object : design.objects()) {
            createdEntities.add(new LaserEntity(object.name(),
                    object.kind() == LaserObject.Kind.TEXT ? object.text() : null, index++));
        }
        this.entities = List.copyOf(createdEntities);
    }

    /**
     * 加载 .ezd 模板（只读，不会写回模板文件），并返回模板中的文本对象列表。
     *
     * @throws LaserException 未初始化、模板路径非法或 SDK 返回错误
     */
    public List<LaserEntity> loadTemplate(Path template) throws LaserException {
        LaserBridgeClient c = requireClient();
        if (template == null || !Files.isRegularFile(template)) {
            throw new LaserException("INVALID_PATH", "模板文件不存在: " + template);
        }
        ObjectNode params = LaserBridgeJson.params();
        params.put("templatePath", template.toAbsolutePath().toString());
        JsonNode result = c.request("load", params, commandTimeout);
        List<LaserEntity> list = LaserBridgeJson.entities(result);
        this.entities = List.copyOf(list);
        return list;
    }

    /** 当前已加载模板中的文本对象（未加载模板时为空列表）。 */
    public List<LaserEntity> textEntities() {
        return entities;
    }

    /**
     * 更新指定名称文本对象的内容（下次 mark 生效）。
     *
     * @throws LaserException 未初始化、对象不存在或非文本对象（错误码保留 SDK 原始码）
     */
    public void updateText(String name, String text) throws LaserException {
        LaserBridgeClient c = requireClient();
        if (name == null || name.isEmpty()) {
            throw new LaserException("INVALID_PARAM", "文本对象名称不能为空");
        }
        if (text == null) {
            throw new LaserException("INVALID_PARAM", "文本内容不能为 null");
        }
        ObjectNode params = LaserBridgeJson.params();
        params.put("name", name);
        params.put("text", text);
        c.request("change", params, commandTimeout);
        List<LaserEntity> updated = new ArrayList<>();
        for (LaserEntity e : entities) {
            updated.add(e.name().equals(name) && e.isText()
                    ? new LaserEntity(e.name(), text, e.order())
                    : e);
        }
        this.entities = List.copyOf(updated);
    }

    /**
     * 开始打标并同步等待完成（最多等待 {@code timeout}）。
     *
     * <p>完成事件中的 SDK 错误码非 0 时抛 {@link LaserException}（含
     * {@code sdkCode}，用户停止为 8）。超时仅结束等待，不中止打标；
     * 需要中止时请并行调用 {@link #stopMarking()}。</p>
     *
     * @throws LaserException 未初始化、打标失败或等待被中断
     */
    public void mark(Duration timeout) throws LaserException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("等待时长必须为正数");
        }
        LaserBridgeClient c = requireClient();
        if (!marking.compareAndSet(false, true)) {
            throw new LaserException("ALREADY_MARKING", "已有打标任务在执行");
        }
        try {
            java.util.concurrent.CompletableFuture<JsonNode> completion = c.beginMark(commandTimeout);
            final JsonNode data;
            try {
                data = completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new LaserException("TIMEOUT", "打标在 " + timeout.toMillis() + "ms 内未完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaserException("INTERRUPTED", "等待打标完成被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LaserException le) {
                    throw le;
                }
                throw new LaserException("INTERNAL", "打标完成事件异常: " + cause, cause);
            }
            int code = data.path("code").asInt(-1);
            if (code != 0) {
                String message = data.path("message").asText("mark failed");
                throw new LaserException("SDK_ERROR", "打标失败 (code=" + code + "): " + message, code);
            }
        } finally {
            marking.set(false);
        }
    }

    /** 持续显示当前对象的红光范围，直到主动停止或设备报错。 */
    public void redLight() throws LaserException {
        LaserBridgeClient c = requireClient();
        if (!marking.compareAndSet(false, true)) {
            throw new LaserException("ALREADY_MARKING", "已有打标任务在执行");
        }
        try {
            java.util.concurrent.CompletableFuture<JsonNode> completion = c.beginRedLight(commandTimeout);
            final JsonNode data;
            try {
                data = completion.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaserException("INTERRUPTED", "等待红光指示完成被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LaserException le) {
                    throw le;
                }
                throw new LaserException("INTERNAL", "红光指示完成事件异常: " + cause, cause);
            }
            int code = data.path("code").asInt(-1);
            if (code != 0 && code != 8) {
                String message = data.path("message").asText("red light failed");
                throw new LaserException("SDK_ERROR", "红光指示失败 (code=" + code + "): " + message, code);
            }
        } finally {
            marking.set(false);
        }
    }

    /**
     * 请求停止当前打标或红光（与 {@link #mark} 并行安全）。未在执行时为空操作。
     */
    public void stopMarking() throws LaserException {
        LaserBridgeClient c = client;
        if (c == null) {
            return;
        }
        try {
            c.request("stop", null, commandTimeout);
        } catch (LaserException e) {
            if (!"NOT_MARKING".equals(e.getCode())) {
                throw e;
            }
        }
    }

    /** 关闭桥接进程并释放 SDK；可重复调用。 */
    @Override
    public synchronized void close() {
        marking.set(false);
        LaserBridgeClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
    }

    /** SDK 目录（initialize 成功后可用）。 */
    public synchronized Path sdkPath() {
        return sdkPath;
    }

    /** 测试模式标志（initialize 成功后可用）。 */
    public synchronized boolean isTestMode() {
        return testMode;
    }

    private LaserBridgeClient requireClient() throws LaserException {
        LaserBridgeClient c = client;
        if (c == null) {
            throw new LaserException("NOT_INITIALIZED", "请先 initialize()");
        }
        return c;
    }
}

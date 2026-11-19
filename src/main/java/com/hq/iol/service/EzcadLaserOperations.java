package com.hq.iol.service;

import com.hq.iol.device.laser.EzcadLaserDriver;
import com.hq.iol.device.laser.LaserDesign;
import com.hq.iol.device.laser.LaserException;
import com.hq.iol.ui.UiLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class EzcadLaserOperations implements LaserOperations {

    private final EzcadLaserDriver driver;
    private final UiLogService uiLog;

    @Autowired
    public EzcadLaserOperations(UiLogService uiLog) {
        this(new EzcadLaserDriver(message -> uiLog.info("[激光诊断] " + message)), uiLog);
    }

    EzcadLaserOperations(EzcadLaserDriver driver) {
        this(driver, null);
    }

    private EzcadLaserOperations(EzcadLaserDriver driver, UiLogService uiLog) {
        this.driver = driver;
        this.uiLog = uiLog;
    }

    @Override
    public void initialize(Path sdkDirectory, boolean testMode) throws Exception {
        try {
            driver.initialize(sdkDirectory, testMode);
        } catch (LaserException e) {
            if (uiLog != null) {
                uiLog.error("[激光诊断] 初始化失败，bridgeCode=" + e.getCode()
                        + "，sdkCode=" + e.getSdkCode() + "，原始信息=" + e.getMessage());
            }
            throw translate(e);
        }
    }

    @Override
    public void buildDesign(LaserDesign design) throws Exception {
        try {
            driver.buildDesign(design);
        } catch (LaserException e) {
            throw translate(e);
        }
    }

    @Override
    public void mark(Duration timeout) throws Exception {
        try {
            driver.mark(timeout);
        } catch (LaserException e) {
            throw translate(e);
        }
    }

    @Override
    public void redLight() throws Exception {
        try {
            driver.redLight();
        } catch (LaserException e) {
            throw translate(e);
        }
    }

    @Override
    public void stopMarking() throws LaserException {
        driver.stopMarking();
    }

    @Override
    public void close() {
        driver.close();
    }

    private static IOException translate(LaserException error) {
        Integer code = error.getSdkCode();
        String detail = code == null ? error.getMessage()
                : sdkError(code) + "（SDK 错误码 " + code + "）";
        if ("TIMEOUT".equals(error.getCode()) || "INIT_TIMEOUT".equals(error.getCode())) {
            detail = "激光设备响应超时，请检查控制卡、加密狗（如使用）及驱动；原操作可能仍在执行，请确认现场状态";
        }
        return new IOException(detail, error);
    }

    private static String sdkError(int code) {
        return switch (code) {
            case 1 -> "Ezcad 已在运行，请关闭后重试";
            case 2 -> "找不到 EZCAD.CFG，请检查 SDK 目录";
            case 3 -> "打开激光控制卡失败";
            case 4 -> "未检测到有效激光控制设备，请检查控制卡、加密狗（如使用）及驱动";
            case 5 -> "激光控制卡版本不匹配";
            case 6 -> "找不到设备配置文件";
            case 7 -> "收到停止信号";
            case 8 -> "打标已由用户停止";
            case 9 -> "未知设备错误";
            case 10 -> "设备操作超时";
            case 11 -> "Ezcad SDK 尚未初始化";
            case 12 -> "读取打标文件失败";
            case 13 -> "打标宿主窗口无效";
            case 14 -> "找不到指定字体";
            case 15 -> "打标笔号错误";
            case 16 -> "指定对象不是文本对象";
            case 17 -> "保存打标文件失败";
            case 18 -> "找不到指定打标对象，请核对对象名称";
            case 19 -> "当前状态不允许执行该操作";
            case 20 -> "打标参数错误";
            case 21 -> "激光设备硬件错误";
            default -> "Ezcad SDK 调用失败";
        };
    }
}

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

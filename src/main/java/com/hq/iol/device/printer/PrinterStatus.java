package com.hq.iol.device.printer;

/** TSC 状态字节语义，见《TSPL/TSPL2 Programming Manual》P82（<ESC>!? 应答）。 */
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

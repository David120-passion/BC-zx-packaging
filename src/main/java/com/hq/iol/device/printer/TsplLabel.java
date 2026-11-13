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

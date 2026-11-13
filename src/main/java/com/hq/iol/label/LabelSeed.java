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

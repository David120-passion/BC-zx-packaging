package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.printer.PrinterProfile;
import com.hq.iol.device.printer.PrinterStatus;
import com.hq.iol.device.printer.TscPrinterDriver;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.label.VariableSupport;
import io.vertx.core.Vertx;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 打印业务编排：模板渲染 + 下发（打印前状态检查在驱动层）。 */
@Service
public class PrintService {

    private final ConfigService config;
    private final TemplateRepository templates;
    private final Vertx vertx;
    private final PrinterRegistry printers;

    public PrintService(ConfigService config, TemplateRepository templates, Vertx vertx,
                        PrinterRegistry printers) {
        this.config = config;
        this.templates = templates;
        this.vertx = vertx;
        this.printers = printers;
    }

    public List<LabelTemplate> listTemplates() {
        return templates.findAll();
    }

    /** 模板变量 = 显式变量绑定 ∪ GS1 模式必需变量。 */
    public Set<String> templateVariables(String templateName) {
        LabelTemplate t = templates.findByName(templateName);
        return VariableSupport.extractRequired(t.designJson());
    }

    /** 查模板并渲染 TSPL（包级可见供测试）。 */
    String buildTspl(String templateName, Map<String, String> vars) {
        LabelTemplate t = templates.findByName(templateName);
        int dpi = LabelRenderer.templateDpi(t.designJson(), config.getInt(ConfigKeys.PRINTER_DPI));
        return LabelRenderer.renderTspl(t.designJson(), vars, dpi);
    }

    /** 按当前画布设计生成实际发送的 TSPL，优先使用模板自身 DPI。 */
    public String previewTspl(String designJson, Map<String, String> vars) {
        int dpi = LabelRenderer.templateDpi(designJson, config.getInt(ConfigKeys.PRINTER_DPI));
        return LabelRenderer.renderTspl(designJson, vars, dpi);
    }

    /** 直接打印当前画布设计，发送前仍由驱动检查真实打印机状态。 */
    public synchronized void printDesign(String designJson, Map<String, String> vars)
            throws IOException, TscPrinterDriver.PrinterNotReadyException {
        newDriver().print(previewTspl(designJson, vars));
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
        PrinterProfile active = printers.active();
        return new TscPrinterDriver(vertx, active.host(), active.port(), 2000);
    }
}

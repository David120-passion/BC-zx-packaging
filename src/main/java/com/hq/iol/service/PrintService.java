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

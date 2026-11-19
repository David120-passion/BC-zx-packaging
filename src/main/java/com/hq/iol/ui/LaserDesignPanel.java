package com.hq.iol.ui;

import com.hq.iol.label.TemplateRepository;
import com.hq.iol.service.LaserService;
import com.hq.iol.service.VariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LaserDesignPanel extends DesignerPanel {

    @Autowired
    public LaserDesignPanel(@Qualifier("laserTemplateRepository") TemplateRepository repo,
                            LaserService laserService, UiLogService uiLog, VariableService variableService) {
        super(repo, laserService, uiLog, variableService);
    }

    LaserDesignPanel(TemplateRepository repo, LaserService laserService, UiLogService uiLog) {
        super(repo, laserService, uiLog);
    }
}

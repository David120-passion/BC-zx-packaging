package com.hq.iol.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppInfoTest {
    @Test
    void readsMavenFilteredVersion() {
        assertEquals("0.1.0", AppInfo.version());
    }

    @Test
    void normalizesMissingAndUnfilteredVersions() {
        assertEquals("dev", AppInfo.normalizeVersion(null));
        assertEquals("dev", AppInfo.normalizeVersion(" @project.version@ "));
        assertEquals("dev", AppInfo.normalizeVersion("${project.version}"));
        assertEquals("1.2.3", AppInfo.normalizeVersion(" 1.2.3 "));
    }
}

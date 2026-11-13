package com.hq.iol.device.scale;

import java.math.BigDecimal;

/** 秤返回 OL（超载）。 */
public class ScaleOverloadException extends Exception {

    public ScaleOverloadException(BigDecimal value) {
        super("电子秤超载，读数: " + value);
    }
}

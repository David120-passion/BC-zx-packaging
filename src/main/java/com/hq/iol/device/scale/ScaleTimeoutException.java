package com.hq.iol.device.scale;

import java.time.Duration;

/** 在限定时间内未读到稳定（ST/QT）帧。 */
public class ScaleTimeoutException extends Exception {

    public ScaleTimeoutException(Duration timeout) {
        super("等待稳定读数超时（" + timeout.toSeconds() + " 秒）");
    }
}

package com.hq.iol.device.scale;

/** 秤返回帧无法解析（帧长度、状态头、数字格式非法）。 */
public class ScaleProtocolException extends Exception {

    public ScaleProtocolException(String raw, String reason) {
        super("秤数据帧非法: [" + raw + "]，原因: " + reason);
    }
}

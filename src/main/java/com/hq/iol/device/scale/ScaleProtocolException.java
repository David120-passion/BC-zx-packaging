package com.hq.iol.device.scale;

/** KXQY 数据组无法解析（缺少结束行、状态或重量、数字格式非法）。 */
public class ScaleProtocolException extends Exception {

    public ScaleProtocolException(String raw, String reason) {
        super("秤数据帧非法: [" + raw + "]，原因: " + reason);
    }
}

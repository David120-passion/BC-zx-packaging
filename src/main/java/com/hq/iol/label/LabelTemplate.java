package com.hq.iol.label;

/** 标签模板记录。designJson 为权威设计稿。 */
public record LabelTemplate(long id, String name, String designJson,
                            boolean builtin, String updatedAt) {
}

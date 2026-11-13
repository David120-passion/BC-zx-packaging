package com.hq.iol.service;

import java.time.LocalDate;

/** A 线晶体标签数据。 */
public record LensLabelData(String type, String power, String serialNo,
                            LocalDate sterileDate, String totalDia, String bodyDia,
                            String diNo) {
}

package com.hq.iol.service;

import java.time.LocalDate;

/** B 线推注器标签数据。 */
public record InjectorLabelData(String type, String lotNo, LocalDate productionDate,
                                LocalDate expiryDate, String cutDia, String registerNo,
                                String diNo) {
}

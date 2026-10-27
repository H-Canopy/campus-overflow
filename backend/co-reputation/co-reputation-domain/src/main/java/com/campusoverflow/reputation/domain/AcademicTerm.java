package com.campusoverflow.reputation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;

/**
 * 排行榜周期键。学期划分（可按学校校历调整）：2–7 月为春季学期，8–12 月与次年 1 月为秋季学期。
 */
public final class AcademicTerm {

    public static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private AcademicTerm() {
    }

    public static String termKey(Instant at) {
        LocalDate date = at.atZone(CAMPUS_ZONE).toLocalDate();
        int year = date.getYear();
        int month = date.getMonthValue();
        // 2-7月春季，8-12月和次年1月秋季
        if (month >= 2 && month <= 7) {
            return year + "-SPRING";
        } else if (month >= 8 && month <= 12) {
            return year + "-FALL";
        } else {
            // 1月属于上一年的秋季
            return (year - 1) + "-FALL";
        }
    }

    public static String weekKey(Instant at) {
        LocalDate date = at.atZone(CAMPUS_ZONE).toLocalDate();
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        return String.format("%d-W%02d", year, week);
    }

    public static String monthKey(Instant at) {
        LocalDate date = at.atZone(CAMPUS_ZONE).toLocalDate();
        return String.format("%d-%02d", date.getYear(), date.getMonthValue());
    }
}

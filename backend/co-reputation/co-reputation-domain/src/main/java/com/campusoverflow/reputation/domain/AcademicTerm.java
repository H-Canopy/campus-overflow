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

    /** 2–7 月 = 当年春季；8–12 月与次年 1 月 = 当年秋季（1 月归上一年的秋季）。 */
    public static String termKey(Instant at) {
        LocalDate date = toCampusDate(at);
        int year = date.getYear();
        int month = date.getMonthValue();
        if (month == 1) {
            return (year - 1) + "-FALL";
        }
        if (month <= 7) {
            return year + "-SPRING";
        }
        return year + "-FALL";
    }

    /** ISO 周编号，跨年周归属 ISO 的周所属年，格式 2026-W41。 */
    public static String weekKey(Instant at) {
        LocalDate date = toCampusDate(at);
        int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%d-W%02d", weekYear, week);
    }

    public static String monthKey(Instant at) {
        LocalDate date = toCampusDate(at);
        return String.format("%d-%02d", date.getYear(), date.getMonthValue());
    }

    private static LocalDate toCampusDate(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("时间不能为空");
        }
        return at.atZone(CAMPUS_ZONE).toLocalDate();
    }
}

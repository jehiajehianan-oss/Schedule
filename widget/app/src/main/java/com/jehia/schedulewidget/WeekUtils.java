package com.jehia.schedulewidget;

import android.content.Context;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** 教学周计算：以开学那一周的周一作为第 1 周的起点。 */
public final class WeekUtils {

    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private WeekUtils() {
    }

    /** 今天对应 1-7，周一是 1。 */
    public static int todayWeekday() {
        return LocalDate.now().getDayOfWeek().getValue();
    }

    public static String weekdayName(int weekday) {
        if (weekday < 1 || weekday > 7) {
            return "";
        }
        return NAMES[weekday - 1];
    }

    /** 开学日期所在周的周一；没设置或格式不对时返回 null。 */
    public static LocalDate firstMonday(Context context) {
        String raw = Prefs.getSemesterStart(context);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw, ISO)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 当前教学周；没设置开学日期时返回 0。 */
    public static int currentWeek(Context context) {
        LocalDate monday = firstMonday(context);
        if (monday == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(monday, LocalDate.now());
        if (days < 0) {
            return 0;
        }
        return (int) (days / 7) + 1;
    }
}

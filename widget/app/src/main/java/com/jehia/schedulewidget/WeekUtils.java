package com.jehia.schedulewidget;

import android.content.Context;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** 教学周与日期的小工具。 */
public final class WeekUtils {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String[] NAMES = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

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

    /** 某个日期所在周的周一。 */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 开学日期所在周的周一；没设置或格式不对时返回 null。 */
    public static LocalDate firstMonday(Context context) {
        return Prefs.semesterMonday(context);
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

    /** 第 week 周的周一；week <= 0 时返回本周一。 */
    public static LocalDate mondayOfWeek(Context context, int week) {
        LocalDate first = firstMonday(context);
        if (first == null || week <= 0) {
            return mondayOf(LocalDate.now());
        }
        return first.plusWeeks(week - 1L);
    }

    /** 「9/22」这样的月日。 */
    public static String monthDay(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    /** 「9/22–9/28」这一周的日期区间。 */
    public static String weekRange(Context context, int week) {
        LocalDate monday = mondayOfWeek(context, week);
        return monthDay(monday) + "–" + monthDay(monday.plusDays(6));
    }

    /** 期末周起始周，0 = 没启用。 */
    public static int finalsStartWeek(Context context) {
        return ScheduleStore.finalsStartWeek(context);
    }

    /** 学期总周数，默认 20。 */
    public static int semesterWeeks(Context context) {
        return ScheduleStore.semesterWeeks(context);
    }

    public static boolean finalsEnabled(Context context) {
        return finalsStartWeek(context) > 0;
    }

    /** 当前这一周是不是期末周（含期末周本身，一直到最后一周）。 */
    public static boolean inFinals(Context context) {
        int week = currentWeek(context);
        return week > 0 && finalsEnabled(context) && week >= finalsStartWeek(context);
    }

    /** 设置开学日期用：ISO 文本 -> yyyy-MM-dd，失败返回空串。 */
    public static String isoOf(LocalDate date) {
        return date == null ? "" : date.format(ISO);
    }
}
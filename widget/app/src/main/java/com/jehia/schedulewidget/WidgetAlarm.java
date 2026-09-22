package com.jehia.schedulewidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 小组件的翻页闹钟。
 *
 * 30 分钟的 updatePeriodMillis 是系统下限，上课期间「还剩 75 分钟」这种相对时间最坏会
 * 过期半小时，所以在三个时刻各排一次闹钟：
 *
 *   下一节课开始   到点翻到「在课」
 *   当前课结束     到点翻到「下一节」
 *   上课期间       每分钟滚一次，下课即停
 *   当天没课了     跨天 00:00:10 翻页（顺带把日期 / 周次刷新）
 *
 * 只用 setAndAllowWhileIdle：它在低电耗模式下也能唤醒，而且**不需要** SCHEDULE_EXACT_ALARM
 * 权限，权限列表保持为空。代价是系统可能把触发时间往后浮动几分钟，这是刻意的取舍。
 * 每次都先 cancel 再 set，同一个 PendingIntent（固定 requestCode），所以重复调度不会叠加。
 */
final class WidgetAlarm {

    private static final int REQUEST_CODE = 91;
    private static final String ACTION = "com.jehia.schedulewidget.action.TICK";
    private static final long TICK_MILLIS = 60_000L;

    private WidgetAlarm() {
    }

    /** 排下一次刷新；没有小组件在桌面上就什么都不做。 */
    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        if (manager == null) {
            return;
        }
        PendingIntent intent = pending(app);
        manager.cancel(intent);
        long at = nextFireAt(app);
        if (at <= 0) {
            return;
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent);
        } catch (SecurityException ignored) {
            // 个别厂商 ROM 会拦；拦了也不影响 30 分钟的兜底刷新
        }
    }

    static void cancel(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        if (manager != null) {
            manager.cancel(pending(app));
        }
    }

    /** 下一次该刷新的时刻（epoch 毫秒）；不需要刷新时返回 0。 */
    static long nextFireAt(Context context) {
        if (!WidgetCommon.hasWidget(context)) {
            return 0;
        }
        int week = WeekUtils.currentWeek(context);
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        long tick = System.currentTimeMillis() + TICK_MILLIS;

        List<Course> courses = ScheduleStore.forDay(context, WeekUtils.todayWeekday(), week);
        long nextStart = 0;
        for (Course course : courses) {
            LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
            LocalTime end = ScheduleStore.sectionEnd(context, course.end);
            if (begin == null || end == null) {
                continue;
            }
            if (!now.isBefore(begin) && !now.isAfter(end)) {
                // 正在上课：每分钟滚一次，下课后立刻翻页
                return Math.min(tick, at(today, end));
            }
            if (now.isBefore(begin)) {
                nextStart = nextStart == 0 ? at(today, begin) : Math.min(nextStart, at(today, begin));
            }
        }
        if (nextStart > 0) {
            return nextStart;
        }
        // 今天的课上完了 / 今天没课：跨天翻页，把日期和周次一起刷新
        return at(today.plusDays(1), LocalTime.of(0, 0, 10));
    }

    private static long at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, WidgetAlarmReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
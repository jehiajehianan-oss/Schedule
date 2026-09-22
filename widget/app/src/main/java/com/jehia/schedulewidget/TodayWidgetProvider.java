package com.jehia.schedulewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 「今日课程」小组件：一张卡回答「我现在上什么课」。
 *
 * 信息层级只允许一处大字：课名 20sp，其它都 <= 14sp。
 * 四种状态：
 *   正在上课  —— 课程自己颜色的圆角竖条 + 实心强调色药丸「在课 · 还剩 N 分钟」+ 进度条
 *   课间      —— 主角换成下一节，药丸写「下一节 · N 分钟后上课」
 *   没课      —— 全部降成灰阶，不留任何强调色
 *   期末周    —— 考试优先，没有考试就预告下一场
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    /** 高度够放「下一节」这一行的下限；小尺寸拉不到这么高时只显示当前课程。 */
    private static final int MIN_HEIGHT_FOR_NEXT = 168;
    /** 宽度太窄时（2×2）状态药丸只留两三个字，否则会把标题挤没。 */
    private static final int COMPACT_WIDTH = 200;

    /** 课名是整张卡唯一的大字；空状态降一档，免得「今天没有课」也那么抢眼。 */
    private static final float NAME_SIZE_SP = 20f;
    private static final float TIME_SIZE_SP = 14f;
    private static final float EMPTY_NAME_SIZE_SP = 17f;
    private static final float EMPTY_TIME_SIZE_SP = 13f;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, Bundle options) {
        update(context, manager, new int[]{id});
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        if (!WidgetCommon.hasWidget(context)) {
            WidgetAlarm.cancel(context);
        }
    }

    @Override
    public void onDisabled(Context context) {
        WidgetAlarm.cancel(context);
    }

    static void update(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, build(context, manager, id));
        }
        WidgetAlarm.schedule(context);
    }

    /** 组件被拉大拉小时按实际高度决定要不要显示「下一节」。 */
    private static boolean roomForNext(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (height <= 0) {
            height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        }
        // 拿不到尺寸（0）就按「放得下」处理，宁可多显示也不要少显示。
        return height <= 0 || height >= MIN_HEIGHT_FOR_NEXT;
    }

    private static boolean isCompact(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        if (width <= 0) {
            width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        }
        return width > 0 && width < COMPACT_WIDTH;
    }

    private static RemoteViews build(Context context, AppWidgetManager manager, int id) {
        boolean showNext = roomForNext(manager, id);
        boolean compact = isCompact(manager, id);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        views.setInt(R.id.widget_root, "setBackgroundResource",
                WidgetCommon.widgetBackground(WidgetCommon.isNight(context)));
        views.setInt(R.id.divider, "setBackgroundColor", WidgetCommon.divider(context));
        views.setTextColor(R.id.header, WidgetCommon.textSecondary(context));
        views.setOnClickPendingIntent(R.id.widget_root, WidgetCommon.openApp(context));

        int week = WeekUtils.currentWeek(context);
        LocalDate today = LocalDate.now();
        String header = (compact || week == 0 ? "" : "第 " + week + " 周 · ")
                + WeekUtils.weekdayName(WeekUtils.todayWeekday())
                + " " + WeekUtils.monthDay(today);
        views.setTextViewText(R.id.header, header);

        if (week == 0) {
            showMessage(context, views, "还没设置开学日期", "点一下打开应用设置");
        } else if (WeekUtils.inFinals(context)) {
            renderFinals(context, views, header, showNext, compact);
        } else {
            render(context, views, week, showNext, compact);
        }
        return views;
    }

    /** 按当前时间挑出「正在上课 / 下一节 / 再下一节」。 */
    private static void render(Context context, RemoteViews views, int week,
                               boolean showNext, boolean compact) {
        List<Course> courses = ScheduleStore.forDay(context, WeekUtils.todayWeekday(), week);
        LocalTime now = LocalTime.now();

        Course current = null;
        Course next = null;
        Course after = null;
        for (Course course : courses) {
            LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
            LocalTime end = ScheduleStore.sectionEnd(context, course.end);
            if (begin == null || end == null) {
                continue;
            }
            if (!now.isBefore(begin) && !now.isAfter(end)) {
                current = course;
            } else if (now.isBefore(begin)) {
                if (next == null) {
                    next = course;
                } else if (after == null) {
                    after = course;
                }
            }
        }

        if (current != null) {
            String status = compact ? "在课"
                    : "在课 · " + remaining(now, current, context);
            showCourse(context, views, current, status);
            int progress = progressOf(context, now, current);
            views.setViewVisibility(R.id.progress, progress < 0 ? View.GONE : View.VISIBLE);
            if (progress >= 0) {
                views.setProgressBar(R.id.progress, 100, progress, false);
            }
            showNextRow(context, views, showNext, next, "下一节");
            return;
        }

        if (next != null) {
            String status = compact ? "下一节"
                    : "下一节 · " + before(now, next, context);
            showCourse(context, views, next, status);
            views.setViewVisibility(R.id.progress, View.GONE);
            showNextRow(context, views, showNext, after, "再下一节");
            return;
        }

        showMessage(context, views,
                courses.isEmpty() ? "今天没有课" : "今天的课已经上完了", "好好休息");
    }

    /** 期末周：考试优先，当天没考试就预告下一场。 */
    private static void renderFinals(Context context, RemoteViews views, String header,
                                     boolean showNext, boolean compact) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        List<Exam> all = ScheduleStore.sortedExams(context);

        Exam running = null;
        Exam later = null;
        Exam upcoming = null;
        for (Exam exam : all) {
            LocalDate day = exam.day();
            LocalTime begin = exam.beginTime();
            LocalTime end = exam.endTime();
            if (day == null || begin == null || end == null) {
                continue;
            }
            if (day.equals(today) && !now.isBefore(begin) && !now.isAfter(end)) {
                running = exam;
                break;
            }
            if (running == null && later == null && day.equals(today) && now.isBefore(begin)) {
                later = exam;
            }
            if (upcoming == null && (day.isAfter(today)
                    || (day.equals(today) && now.isBefore(end)))) {
                upcoming = exam;
            }
        }
        Exam target = running != null ? running : (later != null ? later : upcoming);
        if (target == null) {
            showMessage(context, views, "今天没有考试", "好好复习");
            return;
        }

        LocalDate day = target.day();
        LocalTime begin = target.beginTime();
        LocalTime end = target.endTime();
        String status;
        boolean progress = false;
        if (running != null) {
            long minutes = Math.max(0, Duration.between(now, end).toMinutes());
            status = "考试中 · " + minutes + " 分钟后结束";
            progress = true;
        } else if (later != null) {
            long minutes = Math.max(0, Duration.between(now, begin).toMinutes());
            status = minutes >= 60 ? "距考试 " + (minutes / 60) + " 小时"
                    : "距考试 " + minutes + " 分钟";
        } else {
            status = WeekUtils.monthDay(day) + " 开考";
        }

        String when = running == null && later == null
                ? WeekUtils.weekdayName(day.getDayOfWeek().getValue())
                + " " + WeekUtils.monthDay(day) : "";
        views.setTextViewText(R.id.header, when.isEmpty() ? header : header + " · " + when);

        showHero(context, views, WidgetCommon.accent(context), target.name,
                target.begin + "—" + target.end, target.room,
                compact ? "" : status);

        if (progress) {
            LocalTime beginTime = begin;
            long total = Math.max(1, Duration.between(beginTime, end).getSeconds());
            long done = Math.max(0, Duration.between(beginTime, now).getSeconds());
            int percent = (int) Math.max(0, Math.min(100, done * 100 / total));
            views.setViewVisibility(R.id.progress, View.VISIBLE);
            views.setProgressBar(R.id.progress, 100, percent, false);
        } else {
            views.setViewVisibility(R.id.progress, View.GONE);
        }

        Exam coming = null;
        for (Exam exam : all) {
            if (exam.sortKey().compareTo(target.sortKey()) > 0) {
                coming = exam;
                break;
            }
        }
        if (showNext && coming != null) {
            LocalDate comingDay = coming.day();
            RemoteViews row = new RemoteViews(context.getPackageName(),
                    R.layout.widget_today_row);
            row.setTextViewText(R.id.row_label, "下一场");
            row.setTextViewText(R.id.row_name, coming.name);
            row.setTextViewText(R.id.row_time, comingDay == null ? ""
                    : WeekUtils.monthDay(comingDay));
            row.setTextViewText(R.id.row_room, coming.begin);
            styleNextRow(context, row);
            views.addView(R.id.next, row);
            showNextDivider(views);
        } else {
            hideNext(views);
        }
    }

    private static void showNextRow(Context context, RemoteViews views, boolean showNext,
                                    Course course, String label) {
        if (showNext && course != null) {
            RemoteViews row = new RemoteViews(context.getPackageName(),
                    R.layout.widget_today_row);
            row.setTextViewText(R.id.row_label, label);
            row.setTextViewText(R.id.row_name, course.name);
            row.setTextViewText(R.id.row_time, beginClock(context, course));
            row.setTextViewText(R.id.row_room, course.room);
            styleNextRow(context, row);
            views.addView(R.id.next, row);
            showNextDivider(views);
        } else {
            hideNext(views);
        }
    }

    private static void styleNextRow(Context context, RemoteViews row) {
        row.setTextColor(R.id.row_label, WidgetCommon.textSecondary(context));
        row.setTextColor(R.id.row_name, WidgetCommon.textPrimary(context));
        row.setTextColor(R.id.row_time, WidgetCommon.textSecondary(context));
        row.setTextColor(R.id.row_room, WidgetCommon.textSecondary(context));
    }

    private static void showNextDivider(RemoteViews views) {
        views.setViewVisibility(R.id.divider, View.VISIBLE);
        views.setViewVisibility(R.id.next, View.VISIBLE);
    }

    /** 没有下一节时整块收掉：分隔线、NEXT 行都不出现。 */
    private static void hideNext(RemoteViews views) {
        views.removeAllViews(R.id.next);
        views.setViewVisibility(R.id.divider, View.GONE);
        views.setViewVisibility(R.id.next, View.GONE);
    }

    private static void showCourse(Context context, RemoteViews views, Course course,
                                   String status) {
        showHero(context, views, WidgetCommon.blockFill(course), course.name,
                ScheduleStore.timeRange(context, course).replace('-', '—'),
                metaOf(course), status);
    }

    private static void showHero(Context context, RemoteViews views, int barColor,
                                 String name, String time, String meta, String status) {
        views.setViewVisibility(R.id.hero, View.VISIBLE);
        views.setViewVisibility(R.id.status, status.isEmpty() ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.status, status);
        WidgetCommon.applyBar(views, barColor);
        views.setViewVisibility(R.id.status_bar, View.VISIBLE);
        views.setViewPadding(R.id.hero_txt, dp(context, 10), 0, 0, 0);
        views.setTextViewTextSize(R.id.course_name, TypedValue.COMPLEX_UNIT_SP, NAME_SIZE_SP);
        views.setTextViewTextSize(R.id.course_time, TypedValue.COMPLEX_UNIT_SP, TIME_SIZE_SP);

        views.setTextColor(R.id.course_name, WidgetCommon.textPrimary(context));
        views.setTextColor(R.id.course_time, WidgetCommon.textPrimary(context));
        views.setTextColor(R.id.course_meta, WidgetCommon.textSecondary(context));
        views.setTextViewText(R.id.course_name, name);
        views.setTextViewText(R.id.course_time, time);
        views.setTextViewText(R.id.course_meta, meta == null ? "" : meta);
    }

    /** 没课时整块降成灰阶：没有强调色也没有竖条，也就没有「现在有事」的暗示。 */
    private static void showMessage(Context context, RemoteViews views,
                                    String title, String hint) {
        views.setViewVisibility(R.id.status, View.GONE);
        views.setViewVisibility(R.id.status_bar, View.GONE);
        views.setViewVisibility(R.id.progress, View.GONE);
        hideNext(views);
        views.setViewVisibility(R.id.hero, View.VISIBLE);
        views.setViewPadding(R.id.hero_txt, 0, 0, 0, 0);
        views.setTextViewTextSize(R.id.course_name,
                TypedValue.COMPLEX_UNIT_SP, EMPTY_NAME_SIZE_SP);
        views.setTextViewTextSize(R.id.course_time,
                TypedValue.COMPLEX_UNIT_SP, EMPTY_TIME_SIZE_SP);
        views.setTextColor(R.id.course_name, WidgetCommon.textSecondary(context));
        views.setTextColor(R.id.course_time, WidgetCommon.textSecondary(context));
        views.setTextColor(R.id.course_meta, WidgetCommon.textSecondary(context));
        views.setTextViewText(R.id.course_name, title);
        views.setTextViewText(R.id.course_time, hint);
        views.setTextViewText(R.id.course_meta, "");
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 「第3-4节 · 综合楼702 · 杨京辉」，空字段自动跳过。 */
    private static String metaOf(Course course) {
        StringBuilder text = new StringBuilder();
        appendPart(text, "第" + course.start + "-" + course.end + "节");
        appendPart(text, course.room);
        appendPart(text, course.teacher);
        return text.toString();
    }

    private static void appendPart(StringBuilder text, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(part);
    }

    /** 上课还剩几分钟，向上取整，避免出现「还剩 0 分钟」。 */
    private static String remaining(LocalTime now, Course course, Context context) {
        LocalTime end = ScheduleStore.sectionEnd(context, course.end);
        if (end == null) {
            return "";
        }
        long seconds = Duration.between(now, end).getSeconds();
        if (seconds <= 0) {
            return "就要下课";
        }
        return "还剩 " + roundUpMinutes(seconds) + " 分钟";
    }

    /** 距离上课还有几分钟。 */
    private static String before(LocalTime now, Course course, Context context) {
        LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
        if (begin == null) {
            return "";
        }
        long seconds = Duration.between(now, begin).getSeconds();
        if (seconds <= 0) {
            return "马上上课";
        }
        return roundUpMinutes(seconds) + " 分钟后上课";
    }

    private static long roundUpMinutes(long seconds) {
        return Math.max(0, (seconds + 59) / 60);
    }

    /** 已上课的百分比；作息时间缺失时返回 -1（不显示进度条）。 */
    private static int progressOf(Context context, LocalTime now, Course course) {
        LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
        LocalTime end = ScheduleStore.sectionEnd(context, course.end);
        if (begin == null || end == null || !end.isAfter(begin)) {
            return -1;
        }
        long total = Duration.between(begin, end).getSeconds();
        long done = Duration.between(begin, now).getSeconds();
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    /** 上课时间，例如 13:30；作息时间缺失时返回空串。 */
    private static String beginClock(Context context, Course course) {
        LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
        return begin == null ? "" : begin.toString();
    }
}

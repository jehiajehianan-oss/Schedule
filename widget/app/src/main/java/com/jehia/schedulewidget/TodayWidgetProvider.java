package com.jehia.schedulewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 「今日课程」小组件：一张卡回答「我现在上什么课」。
 *
 * 三种状态：
 *   正在上课 —— 强调色状态条 + 进度条 + 还剩几分钟
 *   课间     —— 主角换成下一节，标出还有几分钟上课
 *   没课     —— 全部降成灰阶，不留任何强调色
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    /** 高度够放「下一节」这一行的下限；小尺寸拉不到这么高时只显示当前课程。 */
    private static final int MIN_HEIGHT_FOR_NEXT = 168;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, Bundle options) {
        update(context, manager, new int[]{id});
    }

    static void update(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id,
                    build(context, roomForNext(manager, id), isCompact(manager, id)));
        }
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

    /** 宽度太窄时（2×2）状态标签只留两三个字，否则会把标题挤没。 */
    private static boolean isCompact(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        if (width <= 0) {
            width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        }
        return width > 0 && width < 200;
    }

    private static RemoteViews build(Context context, boolean showNext, boolean compact) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        views.setInt(R.id.widget_root, "setBackgroundResource",
                WidgetCommon.widgetBackground(WidgetCommon.isNight(context)));
        views.setInt(R.id.divider, "setBackgroundColor", WidgetCommon.divider(context));
        views.setInt(R.id.status_bar, "setBackgroundColor", WidgetCommon.accent(context));
        views.setTextColor(R.id.header, WidgetCommon.textSecondary(context));
        views.setTextColor(R.id.status, WidgetCommon.accentText(context));

        int week = WeekUtils.currentWeek(context);
        LocalDate today = LocalDate.now();
        String header = WeekUtils.weekdayName(WeekUtils.todayWeekday())
                + " " + today.getMonthValue() + "/" + today.getDayOfMonth();
        if (week > 0 && !compact) {
            header = "第 " + week + " 周 · " + header;
        }
        views.setTextViewText(R.id.header, header);
        views.removeAllViews(R.id.next);

        if (week == 0) {
            showMessage(views, context, "还没设置开学日期", "点一下打开应用设置");
            views.setViewVisibility(R.id.divider, View.GONE);
        } else {
            render(context, views, week, showNext, compact);
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetCommon.openApp(context));
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
            showCourse(context, views, current, "● 在课",
                    compact ? "" : remaining(now, current, context));
            int progress = progressOf(context, now, current);
            views.setViewVisibility(R.id.progress, progress < 0 ? View.GONE : View.VISIBLE);
            if (progress >= 0) {
                views.setProgressBar(R.id.progress, 100, progress, false);
            }
            views.setViewVisibility(R.id.divider,
                    showNext && addNextRow(context, views, next, "下一节")
                            ? View.VISIBLE : View.GONE);
            return;
        }

        if (next != null) {
            showCourse(context, views, next, "● 下一节",
                    compact ? "" : before(now, next, context));
            views.setViewVisibility(R.id.progress, View.GONE);
            views.setViewVisibility(R.id.divider,
                    showNext && addNextRow(context, views, after, "再下一节")
                            ? View.VISIBLE : View.GONE);
            return;
        }

        showMessage(views, context,
                courses.isEmpty() ? "今天没有课" : "今天的课已经上完了",
                "好好休息");
        views.setViewVisibility(R.id.divider, View.GONE);
    }

    private static void showCourse(Context context, RemoteViews views, Course course,
                                   String status, String hint) {
        views.setViewVisibility(R.id.status, View.VISIBLE);
        views.setViewVisibility(R.id.status_bar, View.VISIBLE);
        views.setTextViewText(R.id.status, hint.isEmpty() ? status : status + " · " + hint);

        views.setTextColor(R.id.course_name, WidgetCommon.textPrimary(context));
        views.setTextColor(R.id.course_time, WidgetCommon.textPrimary(context));
        views.setTextColor(R.id.course_meta, WidgetCommon.textSecondary(context));
        views.setTextViewText(R.id.course_name, course.name);
        views.setTextViewText(R.id.course_time,
                ScheduleStore.timeRange(context, course).replace('-', '—'));
        views.setTextViewText(R.id.course_meta, metaOf(course));
    }

    /** 没课时整块降成灰阶：没有强调色，也就没有「现在有事」的暗示。 */
    private static void showMessage(RemoteViews views, Context context,
                                    String title, String hint) {
        views.setViewVisibility(R.id.status, View.GONE);
        views.setViewVisibility(R.id.status_bar, View.GONE);
        views.setViewVisibility(R.id.progress, View.GONE);
        views.setTextColor(R.id.course_name, WidgetCommon.textSecondary(context));
        views.setTextColor(R.id.course_time, WidgetCommon.textSecondary(context));
        views.setTextViewText(R.id.course_name, title);
        views.setTextViewText(R.id.course_time, hint);
        views.setTextViewText(R.id.course_meta, "");
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

    /** 加一行「下一节」；没有下一节课时返回 false。 */
    private static boolean addNextRow(Context context, RemoteViews views,
                                      Course course, String label) {
        if (course == null) {
            return false;
        }
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_today_row);
        row.setTextViewText(R.id.row_label, label);
        row.setTextViewText(R.id.row_name, course.name);
        row.setTextViewText(R.id.row_time, beginClock(context, course));
        row.setTextViewText(R.id.row_room, course.room);
        row.setTextColor(R.id.row_label, WidgetCommon.textSecondary(context));
        row.setTextColor(R.id.row_name, WidgetCommon.textPrimary(context));
        row.setTextColor(R.id.row_time, WidgetCommon.textSecondary(context));
        row.setTextColor(R.id.row_room, WidgetCommon.textSecondary(context));
        views.addView(R.id.next, row);
        return true;
    }

    /** 上课时间，例如 13:30；作息时间缺失时返回空串。 */
    private static String beginClock(Context context, Course course) {
        LocalTime begin = ScheduleStore.sectionBegin(context, course.start);
        return begin == null ? "" : begin.toString();
    }
}

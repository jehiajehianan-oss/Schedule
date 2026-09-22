package com.jehia.schedulewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.util.List;

/**
 * 「本周课表」小组件：周一到周日整张网格，同一门课同一个颜色。
 *
 * 课块 = 白色圆角形状 + setColorFilter 现染的颜色（见 WidgetCommon.applyBlock），所以色卡随便换、
 * 单门课也能有自己的颜色。整块的效果靠两件事：
 *   - 圆角只画在整块的外侧：首格圆上边、尾格圆下边、单格四角都圆、中格是方的
 *   - 格子上下的 1dp 缝只留在整块的外侧，块内部上下不留缝，于是多节连成一块
 * 今天那一列整列铺一层淡强调色，表头做成实心强调色胶囊。
 * 期末周内整块换成考试表（按日期排的行），不再显示常规课程。
 */
public class WeekWidgetProvider extends AppWidgetProvider {

    private static final int MAX_SECTION = 12;
    private static final int MAX_EXAM_ROWS = 6;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, android.os.Bundle options) {
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
            manager.updateAppWidget(id, build(context));
        }
        WidgetAlarm.schedule(context);
    }

    private static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_week);
        views.setInt(R.id.widget_root, "setBackgroundResource",
                WidgetCommon.widgetBackground(WidgetCommon.isNight(context)));
        views.setTextColor(R.id.title, WidgetCommon.textPrimary(context));
        views.setTextColor(R.id.range, WidgetCommon.textSecondary(context));
        views.setOnClickPendingIntent(R.id.widget_root, WidgetCommon.openApp(context));
        views.removeAllViews(R.id.grid);

        int week = WeekUtils.currentWeek(context);
        if (WeekUtils.inFinals(context)) {
            views.setTextViewText(R.id.title, "期末考试");
            views.setTextViewText(R.id.range, WeekUtils.weekRange(context, week));
            addExamRows(context, views, week);
            return views;
        }
        if (week > 0) {
            views.setTextViewText(R.id.title, "第 " + week + " 周");
            views.setTextViewText(R.id.range, WeekUtils.weekRange(context, week));
        } else {
            views.setTextViewText(R.id.title, "未设置开学日期，显示全部课程");
            views.setTextViewText(R.id.range, "");
        }
        addGrid(context, views, week);
        return views;
    }

    // ---------------------------------------------------------------- 常规周视图

    private static void addGrid(Context context, RemoteViews views, int week) {
        List<Course> courses = ScheduleStore.forWeek(context, week);
        int maxSection = 1;
        for (Course course : courses) {
            maxSection = Math.max(maxSection, Math.min(course.end, MAX_SECTION));
        }
        int today = WeekUtils.todayWeekday();

        RemoteViews header = new RemoteViews(context.getPackageName(),
                R.layout.widget_week_header_row);
        header.removeAllViews(R.id.row);
        header.addView(R.id.row, timeCell(context, "", 0, false));
        for (int day = 1; day <= 7; day++) {
            header.addView(R.id.row,
                    headerCell(context, WeekUtils.weekdayName(day), day == today));
        }
        views.addView(R.id.grid, header);

        for (int section = 1; section <= maxSection; section++) {
            RemoteViews row = new RemoteViews(context.getPackageName(),
                    R.layout.widget_week_row);
            row.removeAllViews(R.id.row);
            row.addView(R.id.row, timeCell(context, String.valueOf(section), section, true));
            for (int day = 1; day <= 7; day++) {
                row.addView(R.id.row, cell(context, courses, day, section, today));
            }
            views.addView(R.id.grid, row);
        }
    }

    /** 表头：今天那一格是实心强调色胶囊 + 白字。 */
    private static RemoteViews headerCell(Context context, String label, boolean highlight) {
        RemoteViews cell = new RemoteViews(context.getPackageName(),
                R.layout.widget_week_header);
        cell.setTextViewText(R.id.header, label);
        if (highlight) {
            cell.setInt(R.id.header, "setBackgroundResource", R.drawable.header_pill);
            cell.setTextColor(R.id.header, Color.WHITE);
        } else {
            cell.setInt(R.id.header, "setBackgroundColor", Color.TRANSPARENT);
            cell.setTextColor(R.id.header, WidgetCommon.textSecondary(context));
        }
        return cell;
    }

    /** 左侧时间列：上行节次序号，下行该节开始时间，中间一条 1px 分隔线。 */
    private static RemoteViews timeCell(Context context, String label, int section,
                                        boolean withHair) {
        RemoteViews cell = new RemoteViews(context.getPackageName(),
                R.layout.widget_week_time);
        cell.setTextViewText(R.id.section_no, label);
        cell.setTextViewText(R.id.section_clock,
                section > 0 ? ScheduleStore.sectionBeginText(context, section) : "");
        cell.setTextColor(R.id.section_no, WidgetCommon.textSecondary(context));
        cell.setTextColor(R.id.section_clock, WidgetCommon.textSecondary(context));
        cell.setInt(R.id.hair, "setBackgroundColor", WidgetCommon.divider(context));
        cell.setViewVisibility(R.id.hair, withHair ? View.VISIBLE : View.INVISIBLE);
        return cell;
    }

    private static RemoteViews cell(Context context, List<Course> courses,
                                    int day, int section, int today) {
        RemoteViews cell = new RemoteViews(context.getPackageName(),
                R.layout.widget_week_cell);
        cell.setInt(R.id.hair, "setBackgroundColor", WidgetCommon.divider(context));
        // 空着的格子也给今天铺一层淡底色，整列就自动连成一条
        cell.setInt(R.id.band, "setBackgroundColor", day == today
                ? WidgetCommon.accentSoft(context) : Color.TRANSPARENT);

        Course primary = primaryAt(courses, day, section);
        if (primary == null) {
            cell.setViewVisibility(R.id.box_wrap, View.INVISIBLE);
            return cell;
        }

        int position = primary.positionAt(section);
        WidgetCommon.applyBlock(cell, primary, position);
        // 一门课占多节时只在第一节写课名，整块看起来就是一整块
        cell.setTextViewText(R.id.box, section <= primary.start ? primary.name : "");

        int inset = dp(context, 1);
        boolean roundTop = position == Palette.POS_FIRST || position == Palette.POS_SINGLE;
        boolean roundBottom = position == Palette.POS_LAST || position == Palette.POS_SINGLE;
        // 块内部上下不留缝（0dp），只在整块的外侧留 1dp，所以跨节不会断成虚线
        cell.setViewPadding(R.id.box_wrap, inset, roundTop ? inset : 0,
                inset, roundBottom ? inset : 0);
        cell.setViewVisibility(R.id.box_wrap, View.VISIBLE);
        return cell;
    }

    /** 同一格里若有多门课重叠，优先显示「正好从这一节开始」的那门。 */
    private static Course primaryAt(List<Course> courses, int day, int section) {
        Course best = null;
        for (Course course : courses) {
            if (course.weekday != day || !course.covers(section)) {
                continue;
            }
            if (best == null) {
                best = course;
                continue;
            }
            boolean startsNow = course.start == section;
            boolean bestStartsNow = best.start == section;
            if (startsNow && !bestStartsNow) {
                best = course;
            } else if (startsNow == bestStartsNow && course.start > best.start) {
                best = course;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 期末周考试表

    private static void addExamRows(Context context, RemoteViews views, int week) {
        LocalDate monday = WeekUtils.mondayOfWeek(context, week);
        List<Exam> exams = ScheduleStore.examsBetween(context, monday, monday.plusDays(6));
        if (exams.isEmpty()) {
            views.addView(R.id.grid, note(context, "本周没有考试安排"));
            return;
        }
        int shown = Math.min(exams.size(), MAX_EXAM_ROWS);
        for (int index = 0; index < shown; index++) {
            Exam exam = exams.get(index);
            LocalDate day = exam.day();
            RemoteViews row = new RemoteViews(context.getPackageName(),
                    R.layout.widget_week_exam);
            row.setTextViewText(R.id.ex_when, day == null ? "" : WeekUtils.weekdayName(
                    day.getDayOfWeek().getValue()) + " " + WeekUtils.monthDay(day));
            row.setTextViewText(R.id.ex_clock, exam.begin + "–" + exam.end);
            row.setTextViewText(R.id.ex_name, exam.room == null || exam.room.isEmpty()
                    ? exam.name : exam.name + " · " + exam.room);
            row.setTextColor(R.id.ex_when, WidgetCommon.textPrimary(context));
            row.setTextColor(R.id.ex_clock, WidgetCommon.textSecondary(context));
            row.setTextColor(R.id.ex_name, WidgetCommon.textSecondary(context));
            views.addView(R.id.grid, row);
        }
        if (exams.size() > shown) {
            views.addView(R.id.grid, note(context, "还有 " + (exams.size() - shown) + " 场…"));
        }
    }

    private static RemoteViews note(Context context, String text) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_week_note);
        row.setTextViewText(R.id.note, text);
        row.setTextColor(R.id.note, WidgetCommon.textSecondary(context));
        return row;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
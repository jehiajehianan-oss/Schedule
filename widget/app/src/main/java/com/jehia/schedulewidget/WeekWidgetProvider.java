package com.jehia.schedulewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

/** 「本周课表」小组件：用 GridLayout 拼出一张周一到周日的课表。 */
public class WeekWidgetProvider extends AppWidgetProvider {

    private static final int MAX_SECTION = 12;

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
            manager.updateAppWidget(id, build(context));
        }
    }

    private static RemoteViews build(Context context) {
        boolean night = WidgetCommon.isNight(context);
        int week = WeekUtils.currentWeek(context);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_week);
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetCommon.widgetBackground(night));
        views.setTextColor(R.id.title, WidgetCommon.textPrimary(context));
        views.setTextViewText(R.id.title, week > 0
                ? "第 " + week + " 周"
                : "未设置开学日期，显示全部课程");

        int maxSection = 1;
        for (Course course : ScheduleStore.courses(context)) {
            if (course.end > maxSection) {
                maxSection = course.end;
            }
        }
        maxSection = Math.min(maxSection, MAX_SECTION);

        List<Course> courses = ScheduleStore.forWeek(context, week);
        int today = WeekUtils.todayWeekday();

        views.removeAllViews(R.id.grid);

        views.addView(R.id.grid, timeCell(context, ""));
        for (int day = 1; day <= 7; day++) {
            views.addView(R.id.grid,
                    headerCell(context, WeekUtils.weekdayName(day), day == today));
        }

        for (int section = 1; section <= maxSection; section++) {
            views.addView(R.id.grid, timeCell(context, String.valueOf(section)));
            for (int day = 1; day <= 7; day++) {
                views.addView(R.id.grid, cell(context, courses, day, section, night, today));
            }
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetCommon.openApp(context));
        return views;
    }

    private static RemoteViews headerCell(Context context, String label, boolean highlight) {
        RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.widget_week_header);
        cell.setTextViewText(R.id.header, label);
        if (highlight) {
            cell.setTextColor(R.id.header, WidgetCommon.accentText(context));
            cell.setInt(R.id.header, "setBackgroundColor", WidgetCommon.accentSoft(context));
        } else {
            cell.setTextColor(R.id.header, WidgetCommon.textSecondary(context));
        }
        return cell;
    }

    private static RemoteViews timeCell(Context context, String label) {
        RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.widget_week_time);
        cell.setTextViewText(R.id.section, label);
        cell.setTextColor(R.id.section, WidgetCommon.textSecondary(context));
        return cell;
    }

    private static RemoteViews cell(Context context, List<Course> courses,
                                    int day, int section, boolean night, int today) {
        RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.widget_week_cell);
        Course primary = primaryAt(courses, day, section);
        if (primary == null) {
            cell.setTextViewText(R.id.box, "");
            // 空着的格子也给今天铺一层极淡的底色，整列就自动连成一条。
            cell.setInt(R.id.box, "setBackgroundColor",
                    day == today ? WidgetCommon.accentSoft(context) : Color.TRANSPARENT);
            return cell;
        }
        cell.setInt(R.id.box, "setBackgroundColor", WidgetCommon.blockFill(primary.name, night));
        cell.setTextColor(R.id.box, WidgetCommon.blockText(primary.name, night));
        // 一门课占多节时只在第一节写课名，视觉上就连成一块了。
        cell.setTextViewText(R.id.box, primary.start == section ? primary.name : "");
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
}

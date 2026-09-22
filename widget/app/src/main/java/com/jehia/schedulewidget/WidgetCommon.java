package com.jehia.schedulewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.widget.RemoteViews;

/** 两个小组件共用的小工具：深浅色判断、配色、点击跳转、批量刷新、排闹钟。 */
final class WidgetCommon {

    private WidgetCommon() {
    }

    /** 主题：light / dark / system，由 App 里切换，默认浅色。 */
    static boolean isNight(Context context) {
        String theme = Prefs.getTheme(context);
        if (Prefs.THEME_DARK.equals(theme)) {
            return true;
        }
        if (Prefs.THEME_LIGHT.equals(theme)) {
            return false;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 课块底色：手工色板，同一门课永远同色。 */
    static int blockFill(Course course) {
        return Palette.fillOf(course);
    }

    /** 课块文字色：和底色配成一对，对比度都 >= 4.5:1。 */
    static int blockText(Course course) {
        return Palette.inkOf(course);
    }


    /**
     * 把一个课块画进格子：圆角由白色形状资源给，颜色现染上去。
     * tint 路支持任意颜色（用户自定义色也算）；少数 ROM 不支持就退回按色卡预生成的资源。
     */
    static void applyBlock(RemoteViews cell, Course course, int position) {
        int fill = blockFill(course);
        if (Palette.tintSupported()) {
            cell.setInt(R.id.box_bg, "setImageResource", Palette.blockShape(position));
            cell.setInt(R.id.box_bg, "setColorFilter", fill);
            cell.setInt(R.id.box_bg, "setBackgroundColor", Color.TRANSPARENT);
            cell.setTextColor(R.id.box, blockText(course));
        } else {
            // 兜底路只能挑最接近的出厂色，底色和文字色都得跟着「吸附后」的颜色走，
            // 否则浅色卡会被压成深字压深块，整块读不出来。
            int snapped = Palette.snappedFill(fill);
            int baked = Palette.bakedDrawable(fill, position);
            // 中格是方的，没有对应资源，清掉图片直接用背景色铺
            cell.setInt(R.id.box_bg, "setImageResource", baked);
            cell.setInt(R.id.box_bg, "setBackgroundColor",
                    baked == 0 ? snapped : Color.TRANSPARENT);
            // SRC_ATOP + 全透明 = 不着色，等于把上一次可能留下的滤镜清掉
            cell.setInt(R.id.box_bg, "setColorFilter", Color.TRANSPARENT);
            cell.setTextColor(R.id.box, Palette.bakedInk(fill));
        }
    }

    /** 「今日课程」左侧那条 4dp 竖条：圆角走形状资源，颜色现染，用的是课程自己的颜色。 */
    static void applyBar(RemoteViews views, int color) {
        if (Palette.tintSupported()) {
            views.setInt(R.id.status_bar, "setImageResource", R.drawable.bar_shape);
            views.setInt(R.id.status_bar, "setBackgroundColor", Color.TRANSPARENT);
            views.setInt(R.id.status_bar, "setColorFilter", color);
        } else {
            // 染不了色就退回方角纯色条：颜色对，圆角丢
            views.setInt(R.id.status_bar, "setImageResource", 0);
            views.setInt(R.id.status_bar, "setBackgroundColor", color);
        }
    }

    static int roundDrawable(int colorIndex) {
        return Palette.roundDrawable(colorIndex);
    }

    static int textPrimary(Context context) {
        return context.getColor(isNight(context)
                ? R.color.text_primary_dark
                : R.color.text_primary_light);
    }

    static int textSecondary(Context context) {
        return context.getColor(isNight(context)
                ? R.color.text_secondary_dark
                : R.color.text_secondary_light);
    }

    /** 全组件唯一的强调色。 */
    static int accent(Context context) {
        return context.getColor(R.color.accent);
    }

    /** 强调色的淡底，用来铺「今天」这一列。 */
    static int accentSoft(Context context) {
        return context.getColor(R.color.accent_soft);
    }


    static int divider(Context context) {
        return context.getColor(isNight(context)
                ? R.color.divider_dark
                : R.color.divider_light);
    }


    /** 药丸底色用的资源（纯强调色，不随课程变）。 */
    static int widgetBackground(boolean night) {
        return night ? R.drawable.widget_bg_dark : R.drawable.widget_bg;
    }

    /** 点小组件就打开应用主界面。 */
    static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 有没有任何一个小组件还放在桌面上。 */
    static boolean hasWidget(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return hasInstances(manager, context, TodayWidgetProvider.class)
                || hasInstances(manager, context, WeekWidgetProvider.class);
    }

    private static boolean hasInstances(AppWidgetManager manager, Context context,
                                        Class<? extends AppWidgetProvider> type) {
        return manager.getAppWidgetIds(new ComponentName(context, type)).length > 0;
    }

    /** 数据或设置变了，把两个小组件都刷一遍，顺手重排下一次闹钟。 */
    static void updateAll(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        TodayWidgetProvider.update(app, manager,
                manager.getAppWidgetIds(new ComponentName(app, TodayWidgetProvider.class)));
        WeekWidgetProvider.update(app, manager,
                manager.getAppWidgetIds(new ComponentName(app, WeekWidgetProvider.class)));
        WidgetAlarm.schedule(app);
    }
}
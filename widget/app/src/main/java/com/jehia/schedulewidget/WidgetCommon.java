package com.jehia.schedulewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;

/** 两个小组件共用的小工具：深浅色判断、配色、点击跳转、批量刷新。 */
final class WidgetCommon {

    /**
     * 桌面壁纸是固定的，组件如果跟着系统深浅色变，深夜就会变成一块黑卡压在亮壁纸上。
     * 所以默认固定用与壁纸匹配的浅色外观。以后换成深色壁纸，把这里改成 true 即可跟随系统。
     */
    private static final boolean FOLLOW_SYSTEM_DARK = false;

    private WidgetCommon() {
    }

    static boolean isNight(Context context) {
        if (!FOLLOW_SYSTEM_DARK) {
            return false;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 同一门课永远得到同一个色相，方便一眼认出。 */
    private static float hueOf(String name) {
        return Math.abs(name.hashCode()) % 360;
    }

    /** 课表方块的底色：只留一点点颜色当「识别标记」，避免在花壁纸上变成调色盘。 */
    static int blockFill(String name, boolean night) {
        float hue = hueOf(name);
        return night
                ? Color.HSVToColor(new float[]{hue, 0.26f, 0.40f})
                : Color.HSVToColor(new float[]{hue, 0.12f, 1.0f});
    }

    /** 课表方块上的文字颜色。 */
    static int blockText(String name, boolean night) {
        float hue = hueOf(name);
        return night
                ? Color.HSVToColor(new float[]{hue, 0.22f, 0.94f})
                : Color.HSVToColor(new float[]{hue, 0.62f, 0.34f});
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

    /** 强调色的淡底，用来标出「今天」这一列。 */
    static int accentSoft(Context context) {
        return context.getColor(R.color.accent_soft);
    }

    /** 强调色当文字用时压深一档，小字才够对比度。 */
    static int accentText(Context context) {
        return context.getColor(R.color.accent_text);
    }

    static int divider(Context context) {
        return context.getColor(isNight(context)
                ? R.color.divider_dark
                : R.color.divider_light);
    }

    static int progressTrack(Context context) {
        return context.getColor(isNight(context)
                ? R.color.progress_track_dark
                : R.color.progress_track_light);
    }

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

    /** 数据或设置变了，把两个小组件都刷一遍。 */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        TodayWidgetProvider.update(context, manager,
                manager.getAppWidgetIds(new ComponentName(context, TodayWidgetProvider.class)));
        WeekWidgetProvider.update(context, manager,
                manager.getAppWidgetIds(new ComponentName(context, WeekWidgetProvider.class)));
    }
}

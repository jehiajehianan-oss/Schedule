package com.jehia.schedulewidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

/**
 * 偏好存储：只放「跟这台手机有关、不该被课表数据覆盖」的两项。
 *
 * 开学日期和主题都是设备侧的设置；学期总周数、期末周起始周、作息时间属于课表数据，
 * 存在 schedule.json 里（见 ScheduleStore）。
 */
public final class Prefs {

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    private static final String FILE = "schedule_widget";
    private static final String KEY_SEMESTER_START = "semester_start";
    private static final String KEY_THEME = "theme";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 返回 yyyy-MM-dd；没设置过时返回空串。 */
    public static String getSemesterStart(Context context) {
        return sp(context).getString(KEY_SEMESTER_START, "");
    }

    public static void setSemesterStart(Context context, String isoDate) {
        sp(context).edit().putString(KEY_SEMESTER_START, isoDate).apply();
    }

    /** 开学日期所在周的周一；没设置或格式不对时返回 null。 */
    public static LocalDate semesterMonday(Context context) {
        String raw = getSemesterStart(context);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return WeekUtils.mondayOf(LocalDate.parse(raw));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** light / dark / system，默认浅色（壁纸是亮调，深色卡压上去会发黑）。 */
    public static String getTheme(Context context) {
        String value = sp(context).getString(KEY_THEME, THEME_LIGHT);
        if (THEME_DARK.equals(value) || THEME_SYSTEM.equals(value)) {
            return value;
        }
        return THEME_LIGHT;
    }

    public static void setTheme(Context context, String theme) {
        sp(context).edit().putString(KEY_THEME, theme).apply();
    }
}
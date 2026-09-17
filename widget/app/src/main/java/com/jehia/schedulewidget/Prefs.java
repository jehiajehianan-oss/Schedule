package com.jehia.schedulewidget;

import android.content.Context;
import android.content.SharedPreferences;

/** 偏好存储，目前只放开学日期。 */
public final class Prefs {

    private static final String FILE = "schedule_widget";
    private static final String KEY_SEMESTER_START = "semester_start";

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
}

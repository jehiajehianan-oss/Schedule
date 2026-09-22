package com.jehia.schedulewidget;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App 界面里反复用到的几块小东西：dp 换算、取主题色、拼文本行。 */
final class Ui {

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int textPrimary(Context context) {
        return themeColor(context, android.R.attr.textColorPrimary, 0xFF111827);
    }

    static int textSecondary(Context context) {
        return themeColor(context, android.R.attr.textColorSecondary, 0xFF3F4A5A);
    }

    static int accent(Context context) {
        return context.getColor(R.color.accent);
    }

    private static int themeColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                return context.getResources().getColor(value.resourceId, context.getTheme());
            }
        }
        return fallback;
    }

    /** 给可点的行铺一层系统水波纹。 */
    static void clickable(Context context, View view) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId);
        }
    }

    static TextView text(Context context, CharSequence content, float sizeSp) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(sizeSp);
        view.setTextColor(textPrimary(context));
        return view;
    }

    static TextView secondary(Context context, CharSequence content, float sizeSp) {
        TextView view = text(context, content, sizeSp);
        view.setTextColor(textSecondary(context));
        return view;
    }

    /** 一周课程列表里的分组标题，例如「— 周三 —」。 */
    static TextView section(Context context, CharSequence content) {
        TextView view = text(context, content, 13f);
        view.setTextColor(accent(context));
        view.setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 2));
        return view;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams weight(int weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }
}
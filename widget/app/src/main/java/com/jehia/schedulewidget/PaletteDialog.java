package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 色卡面板：列出四张出厂色卡 + 用户自己那张，点一下换、点「编辑自定义」逐槽改颜色。
 *
 * 自定义色卡以「当前色卡」为起点复制出来，槽位颜色用取色器改；文字色不手填，
 * 一律由 Palette.updateCustom() 按对比度自动配（白字或黑字），保证 &gt;= 4.5:1。
 */
final class PaletteDialog {

    private PaletteDialog() {
    }

    static void show(Activity activity, Runnable onChanged) {
        LinearLayout list = Ui.column(activity);
        list.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (Palette.Preset preset : Palette.presets()) {
                list.addView(presetRow(activity, preset, rebuild[0], onChanged));
            }
            TextView hint = Ui.secondary(activity,
                    "自定义色卡以当前色卡为起点；文字色按对比度自动配，改到多浅都不会看不清。", 12f);
            hint.setPadding(0, Ui.dp(activity, 10), 0, 0);
            list.addView(hint);
        };
        rebuild[0].run();

        holder[0] = new AlertDialog.Builder(activity)
                .setTitle("色卡")
                .setView(scroll)
                .setPositiveButton("编辑自定义…", (dialog, which) -> editCustom(activity, onChanged))
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
    }

    private static View presetRow(Activity activity, Palette.Preset preset,
                                  Runnable rebuild, Runnable onChanged) {
        LinearLayout box = Ui.column(activity);
        box.setPadding(0, Ui.dp(activity, 9), 0, Ui.dp(activity, 9));
        Ui.clickable(activity, box);

        boolean selected = preset.id.equals(Palette.currentId());
        TextView title = Ui.text(activity, (selected ? "●  " : "○  ") + preset.name, 14f);
        title.setPadding(0, 0, 0, Ui.dp(activity, 6));
        box.addView(title);
        box.addView(strip(activity, preset));

        box.setOnClickListener(view -> {
            if (preset.id.equals(Palette.currentId())) {
                return;
            }
            ScheduleStore.selectPalette(activity, preset.id);
            rebuild.run();
            onChanged.run();
        });
        return box;
    }

    /** 一张色卡的预览条：10 个槽位并排。 */
    private static View strip(Activity activity, Palette.Preset preset) {
        LinearLayout strip = Ui.row(activity);
        for (int index = 0; index < Palette.SLOTS; index++) {
            View swatch = new View(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, Ui.dp(activity, 20), 1f);
            if (index > 0) {
                params.setMarginStart(Ui.dp(activity, 2));
            }
            swatch.setLayoutParams(params);
            swatch.setBackgroundColor(Palette.parseColor(preset.fillAt(index), Color.GRAY));
            strip.addView(swatch);
        }
        return strip;
    }

    // ---------------------------------------------------------------- 自定义色卡

    private static void editCustom(Activity activity, Runnable onChanged) {
        Palette.ensureCustom();
        List<Integer> fills = new ArrayList<>();
        for (int index = 0; index < Palette.SLOTS; index++) {
            fills.add(Palette.parseColor(Palette.custom().fillAt(index), Color.GRAY));
        }
        showSlots(activity, fills, onChanged);
    }

    private static void showSlots(Activity activity, List<Integer> fills, Runnable onChanged) {
        LinearLayout list = Ui.column(activity);
        list.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (int index = 0; index < Palette.SLOTS; index++) {
                list.addView(slotRow(activity, index, fills, rebuild[0]));
            }
            TextView hint = Ui.secondary(activity,
                    "点色块改颜色。10 个槽位对应「课名 hash」的下标，同一门课永远落在同一个槽里。", 12f);
            hint.setPadding(0, Ui.dp(activity, 10), 0, 0);
            list.addView(hint);
        };
        rebuild[0].run();

        new AlertDialog.Builder(activity)
                .setTitle("自定义色卡")
                .setView(scroll)
                .setPositiveButton("保存", (dialog, which) -> {
                    ScheduleStore.saveCustomPalette(activity, fills);
                    onChanged.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static View slotRow(Activity activity, int index, List<Integer> fills,
                                Runnable rebuild) {
        int color = fills.get(index);
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 7), 0, Ui.dp(activity, 7));
        Ui.clickable(activity, row);

        TextView number = Ui.text(activity, "槽 " + (index + 1), 14f);
        number.setLayoutParams(Ui.weight(1));
        row.addView(number);

        TextView value = Ui.secondary(activity, Palette.hexOf(color), 12f);
        value.setPadding(0, 0, Ui.dp(activity, 10), 0);
        row.addView(value);

        TextView swatch = Ui.text(activity, "Aa", 13f);
        swatch.setGravity(android.view.Gravity.CENTER);
        swatch.setTextColor(Palette.autoInk(color));
        swatch.setBackgroundColor(color);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 54),
                Ui.dp(activity, 34)));
        row.addView(swatch);

        row.setOnClickListener(view -> ColorPickerDialog.show(activity, fills.get(index), picked -> {
            fills.set(index, picked);
            rebuild.run();
        }));
        return row;
    }
}
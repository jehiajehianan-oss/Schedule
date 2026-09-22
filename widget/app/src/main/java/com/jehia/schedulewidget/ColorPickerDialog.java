package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 取色器（纯框架控件，不引第三方库）：三条 RGB 滑杆 + 十六进制输入 + 实时预览。
 *
 * 预览条上的十六进制文本会用 Palette.autoInk() 自动配字色，正好顺带演示「这个颜色配什么字」。
 */
final class ColorPickerDialog {

    interface OnPicked {
        void onPicked(int color);
    }

    private static final String[] NAMES = {"红 R", "绿 G", "蓝 B"};

    private ColorPickerDialog() {
    }

    static void show(Activity activity, int initial, OnPicked callback) {
        LinearLayout box = Ui.column(activity);
        box.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 10), Ui.dp(activity, 20), 0);

        View preview = new View(activity);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 6)));
        box.addView(preview);

        TextView label = Ui.text(activity, "", 13f);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 36)));
        label.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) label.getLayoutParams();
        labelParams.topMargin = Ui.dp(activity, 6);
        box.addView(label);

        EditText hex = new EditText(activity);
        hex.setInputType(InputType.TYPE_CLASS_TEXT);
        hex.setSingleLine(true);
        hex.setHint("RRGGBB");
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexParams.topMargin = Ui.dp(activity, 6);
        hex.setLayoutParams(hexParams);
        box.addView(hex);

        int[] channels = {Color.red(initial), Color.green(initial), Color.blue(initial)};
        SeekBar[] bars = new SeekBar[3];
        TextView[] labels = new TextView[3];
        boolean[] syncing = {false};

        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout row = Ui.row(activity);
            labels[i] = Ui.secondary(activity, NAMES[i] + " " + channels[i], 12f);
            labels[i].setWidth(Ui.dp(activity, 74));
            row.addView(labels[i]);

            SeekBar bar = new SeekBar(activity);
            bar.setMax(255);
            bar.setProgress(channels[i]);
            bar.setLayoutParams(Ui.weight(1));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    channels[index] = value;
                    paint(preview, label, hex, bars, labels, channels, syncing, true);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            bars[i] = bar;
            row.addView(bar);
            box.addView(row);
        }

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (syncing[0]) {
                    return;
                }
                String raw = editable.toString().trim();
                if (raw.startsWith("#")) {
                    raw = raw.substring(1);
                }
                if (raw.length() != 6) {
                    return;
                }
                int parsed = Palette.parseColor("#" + raw, 0);
                if (parsed == 0) {
                    return;
                }
                channels[0] = Color.red(parsed);
                channels[1] = Color.green(parsed);
                channels[2] = Color.blue(parsed);
                paint(preview, label, hex, bars, labels, channels, syncing, false);
            }
        });

        paint(preview, label, hex, bars, labels, channels, syncing, true);

        new AlertDialog.Builder(activity)
                .setTitle("选颜色")
                .setView(box)
                .setPositiveButton("确定", (dialog, which) ->
                        callback.onPicked(Color.rgb(channels[0], channels[1], channels[2])))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把当前 channels 刷到界面上；writeHex 为 false 时不动输入框（避免和输入框互相触发）。 */
    private static void paint(View preview, TextView label, EditText hex, SeekBar[] bars,
                              TextView[] labels, int[] channels, boolean[] syncing,
                              boolean writeHex) {
        int color = Color.rgb(channels[0], channels[1], channels[2]);
        preview.setBackgroundColor(color);
        label.setBackgroundColor(color);
        label.setTextColor(Palette.autoInk(color));
        label.setText(Palette.hexOf(color));

        for (int i = 0; i < 3; i++) {
            labels[i].setText(NAMES[i] + " " + channels[i]);
            if (bars[i] != null && bars[i].getProgress() != channels[i]) {
                bars[i].setProgress(channels[i]);
            }
        }

        if (!writeHex) {
            return;
        }
        String text = Palette.hexOf(color).substring(1);
        if (!text.equalsIgnoreCase(hex.getText().toString().trim())) {
            syncing[0] = true;
            hex.setText(text);
            hex.setSelection(hex.getText().length());
            syncing[0] = false;
        }
    }
}
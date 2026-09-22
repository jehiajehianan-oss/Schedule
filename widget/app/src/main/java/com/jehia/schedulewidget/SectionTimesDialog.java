package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作息时间编辑器：12 节的开始 / 结束时间都能在这里改，不用再去动 Python 里的 SECTION_TIMES。
 * 改完写进 schedule.json，导出脚本下次重跑也不会把它覆盖掉（App 内改的是用户那份数据）。
 */
final class SectionTimesDialog {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int SECTIONS = 12;

    private SectionTimesDialog() {
    }

    static void show(Activity activity, Runnable onSaved) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_section_times, null);
        LinearLayout list = content.findViewById(R.id.time_list);

        Map<Integer, String> current = ScheduleStore.sectionTimes(activity);
        final LocalTime[][] times = new LocalTime[SECTIONS + 1][2];
        for (int section = 1; section <= SECTIONS; section++) {
            String range = current.get(section);
            LocalTime begin = LocalTime.of(8, 0);
            LocalTime end = LocalTime.of(8, 45);
            if (range != null) {
                int index = range.indexOf('-');
                if (index > 0) {
                    begin = parse(range.substring(0, index), begin);
                    end = parse(range.substring(index + 1), end);
                }
            }
            times[section][0] = begin;
            times[section][1] = end;
        }

        for (int section = 1; section <= SECTIONS; section++) {
            final int index = section;
            LinearLayout row = Ui.row(activity);
            row.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));

            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            View label = Ui.text(activity, "第 " + section + " 节", 14f);
            label.setLayoutParams(labelParams);
            row.addView(label);

            Button begin = new Button(activity);
            begin.setText(clock(times[section][0]));
            begin.setOnClickListener(view -> new TimePickerDialog(activity,
                    (picker, hour, minute) -> {
                        times[index][0] = LocalTime.of(hour, minute);
                        begin.setText(clock(times[index][0]));
                    }, times[index][0].getHour(), times[index][0].getMinute(), true).show());
            row.addView(begin);

            Button end = new Button(activity);
            end.setText(clock(times[section][1]));
            end.setOnClickListener(view -> new TimePickerDialog(activity,
                    (picker, hour, minute) -> {
                        times[index][1] = LocalTime.of(hour, minute);
                        end.setText(clock(times[index][1]));
                    }, times[index][1].getHour(), times[index][1].getMinute(), true).show());
            row.addView(end);

            list.addView(row);
        }

        new AlertDialog.Builder(activity)
                .setTitle("作息时间")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> {
                    Map<Integer, String> updated = new LinkedHashMap<>();
                    for (int section = 1; section <= SECTIONS; section++) {
                        updated.put(section, clock(times[section][0]) + "-" + clock(times[section][1]));
                    }
                    try {
                        ScheduleStore.setSectionTimes(activity, updated);
                    } catch (RuntimeException e) {
                        Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    onSaved.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String clock(LocalTime time) {
        return time.format(CLOCK);
    }

    private static LocalTime parse(String text, LocalTime fallback) {
        try {
            return LocalTime.parse(text.trim(), CLOCK);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 考试编辑器：科目、日期、起止时间、地点。期末周里两个小组件都读它。 */
final class ExamEditorDialog {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private ExamEditorDialog() {
    }

    static void show(Activity activity, Exam existing, Runnable onSaved) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_exam, null);
        EditText editName = content.findViewById(R.id.edit_name);
        EditText editRoom = content.findViewById(R.id.edit_room);
        Button btnDate = content.findViewById(R.id.btn_date);
        Button btnBegin = content.findViewById(R.id.btn_begin);
        Button btnEnd = content.findViewById(R.id.btn_end);

        LocalDate today = LocalDate.now();
        final LocalDate[] date = {existing == null ? today : parseDate(existing.date, today)};
        final LocalTime[] begin = {existing == null ? LocalTime.of(9, 0)
                : parseTime(existing.begin, LocalTime.of(9, 0))};
        final LocalTime[] end = {existing == null ? LocalTime.of(11, 0)
                : parseTime(existing.end, LocalTime.of(11, 0))};

        btnDate.setText(date[0].toString());
        btnBegin.setText(clock(begin[0]));
        btnEnd.setText(clock(end[0]));

        btnDate.setOnClickListener(view -> new DatePickerDialog(activity,
                (picker, year, month, dayOfMonth) -> {
                    date[0] = LocalDate.of(year, month + 1, dayOfMonth);
                    btnDate.setText(date[0].toString());
                }, date[0].getYear(), date[0].getMonthValue() - 1, date[0].getDayOfMonth())
                .show());

        btnBegin.setOnClickListener(view -> new TimePickerDialog(activity,
                (picker, hour, minute) -> {
                    begin[0] = LocalTime.of(hour, minute);
                    btnBegin.setText(clock(begin[0]));
                }, begin[0].getHour(), begin[0].getMinute(), true).show());

        btnEnd.setOnClickListener(view -> new TimePickerDialog(activity,
                (picker, hour, minute) -> {
                    end[0] = LocalTime.of(hour, minute);
                    btnEnd.setText(clock(end[0]));
                }, end[0].getHour(), end[0].getMinute(), true).show());

        if (existing != null) {
            editName.setText(existing.name);
            editRoom.setText(existing.room);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "添加考试" : "编辑考试")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(activity, "科目不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!end[0].isAfter(begin[0])) {
                Toast.makeText(activity, "结束时间要晚于开始时间", Toast.LENGTH_SHORT).show();
                return;
            }
            String id = existing == null ? "e" + System.currentTimeMillis() : existing.id;
            Exam exam = new Exam(id, name, date[0].toString(), clock(begin[0]),
                    clock(end[0]), editRoom.getText().toString().trim());
            try {
                ScheduleStore.saveExam(activity, exam);
            } catch (RuntimeException e) {
                Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            onSaved.run();
        });
    }

    private static String clock(LocalTime time) {
        return String.format(Locale.US, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static LocalTime parseTime(String text, LocalTime fallback) {
        try {
            return LocalTime.parse(text, CLOCK);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
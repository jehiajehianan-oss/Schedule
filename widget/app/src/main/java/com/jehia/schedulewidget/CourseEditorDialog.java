package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 课程编辑器（弹窗，不引第三方库）。
 *
 * 周次用 1..学期总周数 的小方块多选，配「全选 / 清空 / 单周 / 双周 / 连续段」几个快捷键；
 * 保存时 weeks 文本由 weekList 反推（Weeks.format），语法和 coursetable/schedule.py 的
 * expand_weeks 一致，所以导出的数据仍然对得上。
 */
final class CourseEditorDialog {

    private static final int MAX_SECTION = 12;

    private CourseEditorDialog() {
    }

    static void show(Activity activity, Course existing, Runnable onSaved) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_course, null);
        EditText editName = content.findViewById(R.id.edit_name);
        EditText editRoom = content.findViewById(R.id.edit_room);
        EditText editTeacher = content.findViewById(R.id.edit_teacher);
        Spinner spinWeekday = content.findViewById(R.id.spin_weekday);
        Spinner spinStart = content.findViewById(R.id.spin_start);
        Spinner spinEnd = content.findViewById(R.id.spin_end);
        LinearLayout quick = content.findViewById(R.id.week_quick);
        GridLayout grid = content.findViewById(R.id.week_grid);
        LinearLayout colorRow = content.findViewById(R.id.color_row);
        TextView colorHint = content.findViewById(R.id.color_hint);

        int totalWeeks = Math.max(1, Math.min(30, ScheduleStore.semesterWeeks(activity)));

        spinWeekday.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, dayNames()));
        spinStart.setAdapter(sectionAdapter(activity));
        spinEnd.setAdapter(sectionAdapter(activity));

        // 周次小方块
        final boolean[] picked = new boolean[totalWeeks + 1];
        final TextView[] chips = new TextView[totalWeeks + 1];
        grid.setColumnCount(Math.min(10, totalWeeks));
        for (int week = 1; week <= totalWeeks; week++) {
            final int index = week;
            TextView chip = Ui.text(activity, String.valueOf(week), 13f);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(0, Ui.dp(activity, 5), 0, Ui.dp(activity, 5));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 2),
                    Ui.dp(activity, 2), Ui.dp(activity, 2));
            chip.setLayoutParams(params);
            chip.setOnClickListener(view -> {
                picked[index] = !picked[index];
                paintChip(activity, chip, picked[index]);
            });
            chips[week] = chip;
            grid.addView(chip);
        }

        List<Integer> initialWeeks = existing == null
                ? allWeeks(totalWeeks) : new ArrayList<>(existing.weekList);
        for (int week : initialWeeks) {
            if (week >= 1 && week <= totalWeeks) {
                picked[week] = true;
            }
        }
        for (int week = 1; week <= totalWeeks; week++) {
            paintChip(activity, chips[week], picked[week]);
        }

        addQuickButton(activity, quick, "全选", () -> markAll(activity, chips, picked, true));
        addQuickButton(activity, quick, "清空", () -> markAll(activity, chips, picked, false));
        addQuickButton(activity, quick, "单周", () -> markParity(activity, chips, picked, 1));
        addQuickButton(activity, quick, "双周", () -> markParity(activity, chips, picked, 0));
        addQuickButton(activity, quick, "连续段", () ->
                askRange(activity, totalWeeks, chips, picked));

        // 颜色：第 1 格「自动」，中间 10 格是当前色卡，最后 1 格「自定义颜色」
        final int[] chosen = {existing == null ? -1
                : (existing.colorHex != null && !existing.colorHex.isEmpty() ? -2
                : (existing.color >= 0 ? existing.color : -1))};
        final int[] custom = {existing != null && existing.colorHex != null
                && !existing.colorHex.isEmpty()
                ? Palette.parseColor(existing.colorHex, Palette.fillAt(0))
                : Palette.fillAt(0)};
        final LinearLayout swatches = Ui.row(activity);
        for (int index = 0; index <= Palette.SLOTS + 1; index++) {
            swatches.addView(swatch(activity, index));
        }
        colorRow.addView(swatches);
        final Runnable[] repaint = new Runnable[1];
        repaint[0] = () -> {
            for (int index = 0; index <= Palette.SLOTS + 1; index++) {
                View view = swatches.getChildAt(index);
                view.setAlpha(chosen[0] == slotValue(index) ? 1f : 0.4f);
            }
            View tail = swatches.getChildAt(Palette.SLOTS + 1);
            tail.setBackgroundResource(R.drawable.block_shape_single);
            tail.getBackground().setTint(custom[0]);
            if (chosen[0] == -1) {
                colorHint.setText("自动：按课名定，同一门课永远同色");
            } else if (chosen[0] == -2) {
                colorHint.setText("自定义 " + Palette.hexOf(custom[0])
                        + "　文字自动配 " + Palette.hexOf(Palette.autoInk(custom[0])));
            } else {
                colorHint.setText("固定用色卡槽 " + (chosen[0] + 1)
                        + "（" + Palette.hexOf(Palette.fillAt(chosen[0])) + "）");
            }
        };
        for (int index = 0; index <= Palette.SLOTS + 1; index++) {
            final int position = index;
            swatches.getChildAt(index).setOnClickListener(view -> {
                if (position == 0) {
                    chosen[0] = -1;
                    repaint[0].run();
                } else if (position == Palette.SLOTS + 1) {
                    int initial = chosen[0] == -2 ? custom[0]
                            : (chosen[0] >= 0 ? Palette.fillAt(chosen[0])
                            : Palette.fillAt(Palette.indexOf(
                                    editName.getText().toString().trim())));
                    ColorPickerDialog.show(activity, initial, color -> {
                        custom[0] = color;
                        chosen[0] = -2;
                        repaint[0].run();
                    });
                } else {
                    chosen[0] = position - 1;
                    repaint[0].run();
                }
            });
        }
        repaint[0].run();

        if (existing != null) {
            editName.setText(existing.name);
            editRoom.setText(existing.room);
            editTeacher.setText(existing.teacher);
            spinWeekday.setSelection(Math.max(0, Math.min(6, existing.weekday - 1)));
            spinStart.setSelection(Math.max(0, Math.min(MAX_SECTION - 1, existing.start - 1)));
            spinEnd.setSelection(Math.max(0, Math.min(MAX_SECTION - 1, existing.end - 1)));
        } else {
            spinStart.setSelection(0);
            spinEnd.setSelection(0);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "添加课程" : "编辑课程")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(activity, "课名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            List<Integer> weeks = new ArrayList<>();
            for (int week = 1; week <= totalWeeks; week++) {
                if (picked[week]) {
                    weeks.add(week);
                }
            }
            if (weeks.isEmpty()) {
                Toast.makeText(activity, "至少要选一周", Toast.LENGTH_SHORT).show();
                return;
            }
            int start = spinStart.getSelectedItemPosition() + 1;
            int end = spinEnd.getSelectedItemPosition() + 1;
            if (start > end) {
                int swap = start;
                start = end;
                end = swap;
            }
            int weekday = spinWeekday.getSelectedItemPosition() + 1;
            String room = editRoom.getText().toString().trim();
            String teacher = editTeacher.getText().toString().trim();

            String colorHex = chosen[0] == -2 ? Palette.hexOf(custom[0]) : "";
            int colorIndex = chosen[0] >= 0 ? chosen[0] : -1;
            Course course;
            if (existing == null) {
                course = Course.create(name, weekday, start, end, teacher, room, weeks,
                        colorIndex, colorHex);
            } else {
                course = new Course(existing.id, name, weekday, start, end, teacher, room,
                        Weeks.format(weeks), weeks, colorIndex, colorHex);
            }
            try {
                ScheduleStore.saveCourse(activity, course);
            } catch (RuntimeException e) {
                Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            onSaved.run();
        });
    }

    private static void paintChip(Activity activity, TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setBackgroundResource(on ? R.drawable.chip_on : R.drawable.chip_off);
        chip.setTextColor(on ? 0xFFFFFFFF : Ui.textPrimary(activity));
    }

    private static void markAll(Activity activity, TextView[] chips, boolean[] picked,
                                boolean value) {
        for (int week = 1; week < picked.length; week++) {
            picked[week] = value;
            paintChip(activity, chips[week], value);
        }
    }

    /** parity = 1 选单周，0 选双周。 */
    private static void markParity(Activity activity, TextView[] chips, boolean[] picked,
                                   int parity) {
        for (int week = 1; week < picked.length; week++) {
            boolean on = week % 2 == parity;
            picked[week] = on;
            paintChip(activity, chips[week], on);
        }
    }

    private static void askRange(Activity activity, int totalWeeks, TextView[] chips,
                                 boolean[] picked) {
        LinearLayout box = Ui.column(activity);
        box.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8),
                Ui.dp(activity, 20), 0);
        EditText from = numberField(activity, "起");
        EditText to = numberField(activity, "止");
        box.addView(from);
        box.addView(to);

        new AlertDialog.Builder(activity)
                .setTitle("填连续周次，例如 1 到 9")
                .setView(box)
                .setPositiveButton("确定", (dialog, which) -> {
                    int first = number(from, 1);
                    int last = number(to, totalWeeks);
                    if (first > last) {
                        int swap = first;
                        first = last;
                        last = swap;
                    }
                    for (int week = 1; week <= totalWeeks; week++) {
                        boolean on = week >= first && week <= last;
                        picked[week] = on;
                        paintChip(activity, chips[week], on);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static EditText numberField(Activity activity, String hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        return field;
    }

    private static int number(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void addQuickButton(Activity activity, LinearLayout bar, String label,
                                       Runnable action) {
        TextView button = Ui.text(activity, label, 12f);
        button.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 4),
                Ui.dp(activity, 10), Ui.dp(activity, 4));
        button.setBackgroundResource(R.drawable.chip_off);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(activity, 6));
        button.setLayoutParams(params);
        button.setOnClickListener(view -> action.run());
        bar.addView(button);
    }

    /** 0 = 「自动」，1..10 = 当前色卡的槽位，11 = 「自定义」。 */
    private static View swatch(Activity activity, int index) {
        View view = new View(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Ui.dp(activity, 30), Ui.dp(activity, 30));
        params.setMarginEnd(Ui.dp(activity, 6));
        view.setLayoutParams(params);
        if (index == 0) {
            view.setBackgroundResource(R.drawable.chip_off);
        } else if (index <= Palette.SLOTS) {
            view.setBackgroundResource(R.drawable.block_shape_single);
            view.getBackground().setTint(Palette.fillAt(index - 1));
        } else {
            view.setBackgroundResource(R.drawable.block_shape_single);
        }
        return view;
    }

    /** 色块下标 -> 它代表的取值（-1 自动 / 0..9 色卡槽 / -2 自定义）。 */
    private static int slotValue(int index) {
        if (index == 0) {
            return -1;
        }
        return index == Palette.SLOTS + 1 ? -2 : index - 1;
    }

    private static List<Integer> allWeeks(int totalWeeks) {
        List<Integer> weeks = new ArrayList<>();
        for (int week = 1; week <= totalWeeks; week++) {
            weeks.add(week);
        }
        return weeks;
    }

    private static List<String> dayNames() {
        List<String> names = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            names.add(WeekUtils.weekdayName(day));
        }
        return names;
    }

    private static ArrayAdapter<String> sectionAdapter(Activity activity) {
        List<String> labels = new ArrayList<>();
        for (int section = 1; section <= MAX_SECTION; section++) {
            labels.add("第" + section + "节");
        }
        return new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels);
    }
}
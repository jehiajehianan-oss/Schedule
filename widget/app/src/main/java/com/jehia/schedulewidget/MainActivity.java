package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * 主界面：看当前第几周、改设置、增删改课程与考试。
 *
 * 数据分两层：assets/schedule.json 是出厂数据，filesDir/schedule.json 是用户数据。
 * 第一次在 App 里改任何东西都会把出厂数据整份复制过去再改，顶部会写明现在用的是哪一层。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_IMPORT = 1;

    private TextView txtWeek;
    private TextView txtStart;
    private LinearLayout listContainer;
    private LinearLayout examContainer;
    private TextView btnTheme;
    private TextView btnFinals;
    private TextView btnPalette;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(themeResource());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtWeek = findViewById(R.id.txt_week);
        txtStart = findViewById(R.id.txt_start);
        listContainer = findViewById(R.id.list_container);
        examContainer = findViewById(R.id.exam_container);
        btnTheme = findViewById(R.id.btn_theme);
        btnFinals = findViewById(R.id.btn_finals_week);
        btnPalette = findViewById(R.id.btn_palette);

        // 数据文件可能刚被重新导出过，丢掉进程内缓存。
        ScheduleStore.invalidate();

        findViewById(R.id.btn_start_date).setOnClickListener(view -> pickStartDate());
        findViewById(R.id.btn_section_times).setOnClickListener(view ->
                SectionTimesDialog.show(this, this::afterChange));
        findViewById(R.id.btn_semester_weeks).setOnClickListener(view ->
                askNumber("学期总周数", "默认 20 周。周次小方块、「第 N 周」的上限都跟着它走。",
                        ScheduleStore.semesterWeeks(this), weeks -> {
                            ScheduleStore.setSemesterWeeks(this, weeks);
                            afterChange();
                        }));
        findViewById(R.id.btn_finals_week).setOnClickListener(view ->
                askNumber("期末周起始周", "填 0 = 不启用。填 17 表示第 17 周到学期末都是期末周，"
                                + "两个小组件会切成考试表。",
                        ScheduleStore.finalsStartWeek(this), week -> {
                            ScheduleStore.setFinalsStartWeek(this, week);
                            afterChange();
                        }));
        btnTheme.setOnClickListener(view -> cycleTheme());
        btnPalette.setOnClickListener(view -> PaletteDialog.show(this, this::afterChange));
        findViewById(R.id.btn_refresh).setOnClickListener(view -> {
            WidgetCommon.updateAll(this);
            Toast.makeText(this, "小组件已刷新", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_import).setOnClickListener(view -> pickJson());
        findViewById(R.id.btn_reset).setOnClickListener(view -> confirmReset());
        findViewById(R.id.btn_add_course).setOnClickListener(view ->
                CourseEditorDialog.show(this, null, this::afterChange));
        findViewById(R.id.btn_add_exam).setOnClickListener(view ->
                ExamEditorDialog.show(this, null, this::afterChange));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ---------------------------------------------------------------- 主题

    private int themeResource() {
        String theme = Prefs.getTheme(this);
        boolean dark = Prefs.THEME_DARK.equals(theme);
        if (Prefs.THEME_SYSTEM.equals(theme)) {
            dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }
        return dark ? R.style.AppTheme_Dark : R.style.AppTheme;
    }

    private String themeLabel() {
        String theme = Prefs.getTheme(this);
        if (Prefs.THEME_DARK.equals(theme)) {
            return "深色";
        }
        if (Prefs.THEME_SYSTEM.equals(theme)) {
            return "跟随系统";
        }
        return "浅色";
    }

    private void cycleTheme() {
        String next;
        switch (Prefs.getTheme(this)) {
            case Prefs.THEME_LIGHT:
                next = Prefs.THEME_DARK;
                break;
            case Prefs.THEME_DARK:
                next = Prefs.THEME_SYSTEM;
                break;
            default:
                next = Prefs.THEME_LIGHT;
                break;
        }
        Prefs.setTheme(this, next);
        WidgetCommon.updateAll(this);
        Toast.makeText(this, "主题已切换，小组件同步更新", Toast.LENGTH_SHORT).show();
        recreate();
    }

    // ---------------------------------------------------------------- 设置

    private void pickStartDate() {
        LocalDate initial = WeekUtils.firstMonday(this);
        if (initial == null) {
            initial = LocalDate.now();
        }
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Prefs.setSemesterStart(this,
                    WeekUtils.isoOf(LocalDate.of(year, month + 1, dayOfMonth)));
            afterChange();
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private void askNumber(String title, String message, int initial,
                           java.util.function.IntConsumer onSaved) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(initial));
        field.setSelection(field.getText().length());

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.secondary(this, message, 12f));
        box.addView(field);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    int value;
                    try {
                        value = Integer.parseInt(field.getText().toString().trim());
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onSaved.accept(value);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 数据

    private void pickJson() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) {
                throw new IllegalStateException("读不到所选文件");
            }
            ScheduleStore.importFrom(this, in);
            afterChange();
            Toast.makeText(this, "课表数据已更新", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复内置数据？")
                .setMessage("会清掉你在 App 里改的课程、考试、周次和作息时间，"
                        + "换回随 APK 一起装进来的那份课表。\n\n"
                        + "开学日期和主题是这台手机的设置，不受影响。\n"
                        + "这一步不能撤销。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    ScheduleStore.clearCustom(this);
                    afterChange();
                    Toast.makeText(this, "已恢复内置数据", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void afterChange() {
        WidgetCommon.updateAll(this);
        render();
    }

    // ---------------------------------------------------------------- 列表

    private void render() {
        String raw = Prefs.getSemesterStart(this);
        int week = WeekUtils.currentWeek(this);
        String source = ScheduleStore.isCustom(this) ? "自定义数据" : "内置数据";

        if (week == 0) {
            txtWeek.setText("还没设置开学日期");
        } else {
            String finals = WeekUtils.inFinals(this) ? " · 期末周" : "";
            txtWeek.setText("当前：第 " + week + " 周 · "
                    + WeekUtils.weekdayName(WeekUtils.todayWeekday()) + finals);
        }

        int finalsStart = ScheduleStore.finalsStartWeek(this);
        txtStart.setText("当前用：" + source
                + "　开学日期：" + (raw.isEmpty() ? "未设置" : raw)
                + "　学期 " + ScheduleStore.semesterWeeks(this) + " 周"
                + "　期末周：" + (finalsStart > 0 ? "第 " + finalsStart + " 周起" : "未启用")
                + "　主题：" + themeLabel());

        btnTheme.setText("主题：" + themeLabel());
        btnFinals.setText(finalsStart > 0 ? "期末周：第 " + finalsStart + " 周起" : "期末周：关闭");
        btnPalette.setText("色卡：" + Palette.current().name);

        renderCourses();
        renderExams();
    }

    private void renderCourses() {
        listContainer.removeAllViews();
        List<Course> courses = ScheduleStore.forWeek(this, 0);
        if (courses.isEmpty()) {
            listContainer.addView(Ui.secondary(this,
                    "还没读到课程数据。可以运行 scripts/export_widget_data.py 重新导出，"
                            + "或者用下面的「添加课程」手动加。", 13f));
            return;
        }
        int lastDay = 0;
        for (Course course : courses) {
            if (course.weekday != lastDay) {
                listContainer.addView(Ui.section(this,
                        "— " + WeekUtils.weekdayName(course.weekday) + " —"));
                lastDay = course.weekday;
            }
            listContainer.addView(courseRow(course));
        }
    }

    private View courseRow(Course course) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        Ui.clickable(this, row);

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 10), Ui.dp(this, 10));
        dotParams.setMarginEnd(Ui.dp(this, 10));
        dot.setLayoutParams(dotParams);
        dot.setBackgroundResource(Palette.roundDrawable(Palette.indexOf(course)));
        row.addView(dot);

        LinearLayout texts = Ui.column(this);
        texts.setLayoutParams(Ui.weight(1));
        texts.addView(Ui.text(this, course.start + "-" + course.end + "节　" + course.name, 14f));
        texts.addView(Ui.secondary(this, join(" · ",
                ScheduleStore.timeRange(this, course),
                course.room,
                course.teacher,
                course.weeks.isEmpty() ? "" : course.weeks + "周"), 12f));
        row.addView(texts);

        row.setOnClickListener(view -> CourseEditorDialog.show(this, course, this::afterChange));
        row.setOnLongClickListener(view -> {
            confirmDeleteCourse(course);
            return true;
        });
        return row;
    }

    private void confirmDeleteCourse(Course course) {
        new AlertDialog.Builder(this)
                .setTitle("删掉这门课？")
                .setMessage(course.name + "　" + WeekUtils.weekdayName(course.weekday) + " "
                        + course.start + "-" + course.end + "节　" + course.weeks + "周"
                        + "\n\n只影响这条排课，学期总周数、开学日期、考试都不动。")
                .setPositiveButton("删除", (dialog, which) -> {
                    ScheduleStore.deleteCourse(this, course.id);
                    afterChange();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renderExams() {
        examContainer.removeAllViews();
        List<Exam> exams = ScheduleStore.sortedExams(this);
        if (exams.isEmpty()) {
            examContainer.addView(Ui.secondary(this,
                    "还没有考试。期末周里「今日课程」会显示考试进度，「本周课表」会换成考试表。", 13f));
            return;
        }
        for (Exam exam : exams) {
            examContainer.addView(examRow(exam));
        }
    }

    private View examRow(Exam exam) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        Ui.clickable(this, row);

        LinearLayout texts = Ui.column(this);
        texts.setLayoutParams(Ui.weight(1));
        texts.addView(Ui.text(this, exam.date + "　" + exam.begin + "–" + exam.end, 14f));
        texts.addView(Ui.secondary(this, join(" · ", exam.name, exam.room), 12f));
        row.addView(texts);

        row.setOnClickListener(view -> ExamEditorDialog.show(this, exam, this::afterChange));
        row.setOnLongClickListener(view -> {
            confirmDeleteExam(exam);
            return true;
        });
        return row;
    }

    private void confirmDeleteExam(Exam exam) {
        new AlertDialog.Builder(this)
                .setTitle("删掉这场考试？")
                .setMessage(exam.name + "　" + exam.date + " " + exam.begin + "–" + exam.end
                        + "\n\n只影响这一场考试。")
                .setPositiveButton("删除", (dialog, which) -> {
                    ScheduleStore.deleteExam(this, exam.id);
                    afterChange();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String join(String separator, String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(separator);
            }
            text.append(part);
        }
        return text.toString();
    }
}
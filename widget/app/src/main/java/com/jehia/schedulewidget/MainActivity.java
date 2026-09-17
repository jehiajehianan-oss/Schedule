package com.jehia.schedulewidget;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 主界面：设置开学日期、看整学期课程、手动刷新小组件。 */
public class MainActivity extends Activity {

    private static final int REQUEST_IMPORT = 1;

    private TextView txtWeek;
    private TextView txtStart;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtWeek = findViewById(R.id.txt_week);
        txtStart = findViewById(R.id.txt_start);
        listContainer = findViewById(R.id.list_container);

        // 数据文件可能刚被重新导出过，丢掉进程内缓存。
        ScheduleStore.invalidate();

        findViewById(R.id.btn_start_date).setOnClickListener(view -> pickDate());
        findViewById(R.id.btn_refresh).setOnClickListener(view -> {
            WidgetCommon.updateAll(this);
            Toast.makeText(this, "小组件已刷新", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_import).setOnClickListener(view -> pickJson());
        findViewById(R.id.btn_reset).setOnClickListener(view -> {
            ScheduleStore.clearImported(this);
            WidgetCommon.updateAll(this);
            render();
            Toast.makeText(this, "已恢复内置数据", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void pickDate() {
        LocalDate initial = WeekUtils.firstMonday(this);
        if (initial == null) {
            initial = LocalDate.now();
        }
        new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    String iso = String.format(Locale.US, "%04d-%02d-%02d",
                            year, month + 1, dayOfMonth);
                    Prefs.setSemesterStart(this, iso);
                    WidgetCommon.updateAll(this);
                    render();
                },
                initial.getYear(),
                initial.getMonthValue() - 1,
                initial.getDayOfMonth()).show();
    }

    /** 选一个 schedule.json 导入，这样换课表不用重新装 APK。 */
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
            WidgetCommon.updateAll(this);
            render();
            Toast.makeText(this, "课表数据已更新", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        String raw = Prefs.getSemesterStart(this);
        String source = ScheduleStore.isImported(this) ? "导入的数据" : "APK 内置数据";
        if (raw.isEmpty()) {
            txtWeek.setText("还没设置开学日期");
            txtStart.setText("点「设置开学日期」，选开学那一周的任意一天，就能算出当前是第几周。\n数据来源："
                    + source);
        } else {
            int week = WeekUtils.currentWeek(this);
            txtWeek.setText("当前：第 " + week + " 周 · "
                    + WeekUtils.weekdayName(WeekUtils.todayWeekday()));
            txtStart.setText("开学日期：" + raw + "\n数据来源：" + source);
        }

        listContainer.removeAllViews();
        List<Course> courses = ScheduleStore.forWeek(this, 0);
        if (courses.isEmpty()) {
            listContainer.addView(buildRow("还没读到课程数据，请先运行 scripts/export_widget_data.py 重新导出。", 13));
            return;
        }

        String lastDay = "";
        for (Course course : courses) {
            String day = WeekUtils.weekdayName(course.weekday);
            if (!day.equals(lastDay)) {
                listContainer.addView(buildRow("— " + day + " —", 15));
                lastDay = day;
            }
            listContainer.addView(buildRow(String.format(Locale.US,
                    "%d-%d节  %s\n%s  %s  %s周",
                    course.start, course.end, course.name,
                    course.room, course.teacher, course.weeks), 13));
        }
    }

    private TextView buildRow(String text, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setPadding(0, 6, 0, 6);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
}

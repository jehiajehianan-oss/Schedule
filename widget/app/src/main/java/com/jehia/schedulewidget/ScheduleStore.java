package com.jehia.schedulewidget;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表数据：出厂数据随 APK 放在 assets/schedule.json，用户数据在 filesDir/schedule.json。
 *
 * 用户数据存在时优先读它；第一次在 App 里改任何东西（课程 / 考试 / 作息时间 / 学期周数）
 * 都会把出厂数据整份复制过去再改，所以「恢复内置数据」只要把这个文件删掉就行。
 */
public final class ScheduleStore {

    private static final String DATA_FILE = "schedule.json";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static List<Course> courses;
    private static List<Exam> exams;
    private static final Map<Integer, String> SECTION_TIMES = new LinkedHashMap<>();
    private static int semesterWeeks = 20;
    private static int finalsStartWeek = 0;
    private static String generatedAt = "";

    private ScheduleStore() {
    }

    /** 重新导出数据或从外部导入后调用，丢掉缓存。 */
    public static synchronized void invalidate() {
        courses = null;
        exams = null;
        SECTION_TIMES.clear();
    }

    // ---------------------------------------------------------------- 查询

    public static synchronized List<Course> courses(Context context) {
        ensureLoaded(context.getApplicationContext());
        return courses;
    }

    public static synchronized List<Exam> exams(Context context) {
        ensureLoaded(context.getApplicationContext());
        return exams;
    }

    /** 每节的上课时间段，例如 1 -> "08:00-08:45"。 */
    public static synchronized String sectionTime(Context context, int section) {
        ensureLoaded(context.getApplicationContext());
        String range = SECTION_TIMES.get(section);
        return range == null ? "" : range;
    }

    /** 每节的开始时间，例如 1 -> "08:00"。 */
    public static synchronized String sectionBeginText(Context context, int section) {
        String range = sectionTime(context, section);
        int index = range.indexOf('-');
        return index < 0 ? range : range.substring(0, index);
    }

    /** "08:00-09:35" 这样的显示用时间段。 */
    public static synchronized String timeRange(Context context, Course course) {
        ensureLoaded(context.getApplicationContext());
        String from = SECTION_TIMES.get(course.start);
        String to = SECTION_TIMES.get(course.end);
        if (from == null || to == null) {
            return course.start + "-" + course.end + "节";
        }
        return beginOf(from) + "-" + endOf(to);
    }

    /** 某节课的开始时间；没配作息时间时返回 null。 */
    public static synchronized LocalTime sectionBegin(Context context, int section) {
        ensureLoaded(context.getApplicationContext());
        return parseClock(beginOf(SECTION_TIMES.get(section)));
    }

    /** 某节课的结束时间；没配作息时间时返回 null。 */
    public static synchronized LocalTime sectionEnd(Context context, int section) {
        ensureLoaded(context.getApplicationContext());
        return parseClock(endOf(SECTION_TIMES.get(section)));
    }

    public static synchronized int semesterWeeks(Context context) {
        ensureLoaded(context.getApplicationContext());
        return semesterWeeks;
    }

    public static synchronized int finalsStartWeek(Context context) {
        ensureLoaded(context.getApplicationContext());
        return finalsStartWeek;
    }

    /** 一份按节次排好序的作息时间表，给编辑界面用。 */
    public static synchronized Map<Integer, String> sectionTimes(Context context) {
        ensureLoaded(context.getApplicationContext());
        return new LinkedHashMap<>(SECTION_TIMES);
    }

    /** 某天要上的课，按节次升序；week 传 0 表示不按周次过滤。 */
    public static List<Course> forDay(Context context, int weekday, int week) {
        List<Course> result = new ArrayList<>();
        for (Course course : courses(context)) {
            if (course.weekday != weekday) {
                continue;
            }
            if (week > 0 && !course.inWeek(week)) {
                continue;
            }
            result.add(course);
        }
        result.sort(Comparator.comparingInt(course -> course.start));
        return result;
    }

    /** 整个星期的课，按 (星期, 开始节次) 升序；week 传 0 表示不按周次过滤。 */
    public static List<Course> forWeek(Context context, int week) {
        List<Course> result = new ArrayList<>();
        for (Course course : courses(context)) {
            if (week > 0 && !course.inWeek(week)) {
                continue;
            }
            result.add(course);
        }
        result.sort(Comparator
                .comparingInt((Course course) -> course.weekday)
                .thenComparingInt(course -> course.start));
        return result;
    }

    /** 所有考试，按日期 + 开始时间排序。 */
    public static List<Exam> sortedExams(Context context) {
        List<Exam> result = new ArrayList<>(exams(context));
        result.sort(Comparator.comparing(Exam::sortKey));
        return result;
    }

    /** 落在 [from, to] 这两天（含）之间的考试。 */
    public static List<Exam> examsBetween(Context context, java.time.LocalDate from,
                                          java.time.LocalDate to) {
        List<Exam> result = new ArrayList<>();
        for (Exam exam : sortedExams(context)) {
            java.time.LocalDate day = exam.day();
            if (day == null || day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            result.add(exam);
        }
        return result;
    }

    /** 当前用的是不是用户自己那份数据。 */
    public static boolean isCustom(Context context) {
        return new File(context.getFilesDir(), DATA_FILE).isFile();
    }

    // ---------------------------------------------------------------- 写入

    public static synchronized void saveCourse(Context context, Course course) {
        ensureLoaded(context.getApplicationContext());
        boolean replaced = false;
        for (int index = 0; index < courses.size(); index++) {
            if (courses.get(index).id.equals(course.id)) {
                courses.set(index, course);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            courses.add(course);
        }
        sortCourses();
        persist(context);
    }

    public static synchronized void deleteCourse(Context context, String id) {
        ensureLoaded(context.getApplicationContext());
        for (int index = 0; index < courses.size(); index++) {
            if (courses.get(index).id.equals(id)) {
                courses.remove(index);
                break;
            }
        }
        persist(context);
    }

    public static synchronized void saveExam(Context context, Exam exam) {
        ensureLoaded(context.getApplicationContext());
        boolean replaced = false;
        for (int index = 0; index < exams.size(); index++) {
            if (exams.get(index).id.equals(exam.id)) {
                exams.set(index, exam);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            exams.add(exam);
        }
        exams.sort(Comparator.comparing(Exam::sortKey));
        persist(context);
    }

    public static synchronized void deleteExam(Context context, String id) {
        ensureLoaded(context.getApplicationContext());
        for (int index = 0; index < exams.size(); index++) {
            if (exams.get(index).id.equals(id)) {
                exams.remove(index);
                break;
            }
        }
        persist(context);
    }

    public static synchronized void setSectionTimes(Context context,
                                                    Map<Integer, String> times) {
        ensureLoaded(context.getApplicationContext());
        SECTION_TIMES.clear();
        SECTION_TIMES.putAll(times);
        persist(context);
    }

    public static synchronized void setSemesterWeeks(Context context, int weeks) {
        ensureLoaded(context.getApplicationContext());
        semesterWeeks = Math.max(1, weeks);
        persist(context);
    }

    public static synchronized void setFinalsStartWeek(Context context, int week) {
        ensureLoaded(context.getApplicationContext());
        finalsStartWeek = Math.max(0, week);
        persist(context);
    }

    /** 当前选中的色卡 id。 */
    public static synchronized String paletteId(Context context) {
        ensureLoaded(context.getApplicationContext());
        return Palette.currentId();
    }

    /** 换一张色卡（出厂的四张或 "custom"）。 */
    public static synchronized void selectPalette(Context context, String id) {
        ensureLoaded(context.getApplicationContext());
        Palette.select(id);
        persist(context);
    }


    /** 按当前色卡复制一份自定义色卡，改写它的 10 个底色（文字色按对比度自动配）。 */
    public static synchronized void saveCustomPalette(Context context, List<Integer> fills) {
        ensureLoaded(context.getApplicationContext());
        Palette.updateCustom(fills);
        persist(context);
    }

    /** 把外部 JSON 存进应用内部存储，之后优先读它。 */
    public static synchronized void importFrom(Context context, InputStream source)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = source.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("不是合法的 JSON 文件");
        }
        if (root.optJSONArray("courses") == null || root.optJSONArray("courses").length() == 0) {
            throw new IOException("文件里没有课程数据");
        }

        Context app = context.getApplicationContext();
        invalidate();
        ensureLoaded(app);
        applyJson(root, true);
        persist(app);
    }

    /** 删掉自定义数据，回到随 APK 一起装进来的那份。 */
    public static synchronized void clearCustom(Context context) {
        File target = new File(context.getFilesDir(), DATA_FILE);
        if (target.isFile()) {
            target.delete();
        }
        invalidate();
    }

    // ---------------------------------------------------------------- 内部

    private static void ensureLoaded(Context context) {
        if (courses != null) {
            return;
        }
        courses = new ArrayList<>();
        exams = new ArrayList<>();
        SECTION_TIMES.clear();
        semesterWeeks = 20;
        finalsStartWeek = 0;
        generatedAt = "";

        try (InputStream in = openStream(context)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            applyJson(new JSONObject(new String(buffer.toByteArray(),
                    StandardCharsets.UTF_8)), false);
        } catch (Exception e) {
            // 数据文件缺失或损坏时返回空表，界面会提示重新导出。
            courses = new ArrayList<>();
            exams = new ArrayList<>();
            SECTION_TIMES.clear();
            semesterWeeks = 20;
            finalsStartWeek = 0;
        }
    }

    /** 解析一份课表 JSON；全部字段都是可选的，缺什么补什么。 */
    private static void applyJson(JSONObject root, boolean replaceCourses) {
        if (replaceCourses) {
            courses = new ArrayList<>();
            exams = new ArrayList<>();
        }

        JSONObject times = root.optJSONObject("sectionTimes");
        if (times != null && times.length() > 0) {
            SECTION_TIMES.clear();
            Iterator<String> keys = times.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    SECTION_TIMES.put(Integer.valueOf(key), times.optString(key));
                } catch (NumberFormatException ignored) {
                    // 忽略非数字键
                }
            }
        }
        semesterWeeks = root.optInt("semesterWeeks", semesterWeeks <= 0 ? 20 : semesterWeeks);
        finalsStartWeek = root.optInt("finalsStartWeek", 0);
        generatedAt = root.optString("generatedAt", generatedAt);

        // 色卡：优先读 palettes + paletteId；旧数据只有 courseColors，会被当成一张 default 色卡。
        // 导入一份不含色卡信息的 JSON 时保留当前色卡，免得「导课程」把配色也冲掉。
        JSONArray palettes = root.optJSONArray("palettes");
        JSONArray colors = root.optJSONArray("courseColors");
        boolean hasPalette = (palettes != null && palettes.length() > 0)
                || (colors != null && colors.length() > 0);
        if (hasPalette || !replaceCourses) {
            Palette.load(palettes, root.optString("paletteId", ""), colors);
        }

        JSONArray courseArray = root.optJSONArray("courses");
        if (courseArray != null) {
            for (int i = 0; i < courseArray.length(); i++) {
                JSONObject item = courseArray.optJSONObject(i);
                if (item != null) {
                    courses.add(Course.fromJson(item, "c" + (i + 1)));
                }
            }
            sortCourses();
        }

        JSONArray examArray = root.optJSONArray("exams");
        if (examArray != null) {
            for (int i = 0; i < examArray.length(); i++) {
                JSONObject item = examArray.optJSONObject(i);
                if (item != null) {
                    exams.add(Exam.fromJson(item, "e" + (i + 1)));
                }
            }
            exams.sort(Comparator.comparing(Exam::sortKey));
        }
    }

    private static void sortCourses() {
        courses.sort(Comparator
                .comparingInt((Course course) -> course.weekday)
                .thenComparingInt(course -> course.start)
                .thenComparing(course -> course.name));
    }

    /** 写回 filesDir/schedule.json；不存在就先按当前内存内容整份落地。 */
    private static void persist(Context context) {
        Context app = context.getApplicationContext();
        JSONObject root = new JSONObject();
        try {
            root.put("version", 3);
            root.put("generatedAt", generatedAt);
            JSONObject times = new JSONObject();
            for (Map.Entry<Integer, String> entry : SECTION_TIMES.entrySet()) {
                times.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            root.put("sectionTimes", times);

            root.put("paletteId", Palette.currentId());
            root.put("palettes", Palette.toJson());
            root.put("courseColors", Palette.courseColorsJson());
            root.put("semesterWeeks", semesterWeeks);
            root.put("finalsStartWeek", finalsStartWeek);

            JSONArray examArray = new JSONArray();
            for (Exam exam : exams) {
                examArray.put(exam.toJson());
            }
            root.put("exams", examArray);

            JSONArray courseArray = new JSONArray();
            for (Course course : courses) {
                JSONObject item = course.toJson();
                item.put("weeks", Weeks.format(course.weekList));
                courseArray.put(item);
            }
            root.put("courses", courseArray);

            File file = new File(app.getFilesDir(), DATA_FILE);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file),
                    StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
            }
        } catch (Exception e) {
            throw new IllegalStateException("写回课表数据失败：" + e.getMessage(), e);
        }
    }

    private static InputStream openStream(Context context) throws IOException {
        File custom = new File(context.getFilesDir(), DATA_FILE);
        if (custom.isFile()) {
            return new FileInputStream(custom);
        }
        return context.getAssets().open(DATA_FILE);
    }

    private static String beginOf(String range) {
        if (range == null) {
            return null;
        }
        int index = range.indexOf('-');
        return index < 0 ? range : range.substring(0, index);
    }

    private static String endOf(String range) {
        if (range == null) {
            return null;
        }
        int index = range.indexOf('-');
        return index < 0 ? range : range.substring(index + 1);
    }

    private static LocalTime parseClock(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim(), CLOCK);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
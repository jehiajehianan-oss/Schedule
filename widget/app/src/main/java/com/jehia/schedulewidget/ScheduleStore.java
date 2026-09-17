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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取 assets/schedule.json，并提供按天 / 按周查询。 */
public final class ScheduleStore {

    private static final String DATA_FILE = "schedule.json";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static List<Course> courses;
    private static final Map<Integer, String> SECTION_TIMES = new LinkedHashMap<>();

    private ScheduleStore() {
    }

    /** 重新导出数据后调用，丢掉缓存。 */
    public static synchronized void invalidate() {
        courses = null;
        SECTION_TIMES.clear();
    }

    /** 每节的上课时间段，例如 1 -> "08:00-08:45"。 */
    public static synchronized String sectionTime(Context context, int section) {
        courses(context);
        return SECTION_TIMES.get(section);
    }

    /** "08:00-09:35" 这样的显示用时间段。 */
    public static synchronized String timeRange(Context context, Course course) {
        courses(context);
        String from = SECTION_TIMES.get(course.start);
        String to = SECTION_TIMES.get(course.end);
        if (from == null || to == null) {
            return course.start + "-" + course.end + "节";
        }
        return beginOf(from) + "-" + endOf(to);
    }

    /** 某节课的开始时间；没配作息时间时返回 null。 */
    public static synchronized LocalTime sectionBegin(Context context, int section) {
        courses(context);
        String range = SECTION_TIMES.get(section);
        return range == null ? null : parseClock(beginOf(range));
    }

    /** 某节课的结束时间；没配作息时间时返回 null。 */
    public static synchronized LocalTime sectionEnd(Context context, int section) {
        courses(context);
        String range = SECTION_TIMES.get(section);
        return range == null ? null : parseClock(endOf(range));
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

    public static synchronized List<Course> courses(Context context) {
        if (courses == null) {
            courses = load(context.getApplicationContext());
        }
        return courses;
    }

    /** 当前用的是不是导入进来的那份课表。 */
    public static boolean isImported(Context context) {
        return new File(context.getFilesDir(), DATA_FILE).isFile();
    }

    /** 把外部 JSON 存进应用内部存储，之后优先读它。 */
    public static synchronized void importFrom(Context context, InputStream source)
            throws IOException {
        File target = new File(context.getFilesDir(), DATA_FILE);
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        invalidate();
    }

    /** 删掉导入的数据，回到随 APK 一起装进来的那份。 */
    public static synchronized void clearImported(Context context) {
        File target = new File(context.getFilesDir(), DATA_FILE);
        if (target.isFile()) {
            target.delete();
        }
        invalidate();
    }

    private static InputStream openStream(Context context) throws IOException {
        File imported = new File(context.getFilesDir(), DATA_FILE);
        if (imported.isFile()) {
            return new FileInputStream(imported);
        }
        return context.getAssets().open(DATA_FILE);
    }

    private static String beginOf(String range) {
        int index = range.indexOf('-');
        return index < 0 ? range : range.substring(0, index);
    }

    private static String endOf(String range) {
        int index = range.indexOf('-');
        return index < 0 ? range : range.substring(index + 1);
    }

    private static List<Course> load(Context context) {
        List<Course> result = new ArrayList<>();
        try (InputStream in = openStream(context)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            JSONObject root = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));

            SECTION_TIMES.clear();
            JSONObject times = root.optJSONObject("sectionTimes");
            if (times != null) {
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

            JSONArray array = root.optJSONArray("courses");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) {
                        result.add(Course.fromJson(item));
                    }
                }
            }
        } catch (Exception e) {
            // 数据文件缺失或损坏时返回空表，界面会提示重新导出。
            result.clear();
        }
        return result;
    }
}

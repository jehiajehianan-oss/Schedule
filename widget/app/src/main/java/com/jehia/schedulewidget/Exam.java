package com.jehia.schedulewidget;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 一场期末考试：期末周里「今日课程」显示的就是它。 */
public final class Exam {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    public final String id;
    public final String name;
    /** yyyy-MM-dd。 */
    public final String date;
    /** HH:mm。 */
    public final String begin;
    /** HH:mm。 */
    public final String end;
    public final String room;

    public Exam(String id, String name, String date, String begin, String end, String room) {
        this.id = id;
        this.name = name;
        this.date = date;
        this.begin = begin;
        this.end = end;
        this.room = room;
    }

    public LocalDate day() {
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public LocalTime beginTime() {
        return parseClock(begin);
    }

    public LocalTime endTime() {
        return parseClock(end);
    }

    private static LocalTime parseClock(String text) {
        try {
            return LocalTime.parse(text, CLOCK);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 排序键：日期 + 开始时间，和导出脚本里的 examList() 一致。 */
    public String sortKey() {
        return date + begin;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("date", date);
            json.put("begin", begin);
            json.put("end", end);
            json.put("room", room);
        } catch (Exception e) {
            throw new IllegalStateException("考试写回 JSON 失败", e);
        }
        return json;
    }

    /** 缺字段时给默认值，兼容没有 exams 字段的旧数据。 */
    public static Exam fromJson(JSONObject json, String fallbackId) {
        String id = json.optString("id");
        if (id == null || id.isEmpty()) {
            id = fallbackId;
        }
        return new Exam(id, json.optString("name"), json.optString("date"),
                json.optString("begin"), json.optString("end"), json.optString("room"));
    }
}
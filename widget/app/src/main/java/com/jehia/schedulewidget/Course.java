package com.jehia.schedulewidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一条排课记录：某门课在某个星期的某个节次区间上课。 */
public final class Course {

    public final String name;
    /** 1 = 周一 …… 7 = 周日。 */
    public final int weekday;
    public final int start;
    public final int end;
    public final String teacher;
    public final String room;
    /** 原始周次文本，例如 "1-3、8-15单"。 */
    public final String weeks;
    /** 展开后的上课周次列表。 */
    public final List<Integer> weekList;

    public Course(String name, int weekday, int start, int end,
                  String teacher, String room, String weeks, List<Integer> weekList) {
        this.name = name;
        this.weekday = weekday;
        this.start = start;
        this.end = end;
        this.teacher = teacher;
        this.room = room;
        this.weeks = weeks;
        this.weekList = Collections.unmodifiableList(weekList);
    }

    /** 这门课在第 week 周是否要上。 */
    public boolean inWeek(int week) {
        return weekList.contains(week);
    }

    public boolean covers(int section) {
        return section >= start && section <= end;
    }

    public static Course fromJson(JSONObject json) {
        List<Integer> weeks = new ArrayList<>();
        JSONArray array = json.optJSONArray("weekList");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                weeks.add(array.optInt(i));
            }
        }
        return new Course(
                json.optString("name"),
                json.optInt("weekday"),
                json.optInt("start"),
                json.optInt("end"),
                json.optString("teacher"),
                json.optString("room"),
                json.optString("weeks"),
                weeks);
    }
}

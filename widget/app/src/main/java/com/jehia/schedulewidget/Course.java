package com.jehia.schedulewidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一条排课记录：某门课在某个星期的某个节次区间上课。 */
public final class Course {

    /** 稳定标识，App 靠它认出「同一条排课」；出厂数据是 c1、c2……，用户新加的是 u<时间戳>。 */
    public final String id;
    public final String name;
    /** 1 = 周一 …… 7 = 周日。 */
    public final int weekday;
    public final int start;
    public final int end;
    public final String teacher;
    public final String room;
    /** 原始周次文本，例如 "1-3、8-15单"；App 改过之后由 Weeks.format() 按 weekList 重新生成。 */
    public final String weeks;
    /** 展开后的上课周次列表，升序去重。 */
    public final List<Integer> weekList;
    /** 色卡槽位下标；-1 = 按课名自动取色。 */
    public final int color;
    /** 单独指定的颜色 #RRGGBB；空串表示跟着色卡走（App 里选「自定义颜色」时才有）。 */
    public final String colorHex;

    public Course(String id, String name, int weekday, int start, int end,
                  String teacher, String room, String weeks, List<Integer> weekList,
                  int color, String colorHex) {
        this.id = id;
        this.name = name;
        this.weekday = weekday;
        this.start = start;
        this.end = end;
        this.teacher = teacher;
        this.room = room;
        this.weeks = weeks;
        this.weekList = Collections.unmodifiableList(new ArrayList<>(weekList));
        this.color = color;
        this.colorHex = colorHex == null ? "" : colorHex;
    }

    /** 这门课在第 week 周是否要上。 */
    public boolean inWeek(int week) {
        return weekList.contains(week);
    }

    public boolean covers(int section) {
        return section >= start && section <= end;
    }


    /** 在格子里的位置：单格 / 首格 / 中格 / 尾格。 */
    public int positionAt(int section) {
        if (start == end) {
            return Palette.POS_SINGLE;
        }
        if (section <= start) {
            return Palette.POS_FIRST;
        }
        if (section >= end) {
            return Palette.POS_LAST;
        }
        return Palette.POS_MIDDLE;
    }


    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("weekday", weekday);
            json.put("start", start);
            json.put("end", end);
            json.put("teacher", teacher);
            json.put("room", room);
            json.put("weeks", weeks);
            json.put("weekList", new JSONArray(weekList));
            json.put("color", color);
            json.put("colorHex", colorHex);
        } catch (Exception e) {
            throw new IllegalStateException("课程写回 JSON 失败", e);
        }
        return json;
    }

    /**
     * 读一条课程。缺 id / color / weeks 都按默认值处理，兼容旧版导出的数据。
     */
    public static Course fromJson(JSONObject json, String fallbackId) {
        List<Integer> weeks = new ArrayList<>();
        JSONArray array = json.optJSONArray("weekList");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                weeks.add(array.optInt(i));
            }
        }
        Collections.sort(weeks);

        String id = json.optString("id");
        if (id == null || id.isEmpty()) {
            id = fallbackId;
        }
        String weeksText = json.optString("weeks");
        if (weeksText == null || weeksText.isEmpty()) {
            weeksText = Weeks.format(weeks);
        }
        int color = json.has("color") ? json.optInt("color", -1) : -1;

        return new Course(id, json.optString("name"), json.optInt("weekday"),
                json.optInt("start"), json.optInt("end"), json.optString("teacher"),
                json.optString("room"), weeksText, weeks, color,
                json.optString("colorHex", ""));
    }

    /** 新建一条排课时用：id 自动生成，颜色默认跟着课名走。 */
    public static Course create(String name, int weekday, int start, int end,
                                String teacher, String room, List<Integer> weekList,
                                int color, String colorHex) {
        List<Integer> sorted = new ArrayList<>(weekList);
        Collections.sort(sorted);
        return new Course("u" + System.currentTimeMillis(), name, weekday, start, end,
                teacher, room, Weeks.format(sorted), sorted, color, colorHex);
    }
}
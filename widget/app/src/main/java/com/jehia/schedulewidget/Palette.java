package com.jehia.schedulewidget;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 色卡系统：几套出厂色卡 + 一张用户自定义色卡 + 每门课的单独颜色。
 *
 * 唯一真源是 scripts/export_widget_data.py 里的 PALETTES，它写进 schedule.json 的 "palettes"；
 * 下面的 BUILTIN_* 只是「读不到 JSON 字段」时的兜底，导出脚本会逐项核对两边是否一致。
 *
 * 色块渲染有两条路，按 ROM 能力自动选：
 *   tint  —— 色块是一个纯白圆角 ImageView，用 setColorFilter 现染成任意颜色（首选，支持任意颜色）
 *   baked —— 少数 ROM 的 ImageView.setColorFilter 不是 @RemotableViewMethod，那就退回按色板
 *            预生成的 block_&lt;下标&gt;_&lt;形状&gt;.xml，颜色取最接近的色板色（圆角不变，颜色近似）
 * 第二条路存在的唯一理由是第一条没法在电脑上验证，只能到真机上自检。
 */
final class Palette {

    /** 一张色卡的槽位数：每门课按课名 hash 落在这 10 个槽里。 */
    static final int SLOTS = 10;

    /** 用户自定义色卡的固定 id。 */
    static final String CUSTOM_ID = "custom";

    /** 课块在格子里的位置。 */
    static final int POS_SINGLE = 0;
    static final int POS_FIRST = 1;
    static final int POS_MIDDLE = 2;
    static final int POS_LAST = 3;

    /** 位置 -> 圆角形状资源（白色，靠 setColorFilter 上色）。 */
    private static final int[] SHAPE = {
            R.drawable.block_shape_single,
            R.drawable.block_shape_first,
            R.drawable.block_shape_middle,
            R.drawable.block_shape_last,
    };

    /** 出厂色卡 id / 名字，顺序即选择界面的顺序。 */
    private static final String[] BUILTIN_IDS = {"default", "morandi", "macaron", "bright"};
    private static final String[] BUILTIN_NAMES = {
            "默认 · 深色底白字", "莫兰迪 · 低饱和", "马卡龙 · 浅底深字", "明快 · 亮底深字",
    };

    /** 出厂色卡底色；改这里必须同步改 export_widget_data.py 的 PALETTES。 */
    private static final String[][] BUILTIN_FILL = {
            {"#2563EB", "#5B54D6", "#8E45CC", "#C13C93", "#C93A46",
             "#B3541E", "#E0A63C", "#23713F", "#17706E", "#7EA9C4"},
            {"#5C6B7A", "#6E5F7A", "#7A5F5A", "#5F7A6B", "#7A6B5C",
             "#4F5B66", "#7F6A4E", "#55707A", "#6B5F7A", "#7A6E63"},
            {"#F2C4C4", "#F7DCA8", "#CFE6C4", "#B8DCE8", "#D2CBEF",
             "#F4CBDD", "#DCE6B4", "#C7E4DD", "#F5D6B8", "#DAD5EC"},
            {"#60A5FA", "#818CF8", "#C084FC", "#F472B6", "#F87171",
             "#FB923C", "#FACC15", "#4ADE80", "#2DD4BF", "#38BDF8"},
    };

    /** 出厂色卡文字色；必须和上面的底色配出 &gt;= 4.5:1。 */
    private static final String[][] BUILTIN_INK = {
            {"#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF",
             "#FFFFFF", "#2A1D04", "#FFFFFF", "#FFFFFF", "#0F2230"},
            {"#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF",
             "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF", "#FFFFFF"},
            {"#2E241C", "#2E241C", "#2E241C", "#2E241C", "#2E241C",
             "#2E241C", "#2E241C", "#2E241C", "#2E241C", "#2E241C"},
            {"#0B1220", "#0B1220", "#0B1220", "#0B1220", "#0B1220",
             "#0B1220", "#0B1220", "#0B1220", "#0B1220", "#0B1220"},
    };

    /** baked 兜底路的圆角资源：[色板下标][位置]；中格是 0，表示这格是方的。 */
    private static final int[][] BAKED = {
            {R.drawable.block_0_single, R.drawable.block_0_first, 0, R.drawable.block_0_last},
            {R.drawable.block_1_single, R.drawable.block_1_first, 0, R.drawable.block_1_last},
            {R.drawable.block_2_single, R.drawable.block_2_first, 0, R.drawable.block_2_last},
            {R.drawable.block_3_single, R.drawable.block_3_first, 0, R.drawable.block_3_last},
            {R.drawable.block_4_single, R.drawable.block_4_first, 0, R.drawable.block_4_last},
            {R.drawable.block_5_single, R.drawable.block_5_first, 0, R.drawable.block_5_last},
            {R.drawable.block_6_single, R.drawable.block_6_first, 0, R.drawable.block_6_last},
            {R.drawable.block_7_single, R.drawable.block_7_first, 0, R.drawable.block_7_last},
            {R.drawable.block_8_single, R.drawable.block_8_first, 0, R.drawable.block_8_last},
            {R.drawable.block_9_single, R.drawable.block_9_first, 0, R.drawable.block_9_last},
    };

    private Palette() {
    }
    // ---------------------------------------------------------------- 色卡

    /** 一张色卡：10 个槽，每槽一对 (fill, ink)。 */
    static final class Preset {
        final String id;
        final String name;
        final List<String> fills;
        final List<String> inks;

        Preset(String id, String name, List<String> fills, List<String> inks) {
            this.id = id;
            this.name = name;
            this.fills = fills;
            this.inks = inks;
        }

        String fillAt(int index) {
            return fills.get(Math.floorMod(index, fills.size()));
        }

        String inkAt(int index) {
            return inks.get(Math.floorMod(index, inks.size()));
        }
    }

    private static List<Preset> presets = builtins();
    private static String currentId = BUILTIN_IDS[0];
    private static Boolean tintSupported;

    private static List<Preset> builtins() {
        List<Preset> result = new ArrayList<>();
        for (int i = 0; i < BUILTIN_IDS.length; i++) {
            result.add(builtin(i));
        }
        return result;
    }

    private static Preset builtin(int index) {
        return new Preset(BUILTIN_IDS[index], BUILTIN_NAMES[index],
                asList(BUILTIN_FILL[index]), asList(BUILTIN_INK[index]));
    }

    private static List<String> asList(String[] values) {
        List<String> result = new ArrayList<>(values.length);
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    static synchronized List<Preset> presets() {
        return new ArrayList<>(presets);
    }

    static synchronized String currentId() {
        return currentId;
    }

    static synchronized Preset current() {
        Preset found = find(currentId);
        return found == null ? presets.get(0) : found;
    }

    static synchronized Preset find(String id) {
        if (id != null) {
            for (Preset preset : presets) {
                if (id.equals(preset.id)) {
                    return preset;
                }
            }
        }
        return null;
    }

    /** 选一张色卡（"custom" 也算）。 */
    static synchronized void select(String id) {
        if (find(id) != null) {
            currentId = id;
        }
    }

    static synchronized Preset custom() {
        return find(CUSTOM_ID);
    }

    /** 复制当前色卡生成自定义色卡；已经有了就直接返回。 */
    static synchronized Preset ensureCustom() {
        Preset existing = find(CUSTOM_ID);
        if (existing != null) {
            return existing;
        }
        Preset source = current();
        List<String> fills = new ArrayList<>(source.fills);
        List<String> inks = new ArrayList<>(fills.size());
        for (String fill : fills) {
            inks.add(hexOf(autoInk(parseColor(fill, Color.GRAY))));
        }
        Preset made = new Preset(CUSTOM_ID, "自定义", fills, inks);
        presets.add(made);
        return made;
    }

    /** 用一组底色改写自定义色卡，文字色按对比度自动配。 */
    static synchronized Preset updateCustom(List<Integer> fills) {
        Preset target = ensureCustom();
        List<String> nextFills = new ArrayList<>(SLOTS);
        List<String> nextInks = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            int color = i < fills.size() ? fills.get(i) : parseColor(target.fillAt(i), Color.GRAY);
            nextFills.add(hexOf(color));
            nextInks.add(hexOf(autoInk(color)));
        }
        target.fills.clear();
        target.fills.addAll(nextFills);
        target.inks.clear();
        target.inks.addAll(nextInks);
        currentId = CUSTOM_ID;
        return target;
    }

    /**
     * 用 schedule.json 的 palettes / paletteId 覆盖；任一项缺失都退回内置色卡。
     * 旧的 courseColors 字段仍然认：它会被当成一张 id = default 的色卡。
     */
    static synchronized void load(JSONArray array, String paletteId, JSONArray legacyColors) {
        Map<String, Preset> loaded = new LinkedHashMap<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Preset preset = fromJson(array.optJSONObject(i));
                if (preset != null) {
                    loaded.put(preset.id, preset);
                }
            }
        }
        if (loaded.isEmpty() && legacyColors != null && legacyColors.length() > 0) {
            Preset legacy = fromJson(legacyColors, BUILTIN_IDS[0], BUILTIN_NAMES[0]);
            if (legacy != null) {
                loaded.put(legacy.id, legacy);
            }
        }

        List<Preset> result = new ArrayList<>();
        for (int i = 0; i < BUILTIN_IDS.length; i++) {
            Preset fromJson = loaded.remove(BUILTIN_IDS[i]);
            result.add(fromJson == null ? builtin(i) : fromJson);
        }
        Preset custom = loaded.remove(CUSTOM_ID);
        if (custom != null) {
            result.add(custom);
        }
        result.addAll(loaded.values());

        presets = result;
        currentId = find(paletteId) == null ? BUILTIN_IDS[0] : paletteId;
    }

    /** 当前色卡的颜色对，写回 schedule.json 的 courseColors（旧字段，保持兼容）。 */
    static synchronized JSONArray courseColorsJson() {
        JSONArray array = new JSONArray();
        Preset preset = current();
        for (int i = 0; i < preset.fills.size(); i++) {
            JSONObject pair = new JSONObject();
            try {
                pair.put("fill", preset.fillAt(i));
                pair.put("ink", preset.inkAt(i));
            } catch (Exception ignored) {
                // JSONObject.put 只在 key 为 null 时抛，这里不可能
            }
            array.put(pair);
        }
        return array;
    }

    /** 全部色卡，写回 schedule.json 的 palettes。 */
    static synchronized JSONArray toJson() {
        JSONArray array = new JSONArray();
        for (Preset preset : presets) {
            JSONObject item = new JSONObject();
            JSONArray colors = new JSONArray();
            for (int i = 0; i < preset.fills.size(); i++) {
                JSONObject pair = new JSONObject();
                try {
                    pair.put("fill", preset.fillAt(i));
                    pair.put("ink", preset.inkAt(i));
                } catch (Exception ignored) {
                    // 同上
                }
                colors.put(pair);
            }
            try {
                item.put("id", preset.id);
                item.put("name", preset.name);
                item.put("colors", colors);
            } catch (Exception ignored) {
                // 同上
            }
            array.put(item);
        }
        return array;
    }

    private static Preset fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        String id = json.optString("id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        return fromJson(json.optJSONArray("colors"), id,
                json.optString("name", id));
    }

    /** 一个 colors 数组（每项 {"fill","ink"} 或旧版的纯颜色串）-> 色卡。 */
    private static Preset fromJson(JSONArray colors, String id, String name) {
        if (colors == null || colors.length() == 0) {
            return null;
        }
        List<String> fills = new ArrayList<>(SLOTS);
        List<String> inks = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            String fill = null;
            String ink = null;
            if (i < colors.length()) {
                Object raw = colors.opt(i);
                if (raw instanceof JSONObject) {
                    JSONObject pair = (JSONObject) raw;
                    fill = pair.optString("fill", null);
                    ink = pair.optString("ink", null);
                } else if (raw instanceof String) {
                    fill = (String) raw;
                }
            }
            if (fill == null || parseColor(fill, 0) == 0) {
                fill = BUILTIN_FILL[0][i];
            }
            int fillColor = parseColor(fill, Color.GRAY);
            if (ink == null || parseColor(ink, 0) == 0) {
                ink = hexOf(autoInk(fillColor));
            }
            fills.add(fill);
            inks.add(ink);
        }
        return new Preset(id, name == null || name.isEmpty() ? id : name, fills, inks);
    }
    // ---------------------------------------------------------------- 取色

    static synchronized int size() {
        return SLOTS;
    }

    /** 某门课的颜色下标：课程自带就用它，否则按课名稳定分配（同一门课永远同色）。 */
    static synchronized int indexOf(Course course) {
        if (course == null) {
            return 0;
        }
        return course.color >= 0 ? Math.floorMod(course.color, SLOTS) : indexOf(course.name);
    }

    /** 课程名 -> 色板下标；和预览页的 colorIndexOf() 算法一致。 */
    static synchronized int indexOf(String name) {
        String text = name == null ? "" : name;
        return Math.floorMod(Math.abs(text.hashCode()), SLOTS);
    }

    static synchronized int fillAt(int index) {
        return parseColor(current().fillAt(index), Color.GRAY);
    }

    static synchronized int inkAt(int index) {
        return parseColor(current().inkAt(index), Color.WHITE);
    }

    /** 课块底色：课程自带的自定义颜色优先，其次才是色卡槽位。 */
    static synchronized int fillOf(Course course) {
        if (course != null && course.colorHex != null && !course.colorHex.isEmpty()) {
            int picked = parseColor(course.colorHex, 0);
            if (picked != 0) {
                return picked;
            }
        }
        return fillAt(indexOf(course));
    }

    /** 课块文字色：自定义颜色按对比度现配，色卡槽位用配好的那一对。 */
    static synchronized int inkOf(Course course) {
        if (course != null && course.colorHex != null && !course.colorHex.isEmpty()) {
            int picked = parseColor(course.colorHex, 0);
            if (picked != 0) {
                return autoInk(picked);
            }
        }
        return inkAt(indexOf(course));
    }

    static int blockShape(int position) {
        return SHAPE[Math.floorMod(position, SHAPE.length)];
    }

    /** 颜色选择器 / 侧边竖条用的圆角色块（四角都圆）。 */
    static int roundDrawable(int colorIndex) {
        return BAKED[Math.floorMod(colorIndex, BAKED.length)][POS_SINGLE];
    }

    // ---------------------------------------------------------------- 颜色工具

    /** 在纯白和纯黑之间挑对比度更高的那个当文字色；两者总能保证 &gt;= 4.5:1。 */
    static int autoInk(int fill) {
        return contrast(fill, Color.WHITE) >= contrast(fill, Color.BLACK)
                ? Color.WHITE : Color.BLACK;
    }

    static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        double light = Math.max(a, b);
        double dark = Math.min(a, b);
        return (light + 0.05) / (dark + 0.05);
    }

    static double luminance(int color) {
        return 0.2126 * channel(Color.red(color))
                + 0.7152 * channel(Color.green(color))
                + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double scaled = value / 255.0;
        return scaled <= 0.03928 ? scaled / 12.92 : Math.pow((scaled + 0.055) / 1.055, 2.4);
    }

    static String hexOf(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    /** 解析 #RRGGBB / #AARRGGBB；失败返回 fallback。 */
    static int parseColor(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Color.parseColor(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int parseColor(Object value, int fallback) {
        return value instanceof String ? parseColor((String) value, fallback) : fallback;
    }

    // ---------------------------------------------------------------- 渲染能力

    /**
     * 这台机器能不能给 ImageView 上 setColorFilter。查的是框架真身上的
     * {@code @RemotableViewMethod} 注解，不是编译期常量，所以结果就是真机事实。
     */
    static boolean tintSupported() {
        Boolean cached = tintSupported;
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        try {
            Method method = android.widget.ImageView.class.getMethod("setColorFilter", int.class);
            for (Annotation annotation : method.getAnnotations()) {
                if ("android.view.RemotableViewMethod".equals(
                        annotation.annotationType().getName())) {
                    found = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            found = false;
        }
        tintSupported = found;
        return found;
    }

    /**
     * baked 兜底路用：找最接近的色板色，返回它在这个位置上的圆角资源；
     * 中格返回 0，表示这格是方的，直接用 setBackgroundColor。
     */
    static int bakedDrawable(int fill, int position) {
        return BAKED[nearestBaked(fill)][Math.floorMod(position, 4)];
    }

    /**
     * baked 兜底路用：真正会被画出来的底色（离 fill 最近的出厂色）。
     * 中格没有圆角资源、只能 setBackgroundColor，也必须用这个色，否则一整块会前后不一致。
     */
    static int snappedFill(int fill) {
        return parseColor(BUILTIN_FILL[0][nearestBaked(fill)], Color.GRAY);
    }

    /**
     * baked 兜底路用：文字色必须跟着**吸附后**的底色走。
     * 直接用选中色卡的 ink 会在浅色卡（马卡龙 / 明快）上压成深字压深块，什么都读不出来。
     */
    static int bakedInk(int fill) {
        int nearest = nearestBaked(fill);
        int snapped = parseColor(BUILTIN_FILL[0][nearest], Color.GRAY);
        // 正好命中出厂色就沿用设计好的那一对 ink，否则按对比度现配
        return fill == snapped
                ? parseColor(BUILTIN_INK[0][nearest], Color.WHITE)
                : autoInk(snapped);
    }

    /** 离 fill 最近的兜底色板下标（兜底资源只覆盖主色卡那 10 个颜色）。 */
    private static int nearestBaked(int fill) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < SLOTS; index++) {
            int candidate = parseColor(BUILTIN_FILL[0][index], Color.GRAY);
            double distance = distance(fill, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private static double distance(int first, int second) {
        double red = Color.red(first) - Color.red(second);
        double green = Color.green(first) - Color.green(second);
        double blue = Color.blue(first) - Color.blue(second);
        return red * red * 0.3 + green * green * 0.59 + blue * blue * 0.11;
    }
}
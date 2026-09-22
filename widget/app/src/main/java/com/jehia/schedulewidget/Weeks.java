package com.jehia.schedulewidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 周次文本 <-> 周次列表。
 *
 * 读的时候用 coursetable/schedule.py 的 expand_weeks() 逻辑（Python 侧负责展开），
 * 写的时候在这里反推文本，语法必须和 expand_weeks 认的那套一致：
 *   分隔符   、 , ， ; ； / 空白 都行
 *   区间     1-3、8-15、13-15单
 *   单个     6、07、09
 * 单双周只在区间上写（1-9单），单个周次不写「单/双」。
 */
final class Weeks {

    private Weeks() {
    }

    static String format(List<Integer> weeks) {
        List<Integer> sorted = new ArrayList<>(weeks);
        Collections.sort(sorted);
        List<Integer> unique = new ArrayList<>();
        for (int week : sorted) {
            if (week > 0 && !unique.contains(week)) {
                unique.add(week);
            }
        }
        if (unique.isEmpty()) {
            return "";
        }

        List<String> tokens = new ArrayList<>();
        List<List<Integer>> runs = consecutiveRuns(unique);
        List<Integer> loose = new ArrayList<>();
        for (List<Integer> run : runs) {
            if (run.size() >= 3) {
                tokens.add(run.get(0) + "-" + run.get(run.size() - 1));
            } else {
                loose.addAll(run);
            }
        }
        // 剩下的零散周次再按单双周归拢一次，能写成「13-15单」这种紧凑写法
        for (List<Integer> run : parityRuns(loose)) {
            if (run.size() >= 3) {
                tokens.add(run.get(0) + "-" + run.get(run.size() - 1)
                        + (run.get(0) % 2 == 0 ? "双" : "单"));
            } else {
                for (int week : run) {
                    tokens.add(String.valueOf(week));
                }
            }
        }

        // 按每段的第一周排序，读起来顺
        tokens.sort((first, second) -> Integer.compare(firstWeek(first), firstWeek(second)));
        return String.join("、", tokens);
    }

    private static int firstWeek(String token) {
        int index = token.indexOf('-');
        String head = index < 0 ? token : token.substring(0, index);
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 切成若干段连续（步长 1）的区间。 */
    private static List<List<Integer>> consecutiveRuns(List<Integer> weeks) {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int week : weeks) {
            if (!current.isEmpty() && week != current.get(current.size() - 1) + 1) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(week);
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    /** 按奇偶切成步长 2 的等差数列，且同一段里奇偶必须一致。 */
    private static List<List<Integer>> parityRuns(List<Integer> weeks) {
        List<Integer> sorted = new ArrayList<>(weeks);
        Collections.sort(sorted);
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int week : sorted) {
            if (!current.isEmpty()
                    && (week != current.get(current.size() - 1) + 2
                    || week % 2 != current.get(0) % 2)) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(week);
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }
}
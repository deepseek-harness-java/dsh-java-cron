package cn.xiaofuge.dsh.cron.service;

import cn.xiaofuge.dsh.cron.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cron Forge：cron 表达式工坊。
 * - 解析 5 段 cron（分 时 日 月 周），人话解释
 * - 计算下次 N 次触发时间
 * - 校验字段范围与组合矛盾
 * - 相似任务去重建议（同一表达式/同一节奏）
 * 红线：不写系统 crontab。
 */
@Service
public class CronForgeService {

    private static final Map<String, String> MONTHS = Map.ofEntries(
            Map.entry("jan", "1"), Map.entry("feb", "2"), Map.entry("mar", "3"),
            Map.entry("apr", "4"), Map.entry("may", "5"), Map.entry("jun", "6"),
            Map.entry("jul", "7"), Map.entry("aug", "8"), Map.entry("sep", "9"),
            Map.entry("oct", "10"), Map.entry("nov", "11"), Map.entry("dec", "12"));
    private static final Map<String, String> WEEKDAYS = Map.ofEntries(
            Map.entry("sun", "0"), Map.entry("mon", "1"), Map.entry("tue", "2"),
            Map.entry("wed", "3"), Map.entry("thu", "4"), Map.entry("fri", "5"), Map.entry("sat", "6"));

    /** 任务登记表：name -> {expr, owner, description} */
    private final Map<String, Map<String, Object>> jobs = new LinkedHashMap<>();

    public Map<String, Object> parse(String expr) {
        String[] fields = splitFields(expr);
        List<Map<String, Object>> parts = new ArrayList<>();
        String[] names = {"分钟", "小时", "日", "月", "星期"};
        int[][] ranges = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};
        for (int i = 0; i < 5; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("field", names[i]);
            p.put("raw", fields[i]);
            p.put("values", expand(fields[i], ranges[i], i == 4 ? 0 : ranges[i][0]));
            parts.add(p);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expr", expr);
        result.put("fields", fields);
        result.put("parts", parts);
        result.put("human", humanize(fields));
        result.put("kind", classify(fields));
        return result;
    }

    private String[] splitFields(String expr) {
        if (expr == null || expr.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "cron 表达式不能为空");
        String[] f = expr.trim().toLowerCase().split("\\s+");
        if (f.length != 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "需要 5 段 cron（分 时 日 月 周），收到 " + f.length + " 段");
        }
        for (int i = 0; i < 5; i++) {
            for (var e : MONTHS.entrySet()) f[i] = f[i].replace(e.getKey(), e.getValue());
            for (var e : WEEKDAYS.entrySet()) f[i] = f[i].replace(e.getKey(), e.getValue());
            f[i] = f[i].replace("?", "*");
        }
        return f;
    }

    private Set<Integer> expand(String field, int[] range, int min) {
        Set<Integer> out = new TreeSet<>();
        for (String token : field.split(",")) {
            int step = 1;
            String base = token;
            if (token.contains("/")) {
                String[] seg = token.split("/");
                base = seg[0];
                step = Integer.parseInt(seg[1]);
            }
            int lo = min, hi = range[1];
            if (base.equals("*")) {
                // keep defaults
            } else if (base.contains("-")) {
                String[] seg = base.split("-");
                lo = Integer.parseInt(seg[0]);
                hi = Integer.parseInt(seg[1]);
            } else {
                lo = Integer.parseInt(base);
                hi = step > 1 ? range[1] : lo;
            }
            for (int v = lo; v <= hi; v += step) {
                int norm = v;
                if (range[0] == 0 && range[1] == 7 && v == 7) norm = 0; // 周日 7->0
                out.add(norm);
            }
        }
        if (out.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "无法展开字段：" + field);
        return out;
    }

    private String humanize(String[] f) {
        StringBuilder sb = new StringBuilder();
        if (f[0].equals("*") && f[1].equals("*")) sb.append("每分钟");
        else if (f[1].equals("*") && f[0].startsWith("*/")) sb.append("每 ").append(f[0].substring(2)).append(" 分钟");
        else if (f[1].equals("*")) sb.append("每小时第 ").append(f[0]).append(" 分");
        else sb.append("每天 ").append(String.format("%02d:%02d", Integer.parseInt(f[1]), Integer.parseInt(f[0])));
        if (!f[2].equals("*")) sb.append("，每月 ").append(f[2]).append(" 号");
        if (!f[3].equals("*")) sb.append("，").append(monthNames(f[3]));
        if (!f[4].equals("*")) sb.append("，").append(weekdayNames(f[4]));
        sb.append("触发");
        return sb.toString();
    }

    private String monthNames(String field) {
        try {
            return "每月 " + expand(field, new int[]{1, 12}, 1).stream().map(String::valueOf).collect(Collectors.joining("/")) + " 月";
        } catch (Exception e) {
            return "月份 " + field;
        }
    }

    private String weekdayNames(String field) {
        String[] names = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        try {
            return expand(field, new int[]{0, 7}, 0).stream().map(v -> names[v]).collect(Collectors.joining("/"));
        } catch (Exception e) {
            return "星期 " + field;
        }
    }

    private String classify(String[] f) {
        if (f[0].startsWith("*/") && f[1].equals("*") && f[2].equals("*") && f[3].equals("*") && f[4].equals("*"))
            return "interval-minute";
        if (f[0].equals("0") && f[1].startsWith("*/") && f[2].equals("*") && f[3].equals("*") && f[4].equals("*"))
            return "interval-hour";
        if (f[1].equals("*") && f[2].equals("*") && f[3].equals("*") && f[4].equals("*") && !f[0].equals("*"))
            return "hourly";
        if (!f[2].equals("*")) return "monthly-ish";
        if (!f[4].equals("*")) return "weekly";
        return "daily";
    }

    // ---------- 下次触发 ----------

    public Map<String, Object> nextRuns(String expr, int count) {
        String[] f = splitFields(expr);
        Set<Integer> minutes = expand(f[0], new int[]{0, 59}, 0);
        Set<Integer> hours = expand(f[1], new int[]{0, 23}, 0);
        Set<Integer> days = expand(f[2], new int[]{1, 31}, 1);
        Set<Integer> months = expand(f[3], new int[]{1, 12}, 1);
        Set<Integer> weekdays = expand(f[4], new int[]{0, 7}, 0);

        List<String> runs = new ArrayList<>();
        LocalDateTime t = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(1);
        int guard = 0;
        while (runs.size() < count && guard++ < 5_000_000) {
            if (!months.contains(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            boolean dayOk = days.contains(t.getDayOfMonth());
            boolean dowOk = weekdays.contains(t.getDayOfWeek().getValue() % 7);
            boolean dayMatch = f[2].equals("*") ? dowOk : (f[4].equals("*") ? dayOk : (dayOk || dowOk));
            if (!dayMatch) {
                t = t.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!hours.contains(t.getHour())) {
                t = t.plusHours(1).withMinute(0);
                continue;
            }
            if (minutes.contains(t.getMinute())) {
                runs.add(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)")));
            }
            t = t.plusMinutes(1);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expr", expr);
        result.put("count", runs.size());
        result.put("nextRuns", runs);
        return result;
    }

    // ---------- 校验 ----------

    public Map<String, Object> validate(String expr) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String[] f;
        try {
            f = splitFields(expr);
        } catch (ApiException e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("expr", expr);
            r.put("valid", false);
            r.put("issues", List.of(issue("error", "format", e.getMessage())));
            return r;
        }
        try {
            int[][] ranges = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};
            String[] names = {"分钟", "小时", "日", "月", "星期"};
            for (int i = 0; i < 5; i++) {
                try {
                    checkField(f[i], ranges[i], names[i]);
                } catch (ApiException e) {
                    issues.add(issue("error", "range", e.getMessage()));
                }
            }
        } catch (Exception ignored) {
        }
        if (!f[2].equals("*") && !f[4].equals("*")) {
            issues.add(issue("warn", "dom-dow", "日与星期同时受限：cron 语义为 OR（任一匹配即触发），确认是否符合预期"));
        }
        if (f[2].equals("31")) {
            issues.add(issue("warn", "month-length", "日=31 只在 31 天的月份触发（2/4/6/9/11 月会跳过）"));
        }
        if (f[2].equals("30") || f[2].equals("29")) {
            issues.add(issue("warn", "month-length", "日=" + f[2] + " 在短月不触发"));
        }
        if (f[0].startsWith("*/1") || f[0].equals("*")) {
            issues.add(issue("warn", "high-frequency", "每分钟执行的任务请确认消费侧幂等，避免堆积"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("expr", expr);
        r.put("valid", issues.stream().noneMatch(i -> "error".equals(i.get("severity"))));
        r.put("issues", issues);
        return r;
    }

    private void checkField(String field, int[] range, String name) {
        for (int v : expand(field, range, range[0])) {
            if (v < range[0] || v > range[1]) throw new ApiException(HttpStatus.BAD_REQUEST, name + " 超出范围：" + v);
        }
    }

    private Map<String, Object> issue(String severity, String type, String message) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("severity", severity);
        i.put("type", type);
        i.put("message", message);
        return i;
    }

    // ---------- 任务登记 + 去重建议 ----------

    public Map<String, Object> register(String name, String expr, String owner, String description) {
        if (name == null || name.isBlank() || expr == null || expr.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "name 与 expr 不能为空");
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", name);
        job.put("expr", expr);
        job.put("owner", owner == null ? "" : owner);
        job.put("description", description == null ? "" : description);
        job.put("kind", classify(splitFields(expr)));
        jobs.put(name, job);
        Map<String, Object> r = new LinkedHashMap<>(job);
        r.put("registered", true);
        return r;
    }

    public Map<String, Object> suggest() {
        List<Map<String, Object>> duplicates = new ArrayList<>();
        List<Map<String, Object>> list = new ArrayList<>(jobs.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Map<String, Object> a = list.get(i), b = list.get(j);
                boolean sameExpr = a.get("expr").equals(b.get("expr"));
                boolean sameKind = a.get("kind").equals(b.get("kind"));
                if (sameExpr || sameKind) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("type", sameExpr ? "same-expr" : "same-rhythm");
                    d.put("jobs", List.of(a.get("name"), b.get("name")));
                    d.put("expr", a.get("expr"));
                    d.put("suggestion", sameExpr
                            ? "两个任务用同一表达式：确认能否合并为一次批量执行"
                            : "同一节奏（" + a.get("kind") + "）：考虑错峰执行避免资源竞争");
                    duplicates.add(d);
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobCount", jobs.size());
        r.put("suggestionCount", duplicates.size());
        r.put("suggestions", duplicates);
        r.put("jobs", list);
        return r;
    }

    public Map<String, Object> batchExplain(List<String> exprs) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> safeExprs = exprs == null ? Collections.<String>emptyList() : exprs;
        for (String expr : safeExprs) {
            Map<String, Object> item = new LinkedHashMap<>();
            try {
                Map<String, Object> parsed = parse(expr);
                item.put("expr", expr);
                item.put("human", parsed.get("human"));
                item.put("kind", parsed.get("kind"));
                item.put("ok", true);
            } catch (ApiException e) {
                item.put("expr", expr);
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            items.add(item);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", items.size());
        r.put("items", items);
        return r;
    }

    public List<Map<String, Object>> listJobs() {
        return new ArrayList<>(jobs.values());
    }

    public Map<String, Object> library() {
        Map<String, Object> lib = new LinkedHashMap<>();
        lib.put("每分钟", "* * * * *");
        lib.put("每 5 分钟", "*/5 * * * *");
        lib.put("每小时整点", "0 * * * *");
        lib.put("每天 02:30", "30 2 * * *");
        lib.put("工作日 09:00", "0 9 * * 1-5");
        lib.put("每周一 03:00", "0 3 * * 1");
        lib.put("每月 1 号 00:00", "0 0 1 * *");
        lib.put("每季度首月 1 号", "0 0 1 1,4,7,10 *");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("patterns", lib);
        return r;
    }
}

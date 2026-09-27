package cn.xiaofuge.dsh.cron.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshCronForgePlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-cron-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshCronForgePlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ParseTool(),
                new NextRunsTool(),
                new ValidateTool(),
                new RegisterTool(),
                new SuggestTool(),
                new BatchExplainTool(),
                new LibraryTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("cron-forge-capabilities", 20, """
                ## DSH Cron Forge Plugin
                - 除 cron_register 外全部只读；cron_register 仅在用户明确要求登记任务时调用。
                - 触发时间一律用 cron_next_runs 计算，禁止心算。
                - 红线：不写系统 crontab、不执行任何命令。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__cron_")) {
                return PluginHookResult.context("DSH Cron Forge audit: no crontab writes.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_CRON_ADMIN_URL", "http://127.0.0.1:8095")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String exprJson(Map<String, Object> args) {
        return "{\"expr\":\"" + json(str(args, "expr")) + "\"}";
    }

    private class ParseTool extends AbstractTool {
        @Override public String name() { return "cron_parse"; }
        @Override public String description() { return "解析 5 段 cron 表达式：各字段取值展开 + 人话解释 + 节奏分类。解释表达式含义前必须调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("expr", stringSchema("cron 表达式，5 段（分 时 日 月 周）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8095"))
                    .required("expr")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/cron/parse", exprJson(args), args));
        }
    }

    private class NextRunsTool extends AbstractTool {
        @Override public String name() { return "cron_next_runs"; }
        @Override public String description() { return "计算 cron 表达式下次 N 次触发时间（本地时区）。用户问什么时候跑/下次几点跑时必须调用，禁止心算。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("expr", stringSchema("cron 表达式"))
                    .prop("count", stringSchema("次数，默认 5，最多 20"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("expr")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String count = str(args, "count");
            String body = "{\"expr\":\"" + json(str(args, "expr")) + "\"";
            if (!count.isBlank()) body += ",\"count\":" + Integer.parseInt(count);
            body += "}";
            return ok(post("/api/cron/next", body, args));
        }
    }

    private class ValidateTool extends AbstractTool {
        @Override public String name() { return "cron_validate"; }
        @Override public String description() { return "校验 cron 表达式：字段范围错误（error）、月末陷阱/日周 OR 语义/高频幂等提醒（warn）。写入任何调度系统前建议调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("expr", stringSchema("cron 表达式"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("expr")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/cron/validate", exprJson(args), args));
        }
    }

    private class RegisterTool extends AbstractTool {
        @Override public String name() { return "cron_register"; }
        @Override public String description() { return "登记定时任务（名称/表达式/负责人/说明），用于后续去重建议。仅当用户明确要求登记时调用；不写系统 crontab。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("name", stringSchema("任务名，如 order-sync"))
                    .prop("expr", stringSchema("cron 表达式"))
                    .prop("owner", stringSchema("负责人"))
                    .prop("description", stringSchema("任务说明"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("name", "expr")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"name\":\"" + json(str(args, "name")) + "\",\"expr\":\"" + json(str(args, "expr"))
                    + "\",\"owner\":\"" + json(str(args, "owner")) + "\",\"description\":\"" + json(str(args, "description")) + "\"}";
            return ok(post("/api/cron/jobs", body, args));
        }
    }

    private class SuggestTool extends AbstractTool {
        @Override public String name() { return "cron_suggest"; }
        @Override public String description() { return "相似任务去重建议：同表达式（可合并）/同节奏（建议错峰）。整理任务清单前调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/cron/suggest", "{}", args));
        }
    }

    private class BatchExplainTool extends AbstractTool {
        @Override public String name() { return "cron_batch_explain"; }
        @Override public String description() { return "批量解释多个 cron 表达式（人话 + 节奏分类，非法表达式标注错误）。用户给一批表达式时调用。"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> item = stringSchema("cron 表达式");
            Map<String, Object> arr = new java.util.HashMap<>();
            arr.put("type", "array");
            arr.put("description", "表达式列表");
            arr.put("items", item);
            return objectSchema()
                    .prop("exprs", arr)
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("exprs")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder body = new StringBuilder("{\"exprs\":[");
            Object exprs = args.get("exprs");
            if (exprs instanceof List<?> list) {
                boolean first = true;
                for (Object e : list) {
                    if (!first) body.append(",");
                    body.append("\"").append(json(String.valueOf(e))).append("\"");
                    first = false;
                }
            }
            body.append("]}");
            return ok(post("/api/cron/batch-explain", body.toString(), args));
        }
    }

    private class LibraryTool extends AbstractTool {
        @Override public String name() { return "cron_library"; }
        @Override public String description() { return "常用 cron 节奏库（每分钟/每天 02:30/工作日 09:00 等现成表达式）。用户要写新表达式时先给参考。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(get("/api/cron/library", args));
        }
    }
}

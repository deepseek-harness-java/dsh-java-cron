package cn.xiaofuge.dsh.cron.controller;

import cn.xiaofuge.dsh.cron.config.CronForgeProperties;
import cn.xiaofuge.dsh.cron.model.ApiException;
import cn.xiaofuge.dsh.cron.service.CronForgeService;
import cn.xiaofuge.dsh.cron.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class CronForgeController {
    private final CronForgeService cronService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final CronForgeProperties properties;

    public CronForgeController(CronForgeService cronService,
                               HarnessAiStreamService harnessAiStreamService,
                               CronForgeProperties properties) {
        this.cronService = cronService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record ParseRequest(String expr) {
    }

    public record NextRequest(String expr, Integer count) {
    }

    public record RegisterRequest(String name, String expr, String owner, String description) {
    }

    public record BatchRequest(List<String> exprs) {
    }

    @PostMapping("/api/cron/parse")
    public Map<String, Object> parse(@RequestBody ParseRequest request) {
        return cronService.parse(request.expr());
    }

    @PostMapping("/api/cron/next")
    public Map<String, Object> next(@RequestBody NextRequest request) {
        return cronService.nextRuns(request.expr(), request.count() == null ? 5 : Math.min(request.count(), 20));
    }

    @PostMapping("/api/cron/validate")
    public Map<String, Object> validate(@RequestBody ParseRequest request) {
        return cronService.validate(request.expr());
    }

    @PostMapping("/api/cron/jobs")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        return cronService.register(request.name(), request.expr(), request.owner(), request.description());
    }

    @GetMapping("/api/cron/jobs")
    public List<Map<String, Object>> jobs() {
        return cronService.listJobs();
    }

    @PostMapping("/api/cron/suggest")
    public Map<String, Object> suggest() {
        return cronService.suggest();
    }

    @PostMapping("/api/cron/batch-explain")
    public Map<String, Object> batchExplain(@RequestBody BatchRequest request) {
        return cronService.batchExplain(request.exprs());
    }

    @GetMapping("/api/cron/library")
    public Map<String, Object> library() {
        return cronService.library();
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Cron Forge（定时任务工坊）：cron 表达式解析/下次触发/校验/去重建议\n");
        prompt.append("- 你可以使用以下工具获取真实数据（回答表达式含义、触发时间、冲突类问题时，必须先调用工具查证，禁止心算）：\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_parse(expr)：解析并人话解释\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_next_runs(expr, count?)：下次 N 次触发时间\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_validate(expr)：校验（范围/组合矛盾/月末陷阱）\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_register(name, expr, owner?, description?)：登记定时任务（用户明确要求登记时才调用）\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_suggest()：相似任务去重建议（需先登记）\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_batch_explain(exprs)：批量解释\n");
        prompt.append("  - plugin__dsh-java-cron-plugin__cron_library()：常用节奏库\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 触发时间必须来自 cron_next_runs 工具结果，禁止心算日期。\n");
        prompt.append("- 校验发现 warn 时主动提醒（月末陷阱/日周 OR 语义/高频幂等）。\n");
        prompt.append("- 红线：不写系统 crontab，只做解析与建议。\n");
        return prompt.toString();
    }
}

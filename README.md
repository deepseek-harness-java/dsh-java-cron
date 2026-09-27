# DSH Cron Forge（定时任务工坊）

> DSH Java Native Plugin 独立 AI 产品 #4：cron 表达式解析、下次触发时间计算、合法性校验、任务登记与去重建议。**只解析与建议，不写系统 crontab。**

## 概览

- **端口**：8095
- **模块**：`dsh-java-cron-app`（Spring Boot 应用）+ `dsh-java-cron-plugin`（Java Native 插件）
- **插件 ID**：`dsh-java-cron`，入口类 `cn.xiaofuge.dsh.cron.plugin.DshCronForgePlugin`
- **工具前缀**：`cron_`（暴露为 `plugin__dsh-java-cron__<tool>`）

## AI 工具（7 个）

| 工具 | 说明 |
|---|---|
| `cron_parse(expr)` | 解析 5 段 cron 表达式，输出人话解释 + 节奏分类 |
| `cron_next_runs(expr, count?)` | 计算下次 N 次触发时间（最多 20 次） |
| `cron_validate(expr)` | 合法性校验：字段范围 / dom-dow OR 语义 / 月末陷阱 / 高频幂等提醒 |
| `cron_register(name, expr, owner?, description?)` | 登记定时任务（内存清单） |
| `cron_suggest()` | 扫描已登记任务，输出同表达式 / 同节奏去重建议 |
| `cron_batch_explain(exprs)` | 批量解析多条表达式 |
| `cron_library()` | 常用节奏库（每分钟 / 工作日 09:00 / 每月 1 号 等） |

## REST API（8095）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/cron/parse` | `{expr}` → human/kind/fields |
| POST | `/api/cron/next` | `{expr,count}` → nextRuns[] |
| POST | `/api/cron/validate` | `{expr}` → valid/issues[]（含 warn） |
| POST/GET | `/api/cron/jobs` | 登记任务 / 任务清单 |
| POST | `/api/cron/suggest` | 去重建议 |
| POST | `/api/cron/batch-explain` | `{exprs[]}` 批量解释 |
| GET | `/api/cron/library` | 常用节奏库 |
| POST | `/api/assistant/stream` | SSE：`{agentId,message}` → 转发 harness `/api/agent/stream` |

## 运行

```bash
# 1. 构建
mvn package -DskipTests

# 2. 启动应用（注意清空 SERVER__PORT 环境变量污染）
SERVER__PORT= java -jar dsh-java-cron-app/target/dsh-java-cron-app-0.1.0-SNAPSHOT.jar --server.port=8095

# 3. 安装并激活插件
cp dsh-java-cron-plugin/target/dsh-java-cron-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-cron-plugin.jar
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-cron","jarName":"dsh-java-cron-plugin.jar",
  "displayName":"CronForge 定时任务工作台","pluginVersion":"0.1.0",
  "runtimeType":"JAVA_NATIVE",
  "sourcePath":"'"$HOME"'/.dsh/standalone/plugins/dsh-java-cron-plugin.jar",
  "entrypoint":"cn.xiaofuge.dsh.cron.plugin.DshCronForgePlugin","installMode":"REPLACE"}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' -d '{"pluginId":"dsh-java-cron"}'
```

## E2E

```bash
python3 cron_forge_e2e.py   # 8/8 PASS
```

覆盖：表达式解析（T0）、下次触发（T1）、月末陷阱 + dom-dow 校验（T2）、非法表达式拒绝（T3）、登记 + 去重建议（T4）、批量解释（T5）、**AI 对话真实调用 cron_parse/cron_next_runs 工具（T6）**、段数错误 400（T7）。

## 安全红线

- **不写系统 crontab**：插件工具只做解析、校验、登记与建议；AI 系统提示词明确约束「不写系统 crontab，只做解析与建议」。用户要求写入 crontab 时，AI 拒绝执行并提供登记方案 + 用户自操作指引。
- 触发时间必须来自 `cron_next_runs` 工具结果，禁止模型心算日期。
- 校验发现 warn 时主动提醒（月末陷阱 / 日周 OR 语义 / 高频幂等）。

## 截图

- `screenshots/01-home.png` — 工作台首页（解析 / 任务 / 去重 / 节奏库）
- `screenshots/02-ai-drawer.png` — AI 对话调用工具回答「下次触发时间」表格
- `screenshots/03-safety.png` — 安全红线：拒绝直写 crontab，给出登记方案

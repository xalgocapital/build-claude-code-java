# s07 · Skill Loading 技能按需加载

## 解决什么问题

Agent 要懂的东西是分领域的：写发布说明有一套体例，处理线上故障有一套顺序，审 SQL 有一份检查清单。这些知识现在只有两个去处——全塞进 system prompt，还没开始干活上下文就满了；或者根本不告诉它，它写出来的东西不合规范。

`s07` 把它们做成**技能**：一个目录一个 `SKILL.md`，启动时只把「名字 + 一句话」放进 system prompt，正文躺在磁盘上。模型判断这次活儿用得上，调 `skill(name)`，正文才作为 tool 结果进上下文。

代码上多了两个 register 和一个 hook 参数——循环还是那个 `while(true)`。

## 相对 s06 的变更

| 组件 | s06 | s07 |
| --- | --- | --- |
| 工具数 | 7 | 8（+ `skill`） |
| 新代码 | — | `Skill` / `SkillLoader` / `SkillRegistry`：目录、加载、预授权 |
| system prompt | 固定常量 | 末尾拼一段目录（`skills.catalogSection()`） |
| 权限 | DENY → 越界写 | DENY → **技能预授权** → 需审批命令 → 越界写 |
| 循环 | 不变 | 不变 |

三处设计决策：

1. **目录常驻、正文按需** —— 目录里每个技能只占一行，正文多长都不影响启动成本。
2. **加载即 tool 结果** —— 正文走 `role=tool` 的消息进上下文，不需要新的消息类型，也不需要改循环。
3. **加载不卸载** —— 正文进来就一直在，直到上下文被压缩。省的是「没用到的那些」，不是「用过的这一个」。

## 技能长什么样

```
skills/
  release-notes/SKILL.md
  hotfix/SKILL.md
  sql-review/SKILL.md
```

每个 `SKILL.md` 上半是 frontmatter，下半是正文：

```markdown
---
name: release-notes
description: 把一批改动整理成面向用户的发布说明。用户要写 release notes、更新日志、版本公告时用。
allowed-tools: read_file, bash(git tag)
---

# 发布说明
...
```

- `name` / `description` 进目录，是模型决定「要不要加载」的唯一依据——所以 description 要写「什么时候用」，不是「这是什么」。
- `allowed-tools` 是**预授权**，不是「能用哪些工具」。它只放宽审批，不收回工具，也越不过硬拒绝表。
- 正文里可以引用 `scripts/`、`references/` 下的附件，那些只在正文提到时才由 `read_file` 去读——第二级按需。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 接口 | OpenAI 兼容：`OPENAI_BASE_URL` / `OPENAI_API_KEY` |
| temperature | 未显式设置（用服务端默认） |
| 技能目录 | `SKILL_DIR` 环境变量，默认 `skills/`（相对工作目录） |
| 固定提示词 | `AgentLoop.SYSTEM` + 运行时拼的技能目录 |
| max_tokens | 8000 |

## 怎么跑

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可换任意兼容端点
mvn -q clean compile
mvn -q exec:java
```

默认任务：`这个版本修了 session 过期和列表分页，帮我写一份发布说明，然后打 v1.0.0 的 tag。`

不花钱跑法（仓库里带了一个固定剧本的假端点，验证机制而非模型质量）：

```sh
python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8907/v1 MODEL_ID=fake-model mvn -q exec:java
```

注意假端点会让 Agent 真的执行 `git tag v1.0.0`（这是「预授权生效」的证据）。跑完记得 `git tag -d v1.0.0`。

## 验证输出（假端点，实际执行）

```
   [skill] 技能目录 3 项：[hotfix, release-notes, sql-review]

→ bash({"command":"git tag v1.0.0"})
   [HOOK] permissionHook: ⚠ 需审批 'git tag'，非交互环境按拒绝处理

→ skill("release-notes")
   [skill] 载入 release-notes：正文 301 chars，预授权 [read_file, bash(git tag)]
<skill name="release-notes">
# 发布说明
...
</skill>

→ bash({"command":"git tag v1.0.0"})
   [HOOK] permissionHook: ✓ 技能预授权 'bash(git tag)'

→ skill("hotfix")
   [skill] 载入 hotfix：正文 271 chars，预授权 [bash, read_file]

→ bash({"command":"sudo shutdown now"})
   [HOOK] permissionHook: ⛔ 命中硬拒绝 'sudo'
```

同一个 `git tag v1.0.0`，加载技能前被拒、加载后被放行；而 `hotfix` 把整个 `bash` 都预授权了，`sudo` 照样被拦。

十四个断言：

```
catalog_in_system=YES        目录在 system prompt 里
catalog_has_all_three=YES    三个技能各占一行
body_not_in_system=YES       正文没进 system prompt
skill_tool_exposed=YES       skill 是分发表第 8 项
unknown_skill_error=YES      未知技能返回 error，不崩
error_lists_available=YES    错误里列出可用技能
needs_approval_denied=YES    没加载技能时 git tag 被拒
body_injected=YES            正文以 <skill> 包裹进 tool 结果
body_full_text=YES           正文完整，不是摘要
body_has_structure=YES       标题层级原样保留
preapproval_allows=YES       预授权命中，命令真的跑了（tag 确实创建了）
second_skill_loaded=YES      第二个技能也能加载
first_body_still_there=YES   第一个技能的正文还在上下文里（加载不卸载）
deny_list_still_wins=YES     预授权 bash 也绕不过硬拒绝
```

## 简化说明

- frontmatter 只解析 `name` / `description` / `allowed-tools` 三个字段，逐行 `key: value`，不引 YAML 库。
- `allowed-tools` 只支持 `tool` 和 `tool(prefix)` 两种写法。真实实现里还有更细的参数匹配和通配符。
- 「需审批」的命令表（`git push` / `git tag` / `npm publish` / `rm ` / `curl` / `ssh `）是简化替身：真实实现会弹窗问人，这里没有交互对象，直接拒绝。
- 只从本地 `skills/` 目录加载。真实 Claude Code 的技能来源更多（插件包、项目级、用户级、内置），还有市场安装。
- 子 Agent 的工具表里没有 `skill`，它继承不到主 Agent 已加载的技能。真实实现里子 Agent 按 `subagent_type` 声明自己能用哪些技能。
- 没有卸载/失效机制。真实实现也没有——靠上下文压缩（下一篇）处理。

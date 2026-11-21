# s03 · Permission：三道权限闸门

## 解决什么问题

s02 的 Agent 有 5 个工具，file 工具过 `safePath()`，但 **bash 完全不受限**。模型一句 `rm -rf /` 就真能删根目录。安全不能靠信任模型，要在工具真正执行之前加一道判断。

这一篇在 s02 的循环上加一道 `PermissionGuard`：工具执行前串过三道闸门，顺序固定——硬拒绝优先，软询问次之，都没命中就放行。

## 三道闸门

| 闸门 | 作用 | 命中结果 |
|---|---|---|
| 1 · 硬拒绝 | 永远禁止的模式（`rm -rf /`、`sudo`、`shutdown`…） | DENY，直接拦下，连问都不问 |
| 2 · 规则匹配 | 写工作区外、危险 bash 命令 | 转 ASK |
| 3 · 用户审批 | 闸门 2 命中后，弹出来问人 | y → 执行，其余 → 跳过 |

三道都没命中 → ALLOW，绝大多数日常操作走这条路。

对应代码：`PermissionGuard.check()` 走闸门 1 和 2，返回 `Verdict(Decision, reason)`；`checkAndApprove()` 补上闸门 3。循环里只加一行 `if (!guard.checkAndApprove(name, args))`，被拒就喂一条 "Permission denied" 回去（不喂的话模型不知道工具没跑，会卡住）。

## 一个诚实点

这里用**字符串包含**做匹配，这不是可靠的安全机制——`rm -fr /`、shell 展开、变量替换都能绕过。这一版要证明的是「闸门插在哪里、顺序怎么走」，不是「字符串匹配有多安全」。真实系统靠多层规则 + 语义判断，文章和图卡的「深入」部分对比了这些差距。

## 实验条件

| 项 | 值 |
|---|---|
| 协议 | OpenAI Chat Completions 兼容 |
| 默认端点 | `https://api.deepseek.com/v1` |
| 默认模型 | `deepseek-chat` |
| temperature | 用服务端默认（代码未显式设置） |
| 固定提示词 | 见 `AgentLoop.SYSTEM` |
| 实验方式 | 真实 API + 本地假端点端到端验证 |

## 运行

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可选，默认就是它
export MODEL_ID=deepseek-chat                         # 可选

cd core/s03-permission
mvn -q compile
mvn -q exec:java -Dexec.args="clean up temporary files in /tmp"
```

闸门 3 会读 stdin：输入 `y` 或 `yes` 放行，其他一律拒绝。非交互环境（读到 EOF）默认拒绝——安全默认。

试这几个 prompt，观察哪条路径被触发：

1. `List files in current directory` → 只读，ALLOW，直接跑
2. `Delete all temp files in /tmp`（bash + rm）→ 闸门 2 命中，ASK
3. `Try sudo shutdown now` → 闸门 1 命中，DENY，不执行
4. `Write a file to /etc/passwd` → 闸门 2 命中（写工作区外），ASK

> 真实 API 会产生费用。闸门 1 的字符串匹配可被绕过，请在临时目录运行，别拿它当真的安全边界。

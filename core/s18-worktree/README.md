# s18 · Worktree Isolation

s15 到 s17，成员手里一直没有写工具。`Teammate` 的注释里写得很直白：

```java
// 两个读工具 + 认领 + 发信 + 移交。没有 bash / write_file / team_spawn
BuiltinTools.installReadOnly(registry);
```

这不是抠门，是那一篇没法解的问题。两个成员同时改 `notes/plan.md`，
后写的盖掉先写的，`git status` 里只有一个 `M`，没有任何报错——你甚至不知道发生过。

这一篇只做一件事：让每个成员有一棵自己的树，写只落在自己那棵上，合不合由 lead 决定。

## 相对 s17 的改动

| 文件 | 改了什么 |
|---|---|
| `Worktree.java` | 新增。沙箱 / create / list / remove / merge，全在这里 |
| `BuiltinTools.java` | 每个工具多一个 `base` 目录参数。`install(reg, base)` 把 5 个工具钉在指定目录 |
| `Teammate.java` | 成员 cwd 绑到自己的 worktree；工具表从只读换成全套 5 个 |
| `Team.java` | spawn 时先开树，开不出来就不许上岗；status 里带树路径 |
| `AgentLoop.java` | 注册 `worktree_list` / `worktree_merge` / `worktree_remove`；system 里多一段树的账 |

s17 的自主驱动、s16 的信封、s15 的收件箱一行没删。这一篇没有碰循环。

## create：一棵树，一条分支

```java
git worktree add -b agent/<name> /tmp/s18-sandbox/.worktrees/<name>
```

`git worktree` 是 git 自带的：同一个 `.git`，多一份工作区，多一个 HEAD。
两个成员各自 `checkout` 在自己的分支上，`notes/plan.md` 在磁盘上是两个文件，
互不影响——不需要拷贝仓库，也不需要额外写隔离逻辑，这是 git 已经做完的事。

沙箱由 `Worktree.ensureRepo()` 在启动时建：`git init`、写两个文件、提交一次。
**那一次提交不能省**。`git worktree add` 从 HEAD 长出来，仓库里一个提交都没有时 git 会
`inferring '--orphan'`，给出来的是一棵只剩 `.git` 的空目录（`No commits yet on probe`）。

`.worktrees/` 必须进 `.gitignore`。它在主树目录里面，不忽略的话主树永远是脏的，
「主树没人动过」这句话就没法用 `git status` 证明了。这一条不写，整套实验的断言等于白做。

## 工具绑 cwd，不是提示词里嘱咐

s02 起这些工具的实现里写的是 `Path.of(".")`——JVM 的**进程**工作目录。
所有人共用一份，改不了。成员要各写各的树，就得把这个「当前目录」从全局状态变成参数：

```java
public static void install(ToolRegistry reg, Path base) {
    registerBash(reg, base);
    registerReadFile(reg, base);
    registerWriteFile(reg, base);
    registerEditFile(reg, base);
    registerGlob(reg, base);
}
```

`Teammate` 的构造函数里那一行才是机制：

```java
this.cwd = team.worktree().cwdFor(name);
BuiltinTools.install(registry, cwd);
```

`runBash` 用 `pb.directory(base)`，`safePath` 把相对路径钉死在 `base` 里。
成员的 `../` 越不出去，所以它连主树的路径都拼不出来。

写进提示词（「你只能改 .worktrees/alice 下的文件」）只是建议：模型手一滑写 `notes/plan.md`，
落的还是主树，而且没人报错。**一句话写在提示词里，和一行代码写在 `pb.directory` 上，
是两件不同的事。**

实测里每个成员先跑一次 `pwd`：

```
[team:alice] → bash("pwd")
[team:alice]   /private/tmp/s18-sandbox/.worktrees/alice
[team:bob] → bash("pwd")
[team:bob]   /private/tmp/s18-sandbox/.worktrees/bob
```

## merge：先提交，再合

`worktree_merge(name)` 里面有三步，第一步最容易被漏：

```java
git(dir, "add", "-A");
git(dir, "commit", "-m", "agent/" + name + "：N 个文件");
git(main, "merge", "--no-ff", "agent/" + name);
```

worktree 只共享 `.git/objects`，不共享工作区。一个成员在树里改了文件但从没 commit，
那条分支还停在基线提交上，`git merge` 会告诉你「Already up to date」——主树一个字节没变，
也不报错。这是这一篇里最像 s15 那个毛病的地方：**看起来合了，其实什么都没发生。**

所以提交写进 `merge` 里面，不指望模型记得。

成功之后清场：`git worktree remove --force` 加 `git branch -D`。树和分支都不留，
`list()` 少一条。实测 3 条（主树 + 两棵树）→ 合完 alice 剩 2 条 → 树和分支一起消失。

## 冲突：报出来，然后 abort

```java
Result m = git(main, "merge", "--no-ff", "-m", "merge " + PREFIX + name, PREFIX + name);
if (m.code != 0) {
    Result u = git(main, "diff", "--name-only", "--diff-filter=U");
    ...
    git(main, "merge", "--abort");
    return "(error: 合并 " + PREFIX + name + " 冲突，没合进去。主树已回到合并前。\n"
            + conflicts + "\n冲突文件：" + String.join(",", files) + ...);
}
```

两件事同时做，缺一件都不对。

不 abort，主树里躺着 `<<<<<<< HEAD`，下一次 `read_file` 读到的是冲突标记。
那不叫隔离，那叫把问题搬到主树上。这一篇的承诺是「主树在合并被拒之后仍是合并前那一版」，
所以必须 abort。

不返回错误，模型只知道「工具调完了」。它下一轮会以为 bob 的改动已经在主树里，
继续在错误的前提上干活。所以 `CONFLICT` 那几行原样回给它，一个字都不翻译：

```
[worktree] merge bob：冲突，git 说 CONFLICT (content): Merge conflict in notes/plan.md
[worktree] merge bob：冲突文件 notes/plan.md；已 abort，主树回到 4178ac9；worktree 和分支留着
```

`LC_ALL=C` 是为了这个：中文 locale 下 git 会把 `CONFLICT` 翻成别的词，
断言抓不到，读到日志的人也抓不到。git 的英文输出在这里是接口的一部分。

## list 读 git 的记录，不扫目录

```java
git(main, "worktree", "list", "--porcelain")
```

扫 `.worktrees/` 也能得到名字，但目录被手删了、git 还记着的那种半截状态，扫目录看不见。
这一篇的 `list()` 只认 git 自己那份记录。主树那一行要排掉——macOS 上 `/tmp` 是
`/private/tmp` 的软链，字符串比对会对不上，所以用 `Files.isSameFile`。

## 实测：13 次 POST，一棵树合进去，一棵被拦下

剧本：沙箱里 `notes/plan.md` 四行，alice 和 bob 各改**第二行**，其余三行逐字不动。
18 项断言全 YES。

```
SCENARIO=main POSTS=13
sandbox_repo_initialized=YES      沙箱里有仓库，HEAD 3 个提交
seed_plan_tracked=YES             notes/plan.md 被 git 跟踪
two_worktrees_on_disk=YES         .worktrees/ 下 alice、bob 两个目录，worktree list 3 条
worktree_list_reports_two=YES     工具回「worktree 2 个」
agent_branches_created=YES        agent/alice、agent/bob
worktrees_gitignored=YES          .worktrees/ 被 ignore，主树 git status 为空
member_tools_writable=YES         alice 的工具表 8 个：bash,read_file,write_file,edit_file,glob,task_update,send_message,task_handoff
member_tools_include_bash=YES     bash 在，所以「在哪个目录跑」必须有答案
cwd_bound_to_worktree=YES         两人 pwd 分别是 .../.worktrees/alice 和 .../bob
main_tree_untouched_while_editing=YES  两人都写完那一刻，主树逐字节等于种子，diff 空
same_line_diverges=YES            两棵树只差第二行，其余三行逐字相同
merge_alice_takes_main=YES        合完 alice，主树第二行变成「先核对版本号」
merge_cleans_worktree=YES         list 剩 1 条，.worktrees/alice 已经不在
conflict_captured=YES             CONFLICT (content): Merge conflict in notes/plan.md
conflict_reaches_lead=YES         下一轮 lead 的上下文里就有这段错误
main_survives_conflict=YES        主树还是 alice 那版，没有未合并文件
merge_unknown_rejected=YES        合 carol：没有 carol 的 worktree
script_finished=YES               13 次 POST：lead 7、member 6
```

主树在两个人改完之后的样子（`git diff` 输出为空，`git status` 也为空）：

```
$ git -C /tmp/s18-sandbox status --porcelain
$ git -C /tmp/s18-sandbox diff
$
```

同一时刻两棵树里的第二行：

```
alice  - 步骤 1：准备（先核对版本号）      45 字符
bob    - 步骤 1：准备（先备份 orders 表）  51 字符
```

三个数字记住就够了：**主树 0 处改动，两棵树各改 1 行，冲突 1 个文件。**

## 反例：不隔离，后写的静默盖掉先写的

`WORKTREE_MODE=naive` 只做一件事：spawn 的时候不开树，成员的 cwd 是主树。

```
SCENARIO=naive POSTS=7
naive_no_worktree=YES        一棵树都没开，worktree list 只有主树
naive_cwd_is_main=YES        两个人的 pwd 都是 /tmp/s18-sandbox
naive_second_write_wins=YES  主树最后是 bob 那版，「先核对版本号」一个字没剩
naive_git_status_one_file=YES git status 只有 1 条：M notes/plan.md
naive_no_error_anywhere=YES  整份日志没有一条报错，两次 write_file 都回了 Wrote
naive_script_finished=YES    7 次 POST：lead 3、member 4
```

| | main（worktree） | naive（共写主树） |
|---|---|---|
| 树 | 2 棵 | 0 棵 |
| 写完之后主树 | 逐字节等于种子 | 变成 bob 那版 |
| 两个人的改动 | 各自留在自己树里 | alice 的没了 |
| `git status` | 空 | 1 条 `M` |
| 合并 A | 成功，主树变 A | 没有这一步 |
| 合并 B | 冲突，报 CONFLICT，主树不动 | 没有这一步，因为没有「合」 |
| 报错 | 1 条（明确的冲突） | 0 条 |

naive 那一遍里，alice 先写，`Wrote 45 chars`；bob 后写，`Wrote 51 chars`。
两条都返回成功。等你看 `notes/plan.md` 的时候，alice 那 45 个字符已经不在了。

s15 把成员的写权限封掉，封的就是这个。

## 这一篇没解决的

- **冲突只是报出来，不会自己解**。`worktree_merge` 返回错误之后，要么人去解，要么丢掉一棵树。
  让模型自己解冲突（读冲突标记、决定留哪边）是下一步，这一篇没做。
- **合并是串行的，而且只有 lead 能合**。两个人同时调 `worktree_merge` 会在主树上撞车，
  这一篇靠「只有 lead 有这个工具」避开，没有加锁。
- **树开在 `/tmp` 里**。进程重启后 `/tmp` 被清掉，`git worktree list` 里会留下一堆
  「prunable」的半截记录，需要 `git worktree prune`。这一篇没处理。
- **一个成员只有一棵树**。它想同时试两种改法，得开两次 spawn。
- **不隔离 `.tasks/` 和 `.team/`**。任务板和收件箱仍是共享的、写在模块目录里，
  只有代码文件被隔离了。
- **提交用的是机器上的全局 git 身份**。没配 `user.email` 的机器上，`merge` 会在 commit 那一步
  失败，错误回给模型，主树不会被合坏，但这一版也合不进去。
- **沙箱不是真仓库**。真实项目里 `git worktree add` 还要考虑未跟踪文件、submodule、LFS，
  这一篇的仓库只有两个文件。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 沙箱 | `SANDBOX_DIR` 默认 `/tmp/s18-sandbox` |
| 反例开关 | `WORKTREE_MODE`：checked（默认）/ naive |
| 分支前缀 | `agent/<name>` |
| 树目录 | `<沙箱>/.worktrees/<name>` |
| git | 2.54.0（`LC_ALL=C`，冲突输出保持英文） |
| 沙箱内容 | `notes/plan.md`（4 行）、`README.md`、`.gitignore`（`.worktrees/`） |
| 成员 | alice、bob，各 3 步：`bash pwd` → `write_file notes/plan.md` → 一句结论 |
| 剧本（main） | 13 次 POST（lead 7、member 6） |
| 剧本（naive） | 7 次 POST（lead 3、member 4） |

`.tasks/`、`.team/`、`memory/` 都在 `.gitignore` 里，由假端点在启动时造出来。
沙箱整个落在 `/tmp` 下，`fake-endpoint` 每次启动先删掉重建；仓库里不留任何测试产物。

## 跑一遍

两遍互不依赖，各自重置沙箱：断言只读自己那一次的快照，不拿另一遍做对比。

```sh
SCENARIO=main python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8918/v1 \
  MODEL_ID=fake mvn -q exec:java > /tmp/s18_stdout.log 2>&1
cat /tmp/s18_assert.txt                            # 18 项全 YES

SCENARIO=naive python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8918/v1 \
  MODEL_ID=fake WORKTREE_MODE=naive mvn -q exec:java > /tmp/s18_stdout.log 2>&1
cat /tmp/s18_assert_naive.txt                      # 6 项全 YES
```

每一遍都必须是「先起端点、再起 JVM」：端点要在第一次请求之前把沙箱整个删掉，
否则上一遍的 `agent/*` 分支和 `.worktrees/` 还在，`worktree add` 会直接报「already exists」。

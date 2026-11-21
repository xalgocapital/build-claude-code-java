package com.xalgocapital.claude.s18;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * s18：工作树隔离。
 *
 * s15 起成员的写权限一直封着，理由写在 BuiltinTools.installReadOnly 的注释里：
 * 两个人同时改同一个文件，后写的盖掉先写的，而且没有任何报错。那一篇没有能力解这件事，
 * 只能把风险挡在门外。这一篇把它解开——给每个成员一棵自己的 tree。
 *
 * 四个动作，都靠 git 自己的命令：
 *   create(name)  git worktree add -b agent/&lt;name&gt; .worktrees/&lt;name&gt;
 *   list()        git worktree list --porcelain，读 git 的记录，不扫目录
 *   merge(name)   先在它树里 commit，再在主树 git merge --no-ff agent/&lt;name&gt;
 *   remove(name)  git worktree remove + git branch -D
 *
 * 为什么 merge 之前要 commit：worktree 只共享 .git/objects，不共享工作区。
 * 一个成员在树里改了三个文件但从没 commit，merge 那条分支等于合了个空——主树毫无变化，
 * 也没有报错。这是最容易被忽略的一步，所以它写在 merge 里面，不指望模型记得。
 *
 * 冲突不吞。git merge 失败时把 CONFLICT 那几行和冲突文件名原样返回，然后 abort，
 * 让主树退回到合并前。abort 是为了「主树不能被合坏」，返回错误是为了「模型得知道没合进去」。
 * 两件事都要做，少一件都不对。
 */
public final class Worktree {
    /** 沙箱。默认 /tmp/s18-sandbox——这里的所有命令都会写盘、建分支，不能放在仓库里跑 */
    public static final Path SANDBOX = Path.of(
            System.getenv().getOrDefault("SANDBOX_DIR", "/tmp/s18-sandbox")).toAbsolutePath().normalize();
    /** 反例开关：naive = 不用 worktree，成员都写在主树上 */
    public static final boolean NAIVE =
            "naive".equals(System.getenv().getOrDefault("WORKTREE_MODE", "checked").strip());
    private static final String PREFIX = "agent/";

    /** 主树里那份种子计划。两个成员改的就是第二行 */
    private static final String SEED_PLAN = """
            # 发布计划
            - 步骤 1：准备
            - 步骤 2：灰度
            - 步骤 3：全量
            """;
    private static final String SEED_README = """
            # 沙箱仓库
            两个文件：notes/plan.md 是计划，README.md 是说明。
            """;

    private final Path main;
    private final Path trees;

    public Worktree() {
        this.main = SANDBOX;
        this.trees = main.resolve(".worktrees");
    }

    public Path main() {
        return main;
    }

    public Path dir(String name) {
        return trees.resolve(name);
    }

    /** 成员干活的地方。naive 模式下所有人共用主树——那正是这一篇要证明会出事的那种写法 */
    public Path cwdFor(String name) {
        return NAIVE ? main : dir(name);
    }

    public boolean exists(String name) {
        return Files.isDirectory(dir(name));
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Entry e : list()) {
            out.add(e.name());
        }
        return out;
    }

    /**
     * 准备沙箱：没有仓库就 init 一个，写两个文件，提交一次。
     *
     * .worktrees/ 必须进 .gitignore。它在主树目录里面，不忽略的话主树永远是脏的，
     * 「主树没人动过」这句话就没法用 git status 证明了。
     */
    public String ensureRepo() {
        try {
            Files.createDirectories(main);
            if (!Files.isDirectory(main.resolve(".git"))) {
                Result init = git(main, "init", "-q", "-b", "main");
                if (init.code != 0) {
                    return "(error: git init 失败：" + init.out + ")";
                }
                Files.writeString(main.resolve(".gitignore"), ".worktrees/\n");
                Files.createDirectories(main.resolve("notes"));
                Files.writeString(main.resolve("notes/plan.md"), SEED_PLAN);
                Files.writeString(main.resolve("README.md"), SEED_README);
                git(main, "add", "-A");
                Result c = git(main, "commit", "-q", "-m", "seed: notes/plan.md + README.md");
                if (c.code != 0) {
                    return "(error: 种子的第一次提交失败：" + c.out + ")";
                }
                return "沙箱 " + main + " 新建，1 次提交，2 个文件";
            }
            return "沙箱 " + main + " 已存在，HEAD " + git(main, "rev-parse", "--short", "HEAD").out;
        } catch (IOException e) {
            return "(error: 沙箱建不起来：" + e.getMessage() + ")";
        }
    }

    /** 起一棵树。分支名 agent/&lt;name&gt;，目录 .worktrees/&lt;name&gt; */
    public String create(String name) {
        if (name == null || name.isBlank()) {
            return "(error: worktree 要有名字)";
        }
        if (exists(name)) {
            return "(error: " + name + " 已经有树了：" + dir(name) + ")";
        }
        try {
            Files.createDirectories(trees);
        } catch (IOException e) {
            return "(error: 建不了 " + trees + "：" + e.getMessage() + ")";
        }
        Result r = git(main, "worktree", "add", "-b", PREFIX + name, dir(name).toString());
        if (r.code != 0) {
            return "(error: git worktree add 失败：" + r.out + ")";
        }
        System.out.println("   [worktree] create " + name + " → " + dir(name) + "（分支 " + PREFIX + name + "）");
        return "Created worktree " + name + " at " + dir(name) + "（分支 " + PREFIX + name + "）";
    }

    /**
     * 列。读的是 git worktree list --porcelain，不是扫 .worktrees 目录。
     * 目录被手删了但 git 还记着的那种半截状态，扫目录看不见，git 看得见。
     */
    public List<Entry> list() {
        Result r = git(main, "worktree", "list", "--porcelain");
        List<Entry> out = new ArrayList<>();
        if (r.code != 0) {
            return out;
        }
        String path = null;
        String branch = "";
        for (String line : r.out.split("\n")) {
            if (line.startsWith("worktree ")) {
                path = line.substring("worktree ".length()).strip();
                branch = "";
            } else if (line.startsWith("branch ")) {
                branch = line.substring("branch ".length()).strip().replace("refs/heads/", "");
            } else if (line.isBlank() && path != null) {
                addEntry(out, path, branch);
                path = null;
            }
        }
        if (path != null) {
            addEntry(out, path, branch);
        }
        return out;
    }

    private void addEntry(List<Entry> out, String path, String branch) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        // 主树不算。macOS 上 /tmp 是 /private/tmp 的软链，两边字符串对不上，
        // 所以比 isSameFile 而不是比字符串——不然主树会被当成一棵树列出来
        if (sameFile(p, main)) {
            return;
        }
        out.add(new Entry(p.getFileName().toString(), p, branch));
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.equals(b);
        }
    }

    public String listLine() {
        List<Entry> all = list();
        if (all.isEmpty()) {
            return "worktree 0 个";
        }
        StringBuilder sb = new StringBuilder("worktree " + all.size() + " 个：");
        for (Entry e : all) {
            sb.append("\n  ").append(e.name()).append("  ").append(e.path())
                    .append("  [").append(e.branch()).append("]");
        }
        return sb.toString();
    }

    public String printList() {
        List<Entry> all = list();
        System.out.println("   [worktree] list：" + all.size() + " 个 worktree");
        for (Entry e : all) {
            System.out.println("   [worktree]   " + e.name() + "  " + e.path() + "  [" + e.branch() + "]");
        }
        return listLine();
    }

    /**
     * 合回主树。三步：树里提交 → 主树 merge --no-ff → 成功就删掉树和分支。
     *
     * 冲突时先记下 git 自己给的 CONFLICT 行和未合并文件，再 abort。
     * 不 abort 的话主树里躺着冲突标记，下一次 read_file 读到的是「&lt;&lt;&lt;&lt;&lt;&lt;&lt; HEAD」——
     * 那不是「隔离」，那是把问题搬到了主树上。
     */
    public String merge(String name) {
        if (!exists(name)) {
            return "(error: 没有 " + name + " 的 worktree，先 list 看看谁还在)";
        }
        Path dir = dir(name);

        Result st = git(dir, "status", "--porcelain");
        long changed = st.out.isBlank() ? 0 : st.out.lines().count();
        if (changed > 0) {
            git(dir, "add", "-A");
            Result c = git(dir, "commit", "-q", "-m", PREFIX + name + "：" + changed + " 个文件");
            if (c.code != 0) {
                return "(error: " + name + " 的改动提交不上：" + c.out + ")";
            }
            System.out.println("   [worktree] merge " + name + "：" + changed + " 个文件先提交到 "
                    + PREFIX + name);
        } else {
            System.out.println("   [worktree] merge " + name + "：树里没有改动，分支等于基线");
        }

        String before = git(main, "rev-parse", "HEAD").out;
        Result m = git(main, "merge", "--no-ff", "-m", "merge " + PREFIX + name, PREFIX + name);
        if (m.code != 0) {
            Result u = git(main, "diff", "--name-only", "--diff-filter=U");
            List<String> files = new ArrayList<>();
            for (String line : u.out.split("\n")) {
                if (!line.isBlank()) {
                    files.add(line.strip());
                }
            }
            StringBuilder conflicts = new StringBuilder();
            for (String line : m.out.split("\n")) {
                if (line.startsWith("CONFLICT")) {
                    if (conflicts.length() > 0) {
                        conflicts.append('\n');
                    }
                    conflicts.append(line.strip());
                }
            }
            git(main, "merge", "--abort");
            System.out.println("   [worktree] merge " + name + "：冲突，git 说 " + conflicts);
            System.out.println("   [worktree] merge " + name + "：冲突文件 " + String.join(",", files)
                    + "；已 abort，主树回到 " + before.substring(0, Math.min(7, before.length())) + "；"
                    + "worktree 和分支留着");
            return "(error: 合并 " + PREFIX + name + " 冲突，没合进去。主树已回到合并前。\n"
                    + conflicts + "\n冲突文件：" + String.join(",", files)
                    + "\n" + name + " 的 worktree 和分支都留着：让它自己解决后再 worktree_merge 一次，"
                    + "或者你决定用谁的就 worktree_remove 掉另一棵)";
        }

        int files = 0;
        Result d = git(main, "diff", "--name-only", before, "HEAD");
        for (String line : d.out.split("\n")) {
            if (!line.isBlank()) {
                files++;
            }
        }
        System.out.println("   [worktree] merge " + name + "：git merge --no-ff 成功，主树 " + files
                + " 个文件变了");

        remove(name);
        int left = list().size();
        System.out.println("   [worktree] merge " + name + "：worktree 和分支都清理了；list 剩 "
                + left + " 个");
        return "Merged " + PREFIX + name + "：主树 " + files + " 个文件变了，现在是它那版的。"
                + "worktree 和分支已清理，list 剩 " + left + " 个。";
    }

    /** 丢掉一棵树。合不进去、又不打算用它的时候走这条 */
    public String remove(String name) {
        if (!exists(name)) {
            return "(error: 没有 " + name + " 的 worktree)";
        }
        git(main, "worktree", "remove", "--force", dir(name).toString());
        git(main, "branch", "-D", PREFIX + name);
        System.out.println("   [worktree] remove " + name + "：树和分支都删了，list 剩 " + list().size() + " 个");
        return "Removed worktree " + name + "，list 剩 " + list().size() + " 个。";
    }

    /** 主树的账。写进 system prompt 和 team_status，让 lead 每轮都看得见谁还没合 */
    public String statusLine() {
        List<Entry> all = list();
        String head = git(main, "rev-parse", "--short", "HEAD").out;
        String dirty = git(main, "status", "--porcelain").out;
        return "Worktrees " + all.size() + " 个（沙箱 " + main + "，主树 HEAD " + head + "，"
                + (dirty.isBlank() ? "工作区干净" : "工作区有 " + dirty.lines().count() + " 处未提交")
                + "）"
                + (all.isEmpty() ? "。" : "：" + String.join("、", all.stream().map(Entry::name).toList()));
    }

    /** git 一条命令。LC_ALL=C 不是为了好看：中文 locale 下 git 会翻译 CONFLICT 那几行，断言就抓不到了 */
    private Result git(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("LC_ALL", "C");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(124, "(git 60s 没结束)");
            }
            return new Result(p.exitValue(), out.strip());
        } catch (Exception e) {
            return new Result(127, "(git 起不来：" + e.getMessage() + ")");
        }
    }

    public record Entry(String name, Path path, String branch) {
    }

    private record Result(int code, String out) {
    }
}

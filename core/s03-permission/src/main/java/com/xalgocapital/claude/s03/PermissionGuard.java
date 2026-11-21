package com.xalgocapital.claude.s03;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * s03 的全部新东西：工具执行前的三道闸门。
 *
 * s02 的循环对模型报上来的工具照单全收、直接执行。bash 不受 safePath 约束，
 * 模型一句 rm -rf / 就真能删根目录。安全不能靠信任模型，要在执行前加一道判断。
 *
 * 三道闸门，顺序固定：
 *   闸门1 硬拒绝表——命中直接 DENY，连问都不问
 *   闸门2 规则匹配——命中转 ASK，交给闸门3
 *   闸门3 用户审批——弹出来问人，y 放行、其余拒绝
 * 三道都没命中 = ALLOW，绝大多数日常操作走这条路。
 *
 * 一个刻意保留的诚实点：这里用字符串包含做匹配，这不是可靠的安全机制——
 * 命令变体（rm -fr /）、shell 展开、变量替换都能绕过。真实系统靠多层规则 +
 * 语义判断，那部分写在文章和配套图卡里。这一版要证明的是「闸门插在哪里、
 * 顺序怎么走」，不是「字符串匹配有多安全」。
 */
public class PermissionGuard {

    /** 三种决策 */
    public enum Decision { ALLOW, DENY, ASK }

    /** 决策 + 命中原因（ALLOW 时 reason 为 null） */
    public record Verdict(Decision decision, String reason) {}

    /** 闸门1：永远禁止的硬模式 */
    private static final String[] DENY_LIST = {
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"
    };

    /** 闸门2：命中就问用户的 bash 关键词 */
    private static final String[] ASK_BASH_KEYWORDS = {"rm ", "> /etc/", "chmod 777"};

    /**
     * 纯判断，不阻塞。串起闸门1和闸门2，返回决策。
     * 闸门3（问用户）留给 approve()，这样判断逻辑可以单独测。
     */
    public Verdict check(String toolName, JsonNode args) {
        // 闸门1：硬拒绝，优先于一切
        if ("bash".equals(toolName)) {
            String cmd = args.path("command").asText("");
            for (String pattern : DENY_LIST) {
                if (cmd.contains(pattern)) {
                    return new Verdict(Decision.DENY, "命中硬拒绝规则: '" + pattern + "'");
                }
            }
        }

        // 闸门2：规则匹配
        String reason = ruleReason(toolName, args);
        if (reason != null) {
            return new Verdict(Decision.ASK, reason);
        }

        // 三道都没命中 → 放行
        return new Verdict(Decision.ALLOW, null);
    }

    /**
     * 闸门2 的规则集合：描述「什么时候需要问用户」。
     * 两条规则：写工作区外、危险 bash 命令。
     */
    private String ruleReason(String toolName, JsonNode args) {
        // 规则A：写/改工作区外的文件
        if ("write_file".equals(toolName) || "edit_file".equals(toolName)) {
            String path = args.path("path").asText("");
            if (escapesWorkspace(path)) {
                return "写工作区之外的文件: " + path;
            }
        }
        // 规则B：可能破坏本地文件的 bash 命令
        if ("bash".equals(toolName)) {
            String cmd = args.path("command").asText("");
            for (String kw : ASK_BASH_KEYWORDS) {
                if (cmd.contains(kw)) {
                    return "可能有破坏性的命令: " + cmd;
                }
            }
        }
        return null;
    }

    /**
     * check + 闸门3。循环里只insert这一行：if (!guard.checkAndApprove(...))。
     * 非交互环境（stdin 读到 EOF）一律拒绝——安全默认。
     */
    public boolean checkAndApprove(String toolName, JsonNode args) {
        Verdict v = check(toolName, args);
        switch (v.decision()) {
            case ALLOW -> {
                return true;
            }
            case DENY -> {
                System.out.println("   ⛔ 拒绝: " + v.reason());
                return false;
            }
            case ASK -> {
                return askUser(toolName, args, v.reason());
            }
        }
        return false;
    }

    /** 闸门3：把决策交给用户。只有 y / yes 放行。 */
    private boolean askUser(String toolName, JsonNode args, String reason) {
        System.out.println("   ⚠  " + reason);
        System.out.println("   Tool: " + toolName + "(" + args + ")");
        System.out.print("   Allow? [y/N] ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String answer = reader.readLine();
            boolean approve = answer != null
                    && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"));
            System.out.println(approve ? "   → 用户批准，执行" : "   → 用户拒绝，跳过");
            return approve;
        } catch (Exception e) {
            System.out.println("   → 无法读取用户输入，按拒绝处理");
            return false;
        }
    }

    /** 路径是否越出工作目录（和 file 工具的 safePath 同一套判定） */
    private boolean escapesWorkspace(String rel) {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path p = root.resolve(rel).normalize();
        return !p.startsWith(root);
    }
}

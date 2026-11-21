package com.xalgocapital.claude.s05;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/**
 * 5 个内置 hook 回调。重点是看它们怎么各自挂到一个事件上——
 * 每个 hook 只干一件事，彼此不知道对方存在，也不认识循环。
 *
 * 其中 permissionHook 就是 s03 的 PermissionGuard 逻辑原样搬过来：
 * s03 它是循环里硬编码的一道门，s04 它只是一个注册在 PreToolUse 上的回调。
 */
public final class BuiltinHooks {
    private BuiltinHooks() {}

    /** 硬拒绝模式（s03 闸门1，原样搬来） */
    private static final String[] DENY_LIST = {
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"
    };

    /** UserPromptSubmit：往每轮输入注入工作目录信息（精简版只打印日志） */
    public static Hook contextInjectHook() {
        return event -> {
            System.out.println("   [HOOK] UserPromptSubmit: working in " + Path.of(".").toAbsolutePath());
            return null;  // null = 不改输入，放行
        };
    }

    /** PreToolUse：权限检查（s03 的逻辑从循环移到 hook） */
    public static Hook permissionHook() {
        return event -> {
            String tool = event.toolName();
            JsonNode args = event.args();
            if ("bash".equals(tool)) {
                String cmd = args.path("command").asText("");
                for (String pattern : DENY_LIST) {
                    if (cmd.contains(pattern)) {
                        System.out.println("   [HOOK] permissionHook: ⛔ 命中硬拒绝 '" + pattern + "'");
                        return "Permission denied by hook deny list";
                    }
                }
            }
            if ("write_file".equals(tool) || "edit_file".equals(tool)) {
                String path = args.path("path").asText("");
                if (escapesWorkspace(path)) {
                    System.out.println("   [HOOK] permissionHook: ⚠ 越界写 '" + path + "'，非交互环境按拒绝处理");
                    return "Permission denied by hook: path escapes workspace";
                }
            }
            return null;  // 放行
        };
    }

    /** PreToolUse：日志。和权限 hook 挂同一个事件，但彼此不知道对方 */
    public static Hook logHook() {
        return event -> {
            System.out.println("   [HOOK] PreToolUse: " + event.toolName() + "()");
            return null;
        };
    }

    /** PostToolUse：大输出提醒 */
    public static Hook largeOutputHook() {
        return event -> {
            String out = event.output() == null ? "" : event.output();
            if (out.length() > 100_000) {
                System.out.println("   [HOOK] PostToolUse: ⚠ 大输出 " + out.length() + " chars from "
                        + event.toolName());
            }
            return null;
        };
    }

    /** Stop：退出前打印本会话工具调用统计 */
    public static Hook summaryHook() {
        return event -> {
            System.out.println("   [HOOK] Stop: 本会话共 " + event.toolCount() + " 次工具调用");
            return null;  // null = 允许退出；返回字符串 = 强制续跑
        };
    }

    /** 路径是否越出工作目录（和 file 工具的 safePath 同一套判定） */
    private static boolean escapesWorkspace(String rel) {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path p = root.resolve(rel).normalize();
        return !p.startsWith(root);
    }
}

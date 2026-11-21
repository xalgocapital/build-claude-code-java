package dev.lcc.s19;

import java.util.List;

/**
 * s07 的第一个新东西：一个技能的四块内容。
 *
 * 一个 SKILL.md 拆成两半：
 *   上半（frontmatter）name / description / allowed-tools —— 启动时就要用，进目录
 *   下半（正文）整段 markdown —— 调用时才用，进上下文
 *
 * 这个切的不是文件，是「什么时候花 token」。目录一直在，正文按需。
 *
 * allowed-tools 不是「这个技能能用哪些工具」，而是「用这个技能时，哪些工具免审批」。
 * 差别很重要：它只放宽审批，不收回工具，也越不过硬拒绝表。
 */
public record Skill(
        String name,                 // 唯一标识，模型按这个名字加载
        String description,          // 一句话说明「什么时候用它」，目录里就这一行
        List<String> allowedTools,   // 预授权规则，如 read_file、bash(git tag)
        String body                  // 正文，加载时原样塞回上下文
) {}

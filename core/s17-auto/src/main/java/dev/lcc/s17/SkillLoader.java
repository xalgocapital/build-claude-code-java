package dev.lcc.s17;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从磁盘读技能：skills/&lt;名字&gt;/SKILL.md。
 *
 * 一个技能就是一个目录，目录里至少有一个 SKILL.md。正文随你写多长，
 * 里面还可以放 scripts/、references/ 之类的附件——那些只在正文里被提到时
 * 才由 read_file 去读，启动阶段一个字都不进上下文。
 *
 * frontmatter 用最简单的「key: value」逐行解析，不引 YAML 库：
 * 三个字段而已，多一个依赖不划算。解析失败就跳过这个技能并打印原因，
 * 不让一个写错的技能拖垮整个启动过程。
 */
public final class SkillLoader {
    private SkillLoader() {}

    /** 扫技能根目录下的每个子目录。目录不存在就返回空列表，不报错。 */
    public static List<Skill> loadAll(Path root) {
        List<Skill> skills = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return skills;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    skills.add(parse(file));
                } catch (Exception e) {
                    System.out.println("   [skill] 跳过 " + dir.getFileName() + "：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("   [skill] 读目录失败：" + e.getMessage());
        }
        return skills;
    }

    /** 解析一个 SKILL.md：--- 之间是 frontmatter，之后全是正文。 */
    static Skill parse(Path file) throws IOException {
        String text = Files.readString(file).replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("缺少 frontmatter");
        }
        int headEnd = text.indexOf("\n---", 3);
        if (headEnd < 0) {
            throw new IllegalArgumentException("frontmatter 没有闭合");
        }
        String head = text.substring(4, headEnd);
        String body = text.substring(text.indexOf('\n', headEnd + 1) + 1).strip();

        String name = null;
        String description = null;
        List<String> allowedTools = List.of();
        for (String line : head.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "allowed-tools" -> allowedTools = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                default -> { }   // 其他字段（version、license…）本篇用不上，忽略
            }
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("frontmatter 缺 name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("frontmatter 缺 description");
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("正文是空的");
        }
        return new Skill(name, description, allowedTools, body);
    }
}

package dev.lcc.s18;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能注册表。启动时把目录挂进 system prompt，运行时按名字取正文。
 *
 * 这是本篇全部的新机制，一共三件事：
 *   1. catalog()        目录：每个技能只贡献「名字 + 一句话」，几十个 token
 *   2. load(name)       加载：正文作为 tool 结果进上下文，模型才看得到全文
 *   3. grantedBy(...)   预授权：加载过的技能可以让自己声明的工具免审批
 *
 * 第 3 件是很多人忽略的一点：技能不只是「一段文档」，它还能带权限。
 * 但只能放宽审批，不能收回工具，也越不过硬拒绝表——顺序在 permissionHook 里。
 */
public class SkillRegistry {
    /** 工具描述：告诉模型「有这个目录，但正文不在上下文里，要用就调我」 */
    public static final String DESCRIPTION =
            "Load a skill before doing the kind of work it covers. "
            + "The catalog in the system prompt lists each skill's name and when to use it; "
            + "the full instructions are NOT in your context until you call this tool. "
            + "Follow the returned instructions for the rest of the task. "
            + "Loading a skill may also pre-approve the tools it declares in allowed-tools.";

    private final ObjectMapper mapper;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    /** 已加载技能声明的预授权规则，累加——加载过就一直有效 */
    private final Set<String> granted = new LinkedHashSet<>();
    /** 已加载过正文的技能名。s08 压缩后要靠它提示模型「细则不在上下文里了，重新取」 */
    private final Set<String> loaded = new LinkedHashSet<>();

    public SkillRegistry(ObjectMapper mapper, List<Skill> loaded) {
        this.mapper = mapper;
        for (Skill skill : loaded) {
            skills.put(skill.name(), skill);
        }
    }

    public int size() {
        return skills.size();
    }

    public Set<String> names() {
        return skills.keySet();
    }

    /** 加载过正文的技能名，按加载顺序 */
    public Set<String> loadedNames() {
        return loaded;
    }

    /**
     * 目录。每行「- 名字: 什么时候用」，这是唯一常驻上下文的部分。
     * 一个技能在目录里占一行，正文多长都不影响这里。
     */
    public String catalog() {
        if (skills.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills.values()) {
            sb.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
        }
        return sb.toString().strip();
    }

    /** 拼到 system prompt 末尾的一段话：目录 + 用法 */
    public String catalogSection() {
        return "\n\nAvailable skills (name: when to use it). The full instructions are not loaded yet — "
                + "call skill(name) to load one before doing that kind of work:\n"
                + catalog() + "\n";
    }

    /** skill 工具的 schema：name 用 enum 锁死，模型没法编一个不存在的技能 */
    public ObjectNode schema() {
        ObjectNode props = mapper.createObjectNode();

        ObjectNode name = mapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "Skill name, exactly as listed in the system prompt catalog");
        ArrayNode allowed = mapper.createArrayNode();
        skills.keySet().forEach(allowed::add);
        name.set("enum", allowed);
        props.set("name", name);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("name"));
        return schema;
    }

    /**
     * 加载一个技能：正文包一层标签，作为 tool 结果交给模型。
     *
     * 注意「加载」不等于「卸载」。正文一旦进了 messages，就一直留在那儿，
     * 直到上下文被压缩（那是下一篇的事）。按需加载省的是「没用到的那几份」，
     * 不是「用过的那一份」。
     */
    public String load(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return "(error: unknown skill '" + name + "'. Available: " + String.join(", ", skills.keySet()) + ")";
        }
        granted.addAll(skill.allowedTools());
        loaded.add(name);
        System.out.println("   [skill] 载入 " + name + "：正文 " + skill.body().length() + " chars"
                + (skill.allowedTools().isEmpty() ? "" : "，预授权 " + skill.allowedTools()));
        return "<skill name=\"" + skill.name() + "\">\n"
                + skill.body() + "\n"
                + "</skill>\n"
                + "以上是技能 " + skill.name() + " 的完整说明，接下来的任务按它执行。";
    }

    /**
     * 这次调用有没有被某个已加载的技能预授权。返回命中的规则（便于打印），没命中返回 null。
     *
     * 规则两种写法：
     *   read_file         整个工具放行
     *   bash(git tag)     bash 命令以 "git tag" 开头的才放行
     */
    public String grantedBy(String toolName, JsonNode args) {
        for (String rule : granted) {
            int paren = rule.indexOf('(');
            if (paren < 0) {
                if (rule.equals(toolName)) {
                    return rule;
                }
                continue;
            }
            if (!rule.substring(0, paren).equals(toolName)) {
                continue;
            }
            String prefix = rule.substring(paren + 1, rule.endsWith(")") ? rule.length() - 1 : rule.length());
            String command = args.path("command").asText("");
            if (!prefix.isEmpty() && command.startsWith(prefix)) {
                return rule;
            }
        }
        return null;
    }
}

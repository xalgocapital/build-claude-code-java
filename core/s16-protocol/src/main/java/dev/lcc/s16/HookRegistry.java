package dev.lcc.s16;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 注册表：事件名 -> 回调列表。
 *
 * 这是 s04 相对 s03 唯一新增的机制。循环不再直接调用任何检查/日志函数，
 * 只认两个动作：
 *   register(event, hook)       —— 把扩展逻辑挂到某个事件上（启动时做）
 *   trigger(event)              —— 触发某事件的所有 hook（运行时做）
 *
 * 循环代码因此稳定：加一种行为 = register 一个 hook，不碰 while(true)。
 * trigger 的顺序语义：按注册顺序跑，第一个返回非 null 的 hook 立即短路——
 * 这样「拒绝」能抢在「日志」「统计」之前生效。
 */
public class HookRegistry {
    private final Map<String, List<Hook>> hooks = new LinkedHashMap<>();

    public HookRegistry() {
        hooks.put("UserPromptSubmit", new ArrayList<>());
        hooks.put("PreToolUse", new ArrayList<>());
        hooks.put("PostToolUse", new ArrayList<>());
        hooks.put("Stop", new ArrayList<>());
    }

    /** 注册一个 hook 到指定事件 */
    public void register(String event, Hook hook) {
        List<Hook> list = hooks.get(event);
        if (list == null) {
            throw new IllegalArgumentException("unknown hook event: " + event);
        }
        list.add(hook);
    }

    /**
     * 触发事件。按注册顺序跑，第一个返回非 null 的 hook 的值就是本次结果，
     * 后面的 hook 不再执行（短路）。全部返回 null 则返回 null（放行）。
     */
    public String trigger(Event event) {
        List<Hook> list = hooks.get(event.name());
        if (list == null) {
            return null;
        }
        for (Hook hook : list) {
            String result = hook.onEvent(event);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}

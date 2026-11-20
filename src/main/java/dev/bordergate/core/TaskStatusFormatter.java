package dev.bordergate.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Read-only rendering of one calendar quest, using its persisted deadline. */
public final class TaskStatusFormatter {
    private TaskStatusFormatter() {}

    public static List<String> lines(String cadence, QuestCycle cycle, long deadline, boolean settled,
                                     TaskSchedule schedule, long now) {
        String label = switch (cadence) {
            case "weekly" -> "每周任务";
            case "monthly" -> "每月任务";
            default -> "每日任务";
        };
        if (cycle == null) return List.of("[" + label + "] 暂无可用任务。");

        List<String> lines = new ArrayList<>();
        lines.add("[" + label + "] " + cycle.title() + " | 任务ID: " + cycle.id());
        String state;
        if (settled) state = "已结算";
        else if (cycle.isComplete()) state = "已完成，等待结算";
        else if (deadline > 0 && now >= deadline) state = "已到期，等待结算";
        else state = "进行中";
        String percent = cycle.completionPercent().setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        lines.add("状态: " + state + " | 目标达成: " + cycle.completedResourceCount() + "/" + cycle.targets().size()
                + "种（需" + cycle.requiredCount() + "种） | 完成率: " + percent + "%");
        if (deadline <= 0) {
            lines.add("截止时间: 未设置");
        } else {
            String timing;
            if (settled) timing = "本期已结算";
            else if (now >= deadline) timing = "已到期";
            else {
                Duration left = Duration.ofMillis(deadline - now);
                timing = "剩余: " + left.toHours() + "小时" + left.toMinutesPart() + "分钟" + left.toSecondsPart() + "秒";
            }
            lines.add("截止时间: " + schedule.formatDeadline(deadline) + " | " + timing);
        }
        cycle.targets().forEach((resource, target) -> lines.add("  " + resource + ": "
                + cycle.contributed().getOrDefault(resource, BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                + "/" + target.stripTrailingZeros().toPlainString()));
        return List.copyOf(lines);
    }
}

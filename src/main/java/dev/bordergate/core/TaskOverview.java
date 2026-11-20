package dev.bordergate.core;

import java.math.BigDecimal;
import java.util.List;

/** Immutable display data: no payment, claims or live QuestCycle references. */
public record TaskOverview(String cadence, String title, int completed, int required,
                           BigDecimal percent, long deadline, String status, List<Resource> resources) {
    public static final int RESOURCES_PER_PAGE = 12;

    public TaskOverview {
        resources = List.copyOf(resources);
    }

    public record Resource(String id, BigDecimal contributed, BigDecimal target) {
        public BigDecimal remaining() { return target.subtract(contributed).max(BigDecimal.ZERO); }
    }

    public static TaskOverview snapshot(String cadence, QuestCycle cycle, long deadline, boolean settled, long now) {
        if (cycle == null) return new TaskOverview(cadence, "", 0, 0, BigDecimal.ZERO, deadline, "暂无任务", List.of());
        String status = settled ? "已结算" : cycle.isComplete() ? "已达标，等待结算"
                : deadline <= now ? "已到期，等待结算" : "进行中";
        var resources = cycle.targets().entrySet().stream()
                .map(e -> new Resource(e.getKey(), cycle.contributed().getOrDefault(e.getKey(), BigDecimal.ZERO), e.getValue()))
                .toList();
        return new TaskOverview(cadence, cycle.title(), cycle.completedResourceCount(), cycle.requiredCount(),
                cycle.completionPercent(), deadline, status, resources);
    }

    public int pages() { return Math.max(1, (resources.size() + RESOURCES_PER_PAGE - 1) / RESOURCES_PER_PAGE); }

    public List<Resource> page(int page) {
        if (page >= pages()) return List.of();
        int from = Math.max(0, page) * RESOURCES_PER_PAGE;
        return resources.subList(from, Math.min(resources.size(), from + RESOURCES_PER_PAGE));
    }

    public static int resourceSlot(int column, int index) {
        if (column < 0 || column > 2 || index < 0 || index >= RESOURCES_PER_PAGE) throw new IllegalArgumentException("Invalid overview cell");
        return 9 + (index / 3) * 9 + column * 3 + index % 3;
    }
}

package dev.bordergate.core;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Local-backend settlement announcements, deduplicated by task identity. */
public final class SettlementAnnouncements {
    public static final String DEFAULT_SUCCESS = "&a[边界维护] {cadence}已达标，本区边界直径：{size} 格。";
    public static final String DEFAULT_FAILURE = "&e[边界维护] {cadence}未达标，本区边界直径：{size} 格。";
    private final Set<String> announced = new HashSet<>();

    public synchronized boolean announce(boolean enabled, String localServer, String taskOwner,
                                         String taskId, String cadence, boolean success, double size,
                                         String successTemplate, String failureTemplate, Consumer<String> sender) {
        if (!enabled || !localServer.equals(taskOwner) || !Double.isFinite(size) || size <= 0) return false;
        String template = success ? successTemplate : failureTemplate;
        if (template == null || template.isBlank()) return false;
        String message = template.replace("{task}", taskId)
                .replace("{cadence}", switch (cadence) {
                    case "weekly" -> "每周任务";
                    case "monthly" -> "每月任务";
                    default -> "每日任务";
                })
                .replace("{size}", BigDecimal.valueOf(size).stripTrailingZeros().toPlainString());
        if (!announced.add(taskId)) return false;
        try {
            sender.accept(message);
            return true;
        } catch (RuntimeException ex) {
            announced.remove(taskId);
            throw ex;
        }
    }
}

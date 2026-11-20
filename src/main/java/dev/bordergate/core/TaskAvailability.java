package dev.bordergate.core;

/** Explicit missing-task reasons, separate from tasks that already exist. */
public enum TaskAvailability {
    DISABLED("task-disabled", "&7本服已停用{cadence}"),
    NO_TEMPLATE("task-no-template", "&c{cadence}暂不可用：没有可用的资源模板"),
    NO_TARGETS("task-no-targets", "&c{cadence}暂不可用：手动目标为空或无效"),
    PENDING("task-pending", "&e{cadence}正在准备，请稍后刷新");

    private final String configKey;
    private final String template;

    TaskAvailability(String configKey, String template) {
        this.configKey = configKey;
        this.template = template;
    }

    public String configKey() { return "gui." + configKey; }
    public String template() { return template; }
    public String message(String cadence) { return template.replace("{cadence}", cadenceLabel(cadence)); }

    public static String cadenceLabel(String cadence) {
        return switch (cadence) {
            case "daily" -> "每日任务";
            case "weekly" -> "每周任务";
            case "monthly" -> "每月任务";
            default -> throw new IllegalArgumentException("Unknown task cadence: " + cadence);
        };
    }
}

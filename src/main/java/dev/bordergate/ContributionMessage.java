package dev.bordergate;

import dev.bordergate.core.CooperationLedger;
import dev.bordergate.core.TaskAvailability;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

/** Player-facing receipt only; never rounds the stored score or payment amount. */
final class ContributionMessage {
    private ContributionMessage() {}

    static String score(BigDecimal value) {
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.01")) < 0) return "<0.01";
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static Component create(Component resourceName, BigDecimal amount, BigDecimal earned,
                            String cadence, String title, BigDecimal done, BigDecimal target,
                            boolean finished, CooperationLedger.Feedback feedback) {
        Component receipt = ResourceDisplay.text("&a已投入 ").append(resourceName)
                .append(ResourceDisplay.text(" &a×" + exact(amount) + " &7· &e本次贡献 +" + score(earned) + " 分"));
        Component progress = ResourceDisplay.text("&7" + TaskAvailability.cadenceLabel(cadence)
                + " &8· &7资源进度 " + exact(done) + "/" + exact(target));
        if (feedback != null && feedback.firstParticipation())
            progress = progress.append(ResourceDisplay.text(" &6· 首次参与"));
        if (finished) progress = progress.append(ResourceDisplay.text(" &b· 已补齐资源"));
        if (feedback != null && !feedback.landName().isEmpty())
            progress = progress.append(ResourceDisplay.text(" &7· 城镇：")).append(Component.text(feedback.landName()));
        Component details = ResourceDisplay.text("&f任务：").append(ResourceDisplay.text(title));
        if (feedback != null) details = details.append(ResourceDisplay.text("\n&7连续参与 " + feedback.streak() + " 天"));
        return receipt.append(Component.newline()).append(progress).hoverEvent(HoverEvent.showText(details));
    }

    private static String exact(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}

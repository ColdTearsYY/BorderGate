package dev.bordergate;

import static org.junit.jupiter.api.Assertions.*;
import dev.bordergate.core.CooperationLedger;
import java.math.BigDecimal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ContributionMessageTest {
    private static final Component SHELL = Component.translatable("item.minecraft.nautilus_shell");
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
    private static boolean key(Component c) { return c instanceof TranslatableComponent t && t.key().equals("item.minecraft.nautilus_shell") || c.children().stream().anyMatch(ContributionMessageTest::key); }
    private static Component receipt(CooperationLedger.Feedback feedback, boolean finished) {
        return ContributionMessage.create(SHELL, n("1"), n("10.000000000000003"), "daily", "每日任务示例", n("1"), n("10"), finished, feedback);
    }

    @Test void screenshotTailIsFormattedWithoutMutatingStoredScore() {
        BigDecimal original = n("10.000000000000003");
        assertEquals("10", ContributionMessage.score(original));
        assertEquals(n("10.000000000000003"), original);
        assertEquals("10.13", ContributionMessage.score(n("10.126")));
        assertEquals("10.1", ContributionMessage.score(n("10.1000")));
        assertEquals("0", ContributionMessage.score(BigDecimal.ZERO));
    }
    @Test void smallPositiveContributionsAreNotShownAsZero() {
        assertEquals("<0.01", ContributionMessage.score(n("0.00000001")));
        assertEquals("0.01", ContributionMessage.score(n("0.01")));
        assertEquals("0.01", ContributionMessage.score(n("0.014")));
    }
    @Test void receiptPreservesClientTranslationAndHasOnlyTwoExplicitLines() {
        Component result = receipt(null, false);
        assertTrue(key(result));
        String text = plain(result);
        assertFalse(text.contains("item:NAUTILUS_SHELL"));
        assertTrue(text.contains("×1 · 本次贡献 +10 分"));
        assertTrue(text.contains("每日任务 · 资源进度 1/10"));
        assertEquals(2, text.split("\n").length);
        assertFalse(text.contains("已保存"));
        assertFalse(text.contains("首次参与"));
        assertFalse(text.contains("城镇"));
    }
    @Test void personalAndTownFeedbackUseOnlyRecordedAffiliation() {
        var personal = new CooperationLedger.Feedback(true, false, 1, n("10"), "");
        assertTrue(plain(receipt(personal, false)).contains("首次参与"));
        assertFalse(plain(receipt(personal, false)).contains("城镇"));
        var town = new CooperationLedger.Feedback(false, true, 8, n("100"), "城镇甲&c");
        String text = plain(receipt(town, true));
        assertTrue(text.contains("已补齐资源"));
        assertTrue(text.contains("城镇：城镇甲&c"));
        assertFalse(text.contains("连续参与"));
        assertFalse(text.contains("首次参与"));
    }
    @Test void titleAndStreakAreAvailableOnHover() {
        var feedback = new CooperationLedger.Feedback(false, false, 8, n("10"), "城镇甲");
        Component result = receipt(feedback, false);
        assertNotNull(result.hoverEvent());
        String hover = plain((Component)result.hoverEvent().value());
        assertTrue(hover.contains("每日任务示例"));
        assertTrue(hover.contains("连续参与 8 天"));
    }
    @Test void disabledCooperationStillHasProgressAndCompletion() {
        String text = plain(receipt(null, true));
        assertTrue(text.contains("已投入"));
        assertTrue(text.contains("本次贡献 +10 分"));
        assertTrue(text.contains("已补齐资源"));
        assertFalse(text.contains("首次参与"));
        assertFalse(text.contains("城镇"));
    }
    @Test void paymentQuantitiesKeepTheirExactPrecision() {
        Component result = ContributionMessage.create(Component.text("金币"), n("0.000001"), n("0.001"),
                "weekly", "周任务", n("0.123456"), n("1.000001"), false, null);
        assertTrue(plain(result).contains("×0.000001"));
        assertTrue(plain(result).contains("+<0.01 分"));
        assertTrue(plain(result).contains("0.123456/1.000001"));
    }
    @Test void customResourceNameAndLiteralTownNameArePreserved() {
        Component result = ContributionMessage.create(ResourceDisplay.text("&6服务器鹦鹉螺壳"), n("1"), n("2"),
                "monthly", "月任务", n("1"), n("5"), false, null);
        assertTrue(plain(result).contains("服务器鹦鹉螺壳"));
        assertTrue(plain(result).contains("每月任务"));
    }
}

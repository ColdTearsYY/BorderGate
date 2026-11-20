package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooperationNamespaceTest {
    @Test void productionContributionAndLegacyNamespacesRestoreAndDeduplicate() {
        var ledger = new CooperationLedger(); var player = UUID.randomUUID();
        var date = LocalDate.of(2026, 11, 18);
        String local = "local:" + UUID.randomUUID();
        assertNotNull(ledger.recordContribution(local, player, date, BigDecimal.TEN, true));
        assertNotNull(ledger.recordContribution("network:123456", player, date, BigDecimal.ONE, false));
        assertNotNull(ledger.recordContribution("legacy:0", player, date.minusDays(1), BigDecimal.ONE, false));
        var restored = new CooperationLedger(); restored.restoreState(ledger.exportState());
        assertNull(restored.recordContribution(local, player, date, BigDecimal.TEN, true));
        assertNull(restored.recordContribution("legacy:0", player, date, BigDecimal.ONE, false));
        assertEquals(new BigDecimal("12"), restored.profile(player).totalScore());
        assertEquals(1, restored.profile(player).resourceFinishes());
    }
    @Test void actualScopedLandAndTaskIdsWorkAndRestore() {
        var ledger = new CooperationLedger(); var player = UUID.randomUUID();
        String land = "lands:" + "a".repeat(64) + ":01ARZ3NDEKTSV4RRFFQ69G5FAV", task = "server-1:daily:2026-11-18";
        assertNotNull(ledger.claim(UUID.randomUUID().toString(), task, "item:STONE", player, land, "玩家的领地", BigDecimal.ONE, BigDecimal.TEN, 0, 1000));
        assertTrue(ledger.allowReminder(player, task, "new", 0));
        var restored = new CooperationLedger(); restored.restoreState(ledger.exportState());
        assertEquals(land, restored.claims(task, 0).getFirst().landId()); assertEquals(1, restored.claims(task, 0).size());
        assertFalse(restored.allowReminder(player, task, "new", 600000));
    }
    @Test void closeResourceThenTaskDoesNotAffectOtherTasks() {
        var ledger = new CooperationLedger(); var player = UUID.randomUUID();
        ledger.claim("a", "task:1", "item:STONE", player, "", "", BigDecimal.ONE, BigDecimal.TEN, 0, 1000);
        ledger.claim("b", "task:1", "item:APPLE", player, "", "", BigDecimal.ONE, BigDecimal.TEN, 0, 1000);
        ledger.claim("c", "task:2", "item:STONE", player, "", "", BigDecimal.ONE, BigDecimal.TEN, 0, 1000);
        ledger.closeClaims("task:1", "item:STONE");
        assertEquals("b", ledger.claims("task:1", 0).getFirst().id());
        ledger.closeClaims("task:1", null);
        assertTrue(ledger.claims("task:1", 0).isEmpty()); assertEquals(1, ledger.claims("task:2", 0).size());
    }
    @Test void unsafeIdsRemainRejected() {
        var ledger = new CooperationLedger(); var player = UUID.randomUUID();
        for (String id : new String[]{"bad|id", "bad\nname", "bad name", "x".repeat(191)}) {
            assertFalse(LandAffiliation.validId("lands:" + id));
            assertNull(ledger.recordContribution(id, player, LocalDate.now(), BigDecimal.ONE, false));
            assertFalse(ledger.allowReminder(player, id, "new", 0));
        }
    }
}

package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooperationLedgerRegressionTest {
    @Test void reminderLimitBelongsToEachTaskNotPlayersLifetime() {
        var ledger=new CooperationLedger(); var player=UUID.randomUUID();
        assertFalse(ledger.allowReminder(player,"one",null,0));
        assertTrue(ledger.allowReminder(player,"one","new",0));
        assertTrue(ledger.allowReminder(player,"one","near",600000));
        assertTrue(ledger.allowReminder(player,"one","complete",600001));
        assertFalse(ledger.allowReminder(player,"one","complete",1200000));
        assertTrue(ledger.allowReminder(player,"two","new",1200000));
        assertTrue(ledger.allowReminder(player,"two","complete",1200001));
        var restored=new CooperationLedger(); restored.restoreState(ledger.exportState());
        assertFalse(restored.allowReminder(player,"one","near",1800000));
        assertTrue(restored.allowReminder(player,"three","new",1800000));
    }
    @Test void selfReplacementWithNewIdReleasesOldPlanButCannotStealOthers() {
        var ledger=new CooperationLedger(); var a=UUID.randomUUID(); var b=UUID.randomUUID();
        assertNotNull(ledger.claim("a","task","ore",a,"","",BigDecimal.TEN,BigDecimal.TEN,0,1000));
        assertNotNull(ledger.claim("new-a","task","ore",a,"","",new BigDecimal("3"),BigDecimal.TEN,0,1000));
        assertEquals(1,ledger.claims("task",0).size());
        assertEquals("new-a",ledger.claims("task",0).getFirst().id());
        assertNull(ledger.claim("new-a","task","ore",b,"","",BigDecimal.ONE,BigDecimal.TEN,0,1000));
        assertNotNull(ledger.claim("b","task","ore",b,"","",new BigDecimal("7"),BigDecimal.TEN,0,1000));
        assertFalse(ledger.cancelClaim("b",a));
    }
    @Test void advisoryPlansDoNotPreventUnclaimedPlayersFromCompletingQuest() {
        var ledger=new CooperationLedger(); var a=UUID.randomUUID(); var outsider=UUID.randomUUID();
        ledger.claim("a","task","item:STONE",a,"","",BigDecimal.TEN,BigDecimal.TEN,0,1000);
        var quest=new QuestCycle("task","Task","daily",Map.of("item:STONE",BigDecimal.TEN),1,BigDecimal.ONE);
        assertTrue(quest.contribute(outsider,"item:STONE",BigDecimal.TEN,BigDecimal.ONE));
        assertTrue(quest.isComplete()); assertEquals(BigDecimal.ZERO,quest.remaining("item:STONE"));
        assertEquals(1,ledger.claims("task",0).size());
    }
    @Test void olderParticipationDoesNotResetStreakOrMoveLandHistory() {
        var ledger=new CooperationLedger(); var a=UUID.randomUUID();
        var one=new LandAffiliation("lands:one","One",20);
        var two=new LandAffiliation("lands:two","Two",30);
        ledger.recordContribution("1",a,LocalDate.of(2026,1,2),BigDecimal.ONE,false,one);
        ledger.recordContribution("2",a,LocalDate.of(2026,1,3),BigDecimal.ONE,true,one);
        ledger.recordContribution("3",a,LocalDate.of(2026,1,1),BigDecimal.ONE,false,one);
        assertEquals(2,ledger.profile(a).streak()); assertEquals(LocalDate.of(2026,1,3),ledger.profile(a).lastDay());
        ledger.recordContribution("4",a,LocalDate.of(2026,1,7),BigDecimal.ONE,false,two);
        assertEquals(1,ledger.profile(a).streak()); assertEquals(new BigDecimal("3"),ledger.landScore("lands:one"));
        assertEquals(BigDecimal.ONE,ledger.landScore("lands:two"));
    }
}

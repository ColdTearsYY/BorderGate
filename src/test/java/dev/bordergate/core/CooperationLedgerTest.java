package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooperationLedgerTest {
    private static final long NOW = 1_000_000L;
    @Test void claimsAreAdvisoryReplaceableExpiringAndFulfilSelfThenLand() {
        var ledger = new CooperationLedger();
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        assertNotNull(ledger.claim("a","task","ore",a,"lands:crew","领地",bd("6"),bd("10"),NOW,NOW+100));
        assertNotNull(ledger.claim("a","task","ore",a,"lands:crew","领地",bd("4"),bd("10"),NOW,NOW+100));
        assertNotNull(ledger.claim("b","task","ore",b,"lands:crew","领地",bd("6"),bd("10"),NOW,NOW+100));
        assertNull(ledger.claim("c","task","ore",UUID.randomUUID(),"","",bd("1"),bd("10"),NOW,NOW+100));
        assertEquals(2,ledger.claims("task",NOW).size());
        assertFalse(ledger.cancelClaim("a",b));
        ledger.fulfil("task","ore",a,"lands:crew",bd("5"),NOW);
        assertEquals(bd("5"),ledger.claims("task",NOW).getFirst().amount());
        assertEquals(1,ledger.claims("task",NOW+99).size());
        assertTrue(ledger.claims("task",NOW+100).isEmpty());
        assertFalse(ledger.cancelClaim("a",a));
    }
    @Test void externalLandHasNoMemberCapAndScoresAreImmutable() {
        var land = new LandAffiliation("lands:one","领地",200);
        assertEquals(200,land.members());
        var ledger = new CooperationLedger();
        ledger.recordContribution("e1",UUID.randomUUID(),LocalDate.of(2026,1,1),bd("2"),false,land);
        var scores=ledger.landScores();
        assertThrows(UnsupportedOperationException.class,()->scores.clear());
        ledger.recordContribution("e2",UUID.randomUUID(),LocalDate.of(2026,1,1),bd("3"),false,land);
        assertEquals(bd("2"),scores.get(land.id()));
        assertEquals(bd("5"),ledger.landScore(land.id()));
    }
    @Test void contributionsArePositiveIdempotentAndCreditLandAtEventTime() {
        var ledger=new CooperationLedger(); var player=UUID.randomUUID();
        var land=new LandAffiliation("lands:first","领地",20);
        assertNull(ledger.recordContribution("bad",player,LocalDate.of(2026,1,1),BigDecimal.ZERO,true,land));
        assertTrue(ledger.recordContribution("e1",player,LocalDate.of(2026,1,1),bd("2"),true,land).firstParticipation());
        assertEquals(1,ledger.recordContribution("e2",player,LocalDate.of(2026,1,1),bd("3"),false,land).streak());
        var renamed=new LandAffiliation(land.id(),"新领地名",30);
        assertEquals(2,ledger.recordContribution("e3",player,LocalDate.of(2026,1,2),bd("4"),false,renamed).streak());
        assertNull(ledger.recordContribution("e3",player,LocalDate.of(2026,1,3),bd("5"),true,renamed));
        assertEquals(bd("9"),ledger.landScore(land.id()));
        assertEquals(LocalDate.of(2026,1,2),ledger.profile(player).lastDay());
        assertEquals(bd("9"),ledger.profile(player).totalScore());
    }
    @Test void reminderGatesDoNotConsumeRejectedAttemptsAndCompleteBypassesCooldown() {
        var ledger=new CooperationLedger(); var player=UUID.randomUUID();
        ledger.setReminders(player,false);
        assertFalse(ledger.allowReminder(player,"one","new",0));
        ledger.setReminders(player,true);
        assertTrue(ledger.allowReminder(player,"one","new",0));
        assertFalse(ledger.allowReminder(player,"two","near",1));
        assertTrue(ledger.allowReminder(player,"two","complete",1));
        assertFalse(ledger.allowReminder(player,"one","new",700000));
        assertTrue(ledger.allowReminder(player,"three","near",700000));
        assertTrue(ledger.allowReminder(player,"four","complete",800000));
    }
    @Test void exportRestorePreservesPersonalLandStateAndIgnoresMalformedRows() {
        var ledger=new CooperationLedger(); var player=UUID.randomUUID();
        var land=new LandAffiliation("lands:land","领地",20);
        ledger.claim("claim","task","ore",player,land.id(),land.name(),bd("2"),bd("4"),NOW,NOW+500);
        ledger.recordContribution("event",player,LocalDate.of(2026,4,4),bd("7"),true,land);
        ledger.markActivity(player,NOW); ledger.allowReminder(player,"task","new",0);
        var state=ledger.exportState();
        assertThrows(UnsupportedOperationException.class,()->state.put("bad",1));
        var restored=new CooperationLedger(); restored.restoreState(state);
        assertEquals(ledger.claims("task",NOW),restored.claims("task",NOW));
        assertEquals(ledger.profile(player),restored.profile(player));
        assertEquals(1,restored.activePlayers(NOW));
        assertEquals(bd("7"),restored.landScore(land.id()));
        assertFalse(restored.allowReminder(player,"task","new",1));
        assertNull(restored.recordContribution("event",player,LocalDate.of(2026,4,5),bd("7"),true,land));
        assertEquals(2,restored.recordContribution("event-next",player,LocalDate.of(2026,4,5),bd("1"),false).streak());
        restored.restoreState(Map.of("claims",List.of("malformed")));
        assertTrue(restored.claims(null,NOW).isEmpty());
    }
    @Test void oldSquadStateAndClaimsAreArchivedNeverMappedEvenWithLandsPrefix() {
        var player=UUID.randomUUID();
        var oldClaim=Map.<String,Object>of("id","old","taskId","task","resource","ore","playerId",player.toString(),"teamId","lands:pretend","teamName","旧小队","amount","2","expiresAt",NOW+500);
        var personal=Map.<String,Object>of("id","personal","taskId","task","resource","ore","playerId",UUID.randomUUID().toString(),"teamId","","teamName","","amount","1","expiresAt",NOW+500);
        var teams=List.of(Map.of("id","lands:pretend","members",List.of(player.toString())));
        var ledger=new CooperationLedger();
        ledger.restoreState(Map.of("teams",teams,"teamScores",Map.of("lands:pretend","99"),"claims",List.of(oldClaim,personal)));
        assertEquals("personal",ledger.claims("task",NOW).getFirst().id());
        assertEquals(1,ledger.claims("task",NOW).size());
        assertTrue(ledger.landScores().isEmpty());
        var exported=ledger.exportState();
        assertEquals(teams,((Map<?,?>)exported.get("legacy-squads")).get("teams"));
        assertEquals(List.of(oldClaim),exported.get("legacy-squad-claims"));
        var restored=new CooperationLedger(); restored.restoreState(exported);
        assertEquals(exported,restored.exportState());
    }
    @Test void landIdentifierFitsCompatibilityColumnExactly() {
        assertTrue(LandAffiliation.validId("lands:"+"x".repeat(114)));
        assertFalse(LandAffiliation.validId("lands:"+"x".repeat(115)));
        assertFalse(LandAffiliation.validId("lands:"));
        assertThrows(IllegalArgumentException.class,()->new LandAffiliation("lands:ok","",20));
        assertThrows(IllegalArgumentException.class,()->new LandAffiliation("lands:ok","ok",-1));
    }
    private static BigDecimal bd(String value) {return new BigDecimal(value);}
}

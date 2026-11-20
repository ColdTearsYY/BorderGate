package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class TownLeaderboardTest {
    private static LandAffiliation land(String id,String name) {return new LandAffiliation("lands:"+id,name,20);}
    @Test void scoreDescendingAndNameThenIdentityBreakTies() {
        LandAffiliation a=land("a","甲"), b=land("b","同名"), c=land("c","同名");
        var rows=TownLeaderboard.rank(List.of(c,a,b),Map.of(a.id(),new BigDecimal("2.5"),b.id(),BigDecimal.TEN,c.id(),new BigDecimal("10.00")));
        assertEquals(List.of(b,c,a),rows.stream().map(TownLeaderboard.Entry::land).toList());
        assertEquals(List.of(1,2,3),rows.stream().map(TownLeaderboard.Entry::rank).toList());
        assertEquals(new BigDecimal("2.5"),rows.getLast().score());
    }
    @Test void onlyCurrentTownsWithPositiveScoresEnterBoard() {
        LandAffiliation a=land("a","A"),b=land("b","B"),c=land("c","C"),d=land("d","D");
        var scores=Map.of(a.id(),BigDecimal.ONE,b.id(),BigDecimal.ZERO,c.id(),BigDecimal.ONE.negate(),"lands:deleted",BigDecimal.TEN,"lands:foreign",BigDecimal.TEN);
        var rows=TownLeaderboard.rank(List.of(a,b,c,d),scores);
        assertEquals(1,rows.size());assertEquals(a,rows.getFirst().land());
        assertEquals(BigDecimal.TEN,scores.get("lands:deleted"));
    }
    @Test void boardIsAnImmutableSnapshotAndDeduplicatesIdentity() {
        var a=land("a","A");var scores=new HashMap<String,BigDecimal>();scores.put(a.id(),BigDecimal.ONE);
        var rows=TownLeaderboard.rank(List.of(a,a),scores);assertEquals(1,rows.size());
        assertThrows(UnsupportedOperationException.class,()->rows.clear());
        scores.put(a.id(),BigDecimal.TEN);assertEquals(BigDecimal.ONE,rows.getFirst().score());
    }
    @Test void catalogOrderingDoesNotChangeRanking() {
        LandAffiliation a=land("a","A"),b=land("b","B"),c=land("c","C");
        var scores=Map.of(a.id(),BigDecimal.ONE,b.id(),BigDecimal.TEN,c.id(),BigDecimal.ONE);
        assertEquals(TownLeaderboard.rank(List.of(a,b,c),scores),TownLeaderboard.rank(List.of(c,b,a),scores));
    }
    @Test void renameUsesLiveNameWhilePreservingHistoricalIdentityAndScores() {
        var ledger=new CooperationLedger();var old=land("a","旧名");var player=UUID.randomUUID();
        ledger.recordContribution("local:first",player,LocalDate.of(2026,11,18),BigDecimal.TEN,false,old);
        var restored=new CooperationLedger();restored.restoreState(ledger.exportState());
        var renamed=land("a","新名");var rows=TownLeaderboard.rank(List.of(renamed),restored.landScores());
        assertEquals("新名",rows.getFirst().land().name());assertEquals(BigDecimal.TEN,rows.getFirst().score());
        assertNull(restored.recordContribution("local:first",player,LocalDate.of(2026,11,18),BigDecimal.TEN,false,renamed));
        assertEquals(BigDecimal.TEN,restored.landScore(old.id()));
    }
    @Test void oldSquadsNeverProduceTownRankings() {
        var ledger=new CooperationLedger();ledger.restoreState(Map.of("teamScores",Map.of("lands:pretend","100")));
        assertTrue(TownLeaderboard.rank(List.of(land("pretend","不是城镇")),ledger.landScores()).isEmpty());
        assertTrue(ledger.exportState().containsKey("legacy-squads"));
    }
    @Test void ranksContinueAcrossFortyFiveEntryPageBoundary() {
        List<LandAffiliation> lands=new ArrayList<>();Map<String,BigDecimal> scores=new HashMap<>();
        for(int i=0;i<46;i++){var land=land("id"+i,"Town"+i);lands.add(land);scores.put(land.id(),BigDecimal.valueOf(100-i));}
        var rows=TownLeaderboard.rank(lands,scores);
        assertEquals(45,rows.get(44).rank());assertEquals(46,rows.get(45).rank());assertEquals(lands.get(45),rows.get(45).land());
    }
    @Test void emptyDataIsEmptyButMissingDataIsRejected() {
        assertTrue(TownLeaderboard.rank(List.of(),Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->TownLeaderboard.rank(null,Map.of()));
        assertThrows(IllegalArgumentException.class,()->TownLeaderboard.rank(List.of(),null));
    }
}

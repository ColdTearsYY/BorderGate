package dev.bordergate;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LandsTownCatalogTest {
    @Test void publicCatalogIncludesTownsTheViewerDoesNotBelongTo() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("server",fixture.api);
        assertEquals(1,hook.memberships(fixture.a).size());assertEquals(2,hook.allLands().size());
        var second=hook.allLands().stream().filter(l->l.name().equals(fixture.secondName)).findFirst().orElseThrow();
        assertFalse(hook.member(fixture.a,second.id()));
        assertThrows(UnsupportedOperationException.class,()->hook.allLands().clear());
    }
    @Test void deletedTownsAreExcludedWithoutChangingHistoricalScores() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("server",fixture.api);
        fixture.firstExists=false;assertEquals(1,hook.allLands().size());assertTrue(hook.healthy());
        fixture.secondExists=false;assertTrue(hook.allLands().isEmpty());
    }
    @Test void apiFailureIsNotAnEmptyLeaderboard() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("server",fixture.api);
        fixture.fail=true;assertNull(hook.allLands());assertFalse(hook.healthy());
        fixture.fail=false;assertEquals(2,new LandsHook("server",fixture.api).allLands().size());
    }
    @Test void catalogUsesLiveNamesStableIdsAndRejectsMalformedNames() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("server",fixture.api);
        String id=hook.allLands().stream().filter(l->l.name().equals(fixture.firstName)).findFirst().orElseThrow().id();
        fixture.firstName="改名城镇";
        assertEquals(id,hook.allLands().stream().filter(l->l.name().equals(fixture.firstName)).findFirst().orElseThrow().id());
        fixture.firstName=null;assertNull(hook.allLands());assertFalse(hook.healthy());
    }
}

package dev.bordergate;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LandsHookTest {
    @Test void officialMembershipsAreReadOnlyAndNotCappedAtEight() {
        var fixture=new LandsApiFixture(); var hook=new LandsHook("server-a",fixture.api);
        var lands=hook.memberships(fixture.a);
        assertEquals(1,lands.size()); assertEquals(20,lands.getFirst().members());
        assertTrue(lands.getFirst().id().startsWith("lands:")); assertTrue(hook.member(fixture.a,lands.getFirst().id()));
        assertThrows(UnsupportedOperationException.class,()->lands.clear());
        assertEquals(2,hook.memberships(fixture.b).size());
    }
    @Test void ownersRetainPermissionWithoutAnExplicitTrustedEntry() {
        var fixture=new LandsApiFixture(); fixture.firstTrusted.clear();var hook=new LandsHook("a",fixture.api);
        assertEquals(1,hook.memberships(fixture.a).size());
        assertEquals("领地乙",hook.memberships(fixture.b).getFirst().name());
    }
    @Test void trustRevocationIsReadAtTheNextAction() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);
        String id=hook.memberships(fixture.a).getFirst().id();assertTrue(hook.member(fixture.b,id));
        fixture.firstTrusted.remove(fixture.b);assertFalse(hook.member(fixture.b,id));assertTrue(hook.healthy());
        assertEquals(1,hook.memberships(fixture.b).size());
    }
    @Test void landRenameAndMemberChangesKeepStableIdentity() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);var before=hook.memberships(fixture.a).getFirst();
        fixture.firstName="更名后的领地";fixture.firstMembers=45;var after=hook.memberships(fixture.a).getFirst();
        assertEquals(before.id(),after.id());assertEquals("更名后的领地",after.name());assertEquals(45,after.members());
    }
    @Test void serverNamespacesPreventCrossServerMembershipImpersonation() {
        var fixture=new LandsApiFixture();var a=new LandsHook("a",fixture.api);var b=new LandsHook("b",fixture.api);
        String id=a.memberships(fixture.a).getFirst().id();assertNotEquals(id,b.memberships(fixture.a).getFirst().id());
        assertFalse(b.local(id));assertFalse(b.member(fixture.a,id));
        assertTrue(new LandsHook("x".repeat(80),fixture.api).memberships(fixture.a).getFirst().id().length()<=120);
    }
    @Test void deletedLandsAndUnknownPlayersAreNotUsable() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);String id=hook.memberships(fixture.a).getFirst().id();
        fixture.firstExists=false;assertFalse(hook.member(fixture.a,id));assertTrue(hook.memberships(fixture.a).isEmpty());
        assertTrue(hook.memberships(UUID.randomUUID()).isEmpty());assertTrue(hook.memberships(null).isEmpty());
    }
    @Test void editedLandIsOnlyReadAndMustStillBeTrusted() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);
        fixture.edited.put(fixture.a,fixture.second);assertNull(hook.editing(fixture.a));
        fixture.edited.put(fixture.b,fixture.second);assertEquals("领地乙",hook.editing(fixture.b).name());
        fixture.secondExists=false;assertNull(hook.editing(fixture.b));
    }
    @Test void apiFailureDoesNotMasqueradeAsDefiniteMembershipRevocation() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);String id=hook.memberships(fixture.a).getFirst().id();
        fixture.fail=true;assertFalse(hook.member(fixture.b,id));assertFalse(hook.healthy());assertFalse(hook.available());
        fixture.fail=false;assertTrue(new LandsHook("a",fixture.api).member(fixture.b,id));
        assertThrows(IllegalArgumentException.class,()->new LandsHook("a",new Object()));
    }
    @Test void malformedApiNamesPauseRatherThanInventAName() {
        var fixture=new LandsApiFixture();var hook=new LandsHook("a",fixture.api);fixture.firstName=null;
        assertTrue(hook.memberships(fixture.a).isEmpty());assertFalse(hook.healthy());
    }
}

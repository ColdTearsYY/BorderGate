package dev.bordergate;

import java.lang.reflect.Proxy;
import java.util.*;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.applicationframework.util.ULID;

/** Official interface fixture only, never the actual Lands plugin and never shipped. */
public final class LandsApiFixture {
    public final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"), b=UUID.fromString("00000000-0000-0000-0000-000000000002");
    public final Set<UUID> firstTrusted=new HashSet<>();
    public final Set<UUID> secondTrusted=new HashSet<>();
    public final Map<UUID,Land> edited=new HashMap<>();
    public String firstName="领地甲", secondName="领地乙";
    public boolean firstExists=true,secondExists=true,fail=false;
    public int firstMembers=20,secondMembers=30;
    public final Land first,second;
    public final List<Land> additional=new ArrayList<>();
    public final LandsIntegration api;
    public LandsApiFixture() {
        firstTrusted.add(a); firstTrusted.add(b);
        first=land(true,"01ARZ3NDEKTSV4RRFFQ69G5FAV");second=land(false,"01ARZ3NDEKTSV4RRFFQ69G5FAW");
        api=(LandsIntegration)Proxy.newProxyInstance(LandsIntegration.class.getClassLoader(),new Class<?>[]{LandsIntegration.class},(p,m,args)->switch(m.getName()){
            case "getLands"->{if(fail)throw new IllegalStateException("simulated API failure");var all=new ArrayList<Land>(List.of(first,second));all.addAll(additional);yield List.copyOf(all);}
            case "getLandPlayer"->player((UUID)args[0]);
            case "toString"->"Official Lands interface fixture";
            default->zero(m.getReturnType());
        });
    }
    private Land land(boolean one,String id) {
        ULID ulid=(ULID)Proxy.newProxyInstance(ULID.class.getClassLoader(),new Class<?>[]{ULID.class},(p,m,args)->m.getName().equals("toString")?id:zero(m.getReturnType()));
        return (Land)Proxy.newProxyInstance(Land.class.getClassLoader(),new Class<?>[]{Land.class},(p,m,args)->switch(m.getName()){
            case "getULID"->ulid;
            case "getName"->one?firstName:secondName;
            case "getOwnerUID"->one?a:b;
            case "getMembersAmount"->one?firstMembers:secondMembers;
            case "exists"->one?firstExists:secondExists;
            case "isTrusted"->(one?firstTrusted:secondTrusted).contains((UUID)args[0]);
            case "trustPlayer","untrustPlayer","setOwner","delete","setName"->throw new AssertionError("No Lands mutation allowed in BorderGate");
            case "toString"->id;
            default->zero(m.getReturnType());
        });
    }
    /** Extra untrusted live towns for public-ranking and pagination integration tests. */
    public void addLand(String id,String name,int members) {
        ULID ulid=(ULID)Proxy.newProxyInstance(ULID.class.getClassLoader(),new Class<?>[]{ULID.class},(p,m,args)->m.getName().equals("toString")?id:zero(m.getReturnType()));
        additional.add((Land)Proxy.newProxyInstance(Land.class.getClassLoader(),new Class<?>[]{Land.class},(p,m,args)->switch(m.getName()){
            case "getULID"->ulid;case "getName"->name;case "getMembersAmount"->members;case "exists"->true;
            case "getOwnerUID"->new UUID(0,0);case "isTrusted"->false;
            case "trustPlayer","untrustPlayer","setOwner","delete","setName"->throw new AssertionError("No Lands mutation allowed");
            default->zero(m.getReturnType());
        }));
    }
    private LandPlayer player(UUID id) {
        if(!id.equals(a)&&!id.equals(b))return null;
        return (LandPlayer)Proxy.newProxyInstance(LandPlayer.class.getClassLoader(),new Class<?>[]{LandPlayer.class},(p,m,args)->switch(m.getName()){
            case "getLands"->List.of(first,second);
            case "getEditLand"->edited.get(id);
            case "getUID"->id;
            case "setEditLand"->throw new AssertionError("Do not change Lands edit selection");
            default->zero(m.getReturnType());
        });
    }
    private static Object zero(Class<?> c) {return c==boolean.class?false:c==int.class?0:c==long.class?0L:c==double.class?0d:c==float.class?0f:null;}
}

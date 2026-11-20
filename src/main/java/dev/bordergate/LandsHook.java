package dev.bordergate;

import dev.bordergate.core.LandAffiliation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Read-only official Lands API bridge. No copied roster, creation, trust or leave calls. */
final class LandsHook {
    private static final String ROOT = "me.angeschossen.lands.api.";
    private final BorderGatePlugin plugin;
    private final String prefix;
    private Object integration;
    private ClassLoader loader;
    private boolean healthy;
    private String problem = "Lands 未启用";

    LandsHook(BorderGatePlugin plugin, String serverId) {
        this.plugin = plugin;
        this.prefix = scope(serverId);
    }
    /** Injection uses the same official interfaces; reserved for isolated API contract tests. */
    LandsHook(String serverId, Object integration) {
        this.plugin = null;
        this.prefix = scope(serverId);
        bind(integration);
    }
    private static String scope(String server) {
        try { return "lands:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(server.getBytes(StandardCharsets.UTF_8))) + ":"; }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void bind(Object api) {
        try {
            ClassLoader cl = api.getClass().getClassLoader();
            if (!Class.forName(ROOT + "LandsIntegration", true, cl).isInstance(api)) throw new IllegalArgumentException("Not official LandsIntegration");
            integration = api; loader = cl; healthy = true; problem = "";
        } catch (ReflectiveOperationException | RuntimeException ex) { throw new IllegalArgumentException("Unsupported Lands API", ex); }
    }
    boolean available() {
        if (plugin == null) return healthy;
        Plugin lands = Bukkit.getPluginManager().getPlugin("Lands");
        if (!plugin.getConfig().getBoolean("integrations.lands", true) || lands == null || !lands.isEnabled()) {
            healthy = false; integration = null; problem = "Lands 未启用"; return false;
        }
        if (healthy && integration != null) return true;
        try {
            Class<?> type = Class.forName(ROOT + "LandsIntegration", true, lands.getClass().getClassLoader());
            bind(type.getMethod("of", Plugin.class).invoke(null, plugin)); return true;
        } catch (ReflectiveOperationException | RuntimeException ex) { failed(ex); return false; }
    }
    void refresh() { if (plugin != null) { healthy = false; integration = null; } }
    boolean healthy() { return healthy; }
    String problem() { available(); return problem; }
    boolean local(String key) { return key != null && key.startsWith(prefix); }
    private void failed(Exception ex) {
        String next = "Lands API 暂不可用: " + ex.getClass().getSimpleName();
        if (plugin != null && !next.equals(problem)) plugin.getLogger().warning(next);
        problem = next; healthy = false;
    }
    private Object invoke(String iface, Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = Class.forName(ROOT + iface, true, loader).getMethod(name, types);
        return method.invoke(target, args);
    }
    private Object invoke(String iface, Object target, String name) throws ReflectiveOperationException { return invoke(iface,target,name,new Class<?>[0]); }
    private String key(Object land) throws ReflectiveOperationException {
        Object ulid = invoke("memberholder.MemberHolder",land,"getULID");
        if (ulid == null || !ulid.toString().matches("[A-Za-z0-9]{26}")) throw new IllegalArgumentException("Invalid Lands ULID");
        return prefix + ulid;
    }
    private boolean trusted(Object land, UUID player) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(invoke("land.Land",land,"exists")) && (player.equals(invoke("memberholder.MemberHolder",land,"getOwnerUID"))
                || Boolean.TRUE.equals(invoke("memberholder.MemberHolder",land,"isTrusted",new Class<?>[]{UUID.class},player)));
    }
    private LandAffiliation snapshot(Object land) throws ReflectiveOperationException {
        Object name = invoke("land.Land",land,"getName");
        if (!(name instanceof String text)) throw new IllegalArgumentException("Invalid Lands name");
        return new LandAffiliation(key(land), text,
                ((Number)invoke("memberholder.MemberHolder",land,"getMembersAmount")).intValue());
    }
    /** All live local towns for public ranking; null means unavailable, not an empty catalog. */
    List<LandAffiliation> allLands() {
        if (!available()) return null;
        try {
            List<LandAffiliation> result = new ArrayList<>();
            for (Object land : (Collection<?>)invoke("LandsIntegration",integration,"getLands"))
                if (Boolean.TRUE.equals(invoke("land.Land",land,"exists"))) result.add(snapshot(land));
            return result.stream().distinct().sorted(Comparator.comparing(LandAffiliation::name).thenComparing(LandAffiliation::id)).toList();
        } catch (ReflectiveOperationException | RuntimeException ex) { failed(ex); return null; }
    }

    List<LandAffiliation> memberships(UUID player) {
        if (player == null || !available()) return List.of();
        try {
            Object data = invoke("LandsIntegration",integration,"getLandPlayer",new Class<?>[]{UUID.class},player);
            if (data == null) return List.of();
            List<LandAffiliation> result = new ArrayList<>();
            for (Object land : (Collection<?>)invoke("player.OfflinePlayer",data,"getLands")) if (trusted(land,player)) result.add(snapshot(land));
            return result.stream().distinct().sorted(Comparator.comparing(LandAffiliation::name).thenComparing(LandAffiliation::id)).toList();
        } catch (ReflectiveOperationException | RuntimeException ex) { failed(ex); return List.of(); }
    }
    LandAffiliation editing(UUID player) {
        if (player == null || !available()) return null;
        try {
            Object data = invoke("LandsIntegration",integration,"getLandPlayer",new Class<?>[]{UUID.class},player);
            if (data == null) return null;
            Object land = invoke("player.OfflinePlayer",data,"getEditLand");
            return land != null && trusted(land,player) ? snapshot(land) : null;
        } catch (ReflectiveOperationException | RuntimeException ex) { failed(ex); return null; }
    }
    boolean member(UUID player, String id) {
        if (player == null || !local(id) || !available()) return false;
        try {
            for (Object land : (Collection<?>)invoke("LandsIntegration",integration,"getLands"))
                if (id.equals(key(land))) return trusted(land,player);
            return false;
        } catch (ReflectiveOperationException | RuntimeException ex) { failed(ex); return false; }
    }
}

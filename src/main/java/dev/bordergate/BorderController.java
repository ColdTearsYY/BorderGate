package dev.bordergate;

import dev.bordergate.core.BorderMath;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;

final class BorderController implements Listener {
    record Snapshot(double diameter, double centerX, double centerZ) {}
    // Paper 26.3's seconds overload converts to an int number of ticks.
    static final long MAX_TRANSITION_SECONDS = Integer.MAX_VALUE / 20L;

    private static final String API_CLASS = "org.popcraft.chunkyborder.api.ChunkyBorderAPI";
    private final BorderGatePlugin plugin;
    private final boolean chunkyBorderSelected;
    private boolean updatingNative;
    private Object api;
    private Method getBorder;
    private Method addBorder;
    private String unavailableReason;

    BorderController(BorderGatePlugin plugin) {
        this.plugin = plugin;
        Plugin chunkyBorder = Bukkit.getPluginManager().getPlugin("ChunkyBorder");
        this.chunkyBorderSelected = plugin.getConfig().getBoolean("integrations.chunky-border", true)
                && chunkyBorder != null && chunkyBorder.isEnabled();
        if (chunkyBorderSelected) loadApi(chunkyBorder);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    boolean usesChunkyBorder() { return chunkyBorderSelected; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNativeChange(WorldBorderBoundsChangeEvent event) {
        if (!chunkyBorderSelected && !updatingNative && event.getWorld() != null
                && event.getWorld().getName().equals(plugin.managedWorldName())
                && event.getWorldBorder() == event.getWorld().getWorldBorder()) {
            plugin.onNativeBorderOverride();
        }
    }

    Optional<Snapshot> read(World world) {
        if (!chunkyBorderSelected) {
            WorldBorder border = world.getWorldBorder();
            return Optional.of(new Snapshot(border.getSize(), border.getCenter().getX(), border.getCenter().getZ()));
        }
        Object border = getBorder(world);
        if (border == null) return Optional.empty();
        double radiusX = number(border, "radiusX");
        double radiusZ = number(border, "radiusZ");
        double diameter = Math.max(radiusX, radiusZ) * 2;
        BorderMath.scaleRadii(radiusX, radiusZ, diameter);
        return Optional.of(new Snapshot(diameter, number(border, "centerX"), number(border, "centerZ")));
    }

    void initialize(World world, double diameter, double centerX, double centerZ, String initialShape) {
        if (!chunkyBorderSelected) {
            updatingNative = true;
            try {
                WorldBorder border = world.getWorldBorder();
                border.setCenter(centerX, centerZ);
                border.setSize(diameter);
            } finally { updatingNative = false; }
            return;
        }
        Object current = getBorder(world);
        String shape = current == null ? initialShape : string(current, "shape");
        BorderMath.Radii radii = current == null ? new BorderMath.Radii(diameter / 2, diameter / 2)
                : BorderMath.scaleRadii(number(current, "radiusX"), number(current, "radiusZ"), diameter);
        addBorder(world, shape, centerX, centerZ, radii.x(), radii.z());
        verifyDiameter(world, diameter);
    }

    void setDiameter(World world, double diameter, long transitionSeconds) {
        if (!chunkyBorderSelected) {
            updatingNative = true;
            try {
                WorldBorder border = world.getWorldBorder();
                if (transitionSeconds <= 0) border.setSize(diameter);
                else border.setSize(diameter, TimeUnit.SECONDS, Math.min(transitionSeconds, MAX_TRANSITION_SECONDS));
            } finally { updatingNative = false; }
            return;
        }
        Object current = getBorder(world);
        if (current == null) throw new IllegalStateException("ChunkyBorder has no border for " + world.getName());
        BorderMath.Radii radii = BorderMath.scaleRadii(number(current, "radiusX"), number(current, "radiusZ"), diameter);
        addBorder(world, string(current, "shape"), number(current, "centerX"), number(current, "centerZ"), radii.x(), radii.z());
        verifyDiameter(world, diameter);
    }

    private void loadApi(Plugin chunkyBorder) {
        try {
            Class<?> apiType = Class.forName(API_CLASS, true, chunkyBorder.getClass().getClassLoader());
            Object provider = Bukkit.getServicesManager().load(apiType);
            if (provider == null) throw new IllegalStateException("ChunkyBorder API service is not registered");
            Method reader = apiType.getMethod("getBorder", String.class);
            Method writer = apiType.getMethod("addBorder", String.class, String.class,
                    double.class, double.class, double.class, double.class);
            getBorder = reader;
            addBorder = writer;
            api = provider;
            plugin.getLogger().info("ChunkyBorder API detected; using its stored world border.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            unavailableReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            plugin.getLogger().severe("ChunkyBorder is enabled but its API is unavailable; border changes are suspended: " + unavailableReason);
        }
    }

    private Object getBorder(World world) {
        requireApi();
        try {
            Object result = getBorder.invoke(api, world.getName());
            if (!(result instanceof Optional<?> optional)) throw new IllegalStateException("ChunkyBorder API returned an invalid border result");
            return optional.orElse(null);
        } catch (ReflectiveOperationException | RuntimeException ex) { throw apiFailure("read", world, ex); }
    }

    private void addBorder(World world, String shape, double centerX, double centerZ, double radiusX, double radiusZ) {
        requireApi();
        try {
            Object result = addBorder.invoke(api, world.getName(), shape, centerX, centerZ, radiusX, radiusZ);
            if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("ChunkyBorder rejected the border update");
        } catch (ReflectiveOperationException | RuntimeException ex) { throw apiFailure("update", world, ex); }
    }

    private void verifyDiameter(World world, double expected) {
        Snapshot updated = read(world).orElseThrow(() -> new IllegalStateException("ChunkyBorder did not retain the updated border"));
        if (Math.abs(updated.diameter() - expected) > Math.max(0.01, expected * 1.0e-9)) {
            throw new IllegalStateException("ChunkyBorder stored diameter " + updated.diameter() + " instead of " + expected);
        }
    }

    private double number(Object border, String accessor) {
        Object value = invokeAccessor(border, accessor);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException("ChunkyBorder returned invalid " + accessor);
        }
        return number.doubleValue();
    }

    private String string(Object border, String accessor) {
        Object value = invokeAccessor(border, accessor);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException("ChunkyBorder returned invalid " + accessor);
        return text;
    }

    private Object invokeAccessor(Object border, String accessor) {
        try { return border.getClass().getMethod(accessor).invoke(border); }
        catch (ReflectiveOperationException | RuntimeException ex) { throw new IllegalStateException("Could not read ChunkyBorder " + accessor, unwrap(ex)); }
    }

    private IllegalStateException apiFailure(String action, World world, Exception ex) {
        return new IllegalStateException("Could not " + action + " ChunkyBorder border for " + world.getName()
                + "; changes are suspended: " + unwrap(ex).getMessage(), unwrap(ex));
    }

    private Throwable unwrap(Exception ex) {
        if (ex instanceof InvocationTargetException invocation && invocation.getCause() != null) return invocation.getCause();
        return ex;
    }

    private void requireApi() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ChunkyBorder")) {
            throw new IllegalStateException("ChunkyBorder is selected but disabled; changes are suspended");
        }
        if (api == null) throw new IllegalStateException("ChunkyBorder is selected but unavailable; changes are suspended: " + unavailableReason);
    }
}

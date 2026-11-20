package dev.bordergate.core;

/**
 * Externally owned Lands territory identity.
 *
 * <p>The id is an opaque, server-scoped handle of the form {@code lands:<ULID>}; the ledger never
 * invents people, rosters or membership rules of its own. {@code members} is only the externally
 * reported member count carried for display, so it is never capped or enforced here and no
 * internal team/roster/slot system exists behind it.
 */
public record LandAffiliation(String id, String name, int members) {
    private static final String PREFIX = "lands:";
    private static final int MAX_ID = 120;
    private static final int MAX_NAME = 64;

    public LandAffiliation {
        if (!validId(id)) throw new IllegalArgumentException("Invalid Lands affiliation id: " + id);
        if (name == null || name.isBlank() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("Invalid Lands affiliation name: " + name);
        if (members < 0) throw new IllegalArgumentException("Invalid Lands affiliation member count: " + members);
    }

    /** Externally-scoped territory handle: {@code lands:} followed by bounded identifier characters. */
    public static boolean validId(String value) {
        return value != null && value.length() <= MAX_ID && value.startsWith(PREFIX)
                && value.substring(PREFIX.length()).matches("[A-Za-z0-9_.:-]{1,114}");
    }

    /** True when the pair may describe an external Lands territory; blank ids stay personal. */
    public static boolean validClaimLand(String landId, String landName) {
        return validId(landId) && landName != null && !landName.isBlank() && landName.length() <= MAX_NAME;
    }
}

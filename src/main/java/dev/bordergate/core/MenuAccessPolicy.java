package dev.bordergate.core;

/** Entry permissions are separate from permissions to participate inside an open menu. */
public final class MenuAccessPolicy {
    private MenuAccessPolicy() {}

    public static boolean canUseCommands(boolean usePermission, boolean commandPermission) {
        return usePermission && commandPermission;
    }

    public static boolean canAutoOpen(boolean openOnJoin, boolean usePermission, boolean commandPermission) {
        return openOnJoin && canUseCommands(usePermission, commandPermission);
    }
}

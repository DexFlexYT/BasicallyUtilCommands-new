package org.dexflex.basicallycommandutils.util;

import net.minecraft.world.entity.Entity;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class VelocitySync {
    // Resolved once; after that it's JIT-friendly, close to a direct field write
    private static final VarHandle FLAG = resolve();

    private static VarHandle resolve() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        // Newest name first, then the old one
        for (String name : new String[] { "syncVelocity", "hurtMarked" }) {
            try {
                return lookup.findVarHandle(Entity.class, name, boolean.class);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        throw new IllegalStateException(
                "Neither Entity.syncVelocity nor Entity.hurtMarked exists in this Minecraft version");
    }

    public static void markDirty(Entity e) {
        FLAG.set(e, true);
    }
}
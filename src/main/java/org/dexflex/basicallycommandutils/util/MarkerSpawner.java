package org.dexflex.basicallycommandutils.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class MarkerSpawner {
    private static final Object MARKER_TYPE;
    private static final Constructor<?> CTOR;

    static {
        Object type = null;
        for (String n : new String[] {
                "net.minecraft.world.entity.EntityTypes",
                "net.minecraft.world.entity.EntityType" }) {
            try {
                Class<?> c = Class.forName(n);
                Field f = c.getField("MARKER");
                if (Modifier.isStatic(f.getModifiers())) {
                    type = f.get(null);
                    break;
                }
            } catch (ReflectiveOperationException ignored) {
                // class missing or no MARKER here, try the next one
            }
        }
        if (type == null) {
            throw new IllegalStateException("Could not find a static MARKER entity type on this Minecraft version");
        }
        MARKER_TYPE = type;

        try {
            Constructor<?> found = null;
            for (Constructor<?> c : Class.forName("net.minecraft.world.entity.Marker").getConstructors()) {
                if (c.getParameterCount() == 2) { found = c; break; }
            }
            if (found == null) throw new IllegalStateException("No Marker(type, level) constructor");
            CTOR = found;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Entity spawn(ServerLevel level, double x, double y, double z, String tag) {
        try {
            Entity e = (Entity) CTOR.newInstance(MARKER_TYPE, level);
            e.setPos(x, y, z);
            e.addTag(tag);
            level.addFreshEntity(e);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
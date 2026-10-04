package dev.tide.spawnselectortests;

import java.lang.reflect.*;
import java.util.*;
import dev.tide.spawnselector.*;
import net.minecraft.nbt.CompoundTag;

/** Test-only reflection avoids exposing production internals or splitting JPMS packages. */
final class TestAccess {
    static Object field(Object target,String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch(ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    @SuppressWarnings("unchecked") static <T> T call(Object target,String name,Object...args) {
        try {
            Class<?> type=target instanceof Class<?> c?c:target.getClass();
            Method m=Arrays.stream(type.getDeclaredMethods()).filter(method->method.getName().equals(name)
                && matches(method.getParameterTypes(),args)).findFirst().orElseThrow();
            m.setAccessible(true); return (T)m.invoke(target instanceof Class<?>?null:target,args);
        } catch(InvocationTargetException ex) { throw new AssertionError(ex.getCause()); }
        catch(ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    static Class<?> type(String name) {
        try { return Class.forName("dev.tide.spawnselector."+name); }
        catch(ClassNotFoundException ex) { throw new AssertionError(ex); }
    }
    static Object create(String name,Object...args) {
        try {
            Class<?> type=Class.forName("dev.tide.spawnselector."+name);
            Constructor<?> c=Arrays.stream(type.getDeclaredConstructors()).filter(con->matches(con.getParameterTypes(),args)).findFirst().orElseThrow();
            c.setAccessible(true); return c.newInstance(args);
        } catch(ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    private static boolean matches(Class<?>[] types,Object[] args) {
        if(types.length!=args.length) return false;
        for(int i=0;i<types.length;i++) {
            if(args[i]==null) continue;
            if(types[i].isPrimitive()) { if(!(args[i] instanceof Number || args[i] instanceof Boolean)) return false; }
            else if(!types[i].isInstance(args[i])) return false;
        }
        return true;
    }
    static PlayerSelections load(CompoundTag tag) { return (PlayerSelections)create("PlayerSelections",tag); }
    static StructureCandidatePool pool(PlayerSelections data) { return (StructureCandidatePool)field(data,"pool"); }
}

package dev.tide.tests;
import cpw.mods.modlauncher.api.ServiceRunner;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.targets.ForgeClientUserdevLaunchHandler;
import java.lang.reflect.*;
import java.util.*;
/** Tests production SRG classes supplied by ForgeGradle; no client window or user saves. */
public final class HeadlessLaunch extends ForgeClientUserdevLaunchHandler {
    @Override public String name() { return "spawnselectortest"; }
    @Override public Dist getDist() { return Dist.DEDICATED_SERVER; }
    @Override public String getNaming() { return "srg"; }
    @Override public boolean isProduction() { return true; }
    @Override protected ServiceRunner makeService(String[] args,ModuleLayer layer) {
        return () -> {
            ClassLoader loader=layer.findLoader("minecraft");
            for(String type:new String[]{"net.minecraft.server.MinecraftServer","net.minecraft.server.level.ServerPlayer","net.minecraft.server.players.PlayerList","net.minecraft.world.entity.Entity","net.minecraft.server.level.ServerChunkCache"}) {
                Class.forName(type,false,loader);
                System.out.println("SPAWNSELECTOR_MIXIN_LOADED "+type);
            }
            hookRegression(loader);
            // No server startup, world creation, EULA acceptance or save access.
        };
    }
    private static Method hook(Class<?> owner,String suffix) {
        Method method=Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().endsWith("$"+suffix)).findFirst().orElseThrow();
        method.setAccessible(true);return method;
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    /** Execute the actual transformed guards/wrapper using uninitialized world fixtures. */
    private static void hookRegression(ClassLoader loader) throws Throwable {
        Class.forName("net.minecraft.SharedConstants",true,loader).getMethod("m_142977_").invoke(null);
        Class<?> bootstrap=Class.forName("net.minecraft.server.Bootstrap",true,loader);
        bootstrap.getMethod("m_135870_").invoke(null);
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);Method allocate=unsafeType.getMethod("allocateInstance",Class.class);
        Class<?> serverType=Class.forName("net.minecraft.server.MinecraftServer",false,loader);
        Object server=allocate.invoke(unsafe,Class.forName("net.minecraft.server.dedicated.DedicatedServer",true,loader));
        Class<?> dimensions=Class.forName("dev.tide.spawnselector.SpawnSelectorDimensions",true,loader);
        Object holding=dimensions.getField("HOLDING").get(null),position=dimensions.getField("HOLDING_POS").get(null);
        Class<?> levelType=Class.forName("net.minecraft.server.level.ServerLevel",true,loader);
        Object level=allocate.invoke(unsafe,levelType);
        Field levels=Arrays.stream(serverType.getDeclaredFields()).filter(f->Map.class.isAssignableFrom(f.getType())&&f.getGenericType().getTypeName().contains("ServerLevel")).findFirst().orElseThrow();
        levels.setAccessible(true);Map<Object,Object> worlds=new HashMap<>();levels.set(server,worlds);
        Method wait=hook(serverType,"skipWait"),start=hook(serverType,"skipStart");
        Class<?> ticket=Class.forName("net.minecraft.server.level.TicketType",true,loader);
        Object startType=null;
        for(Field field:ticket.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==ticket){field.setAccessible(true);Object value=field.get(null);if(value.toString().equals("start"))startType=value;}
        check(startType!=null,"START ticket fixture");
        for(boolean deferred:new boolean[]{false,true}) {
            worlds.clear();if(deferred)worlds.put(holding,level);
            for(int target:new int[]{441,289})for(int count:new int[]{0,1,289,441,512})
                check((boolean)wait.invoke(server,count!=target)==(!deferred&&count!=target),"Loop guard must preserve comparison or skip, regardless of count/target");
            check((boolean)start.invoke(server,null,startType,null,11,null)==!deferred,"Only deferred START is skipped");
            check((boolean)start.invoke(server,null,null,null,11,null),"Unrelated ticket call is preserved");
        }
        Class<?> baseLevel=Class.forName("net.minecraft.world.level.Level",true,loader);
        Field dimension=Arrays.stream(baseLevel.getDeclaredFields()).filter(f->!Modifier.isStatic(f.getModifiers())&&f.getType().getName().equals("net.minecraft.resources.ResourceKey")&&f.getGenericType().getTypeName().contains("net.minecraft.world.level.Level")).findFirst().orElseThrow();dimension.setAccessible(true);
        Object other=null;
        for(Field field:baseLevel.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType().getName().equals("net.minecraft.resources.ResourceKey")){field.setAccessible(true);other=field.get(null);break;}
        check(other!=null&&!other.equals(holding),"Non-holding dimension fixture");
        Class<?> operation=Class.forName("com.llamalad7.mixinextras.injector.wrapoperation.Operation",true,loader);
        int[] calls={0};Object original=Proxy.newProxyInstance(loader,new Class<?>[]{operation},(proxy,method,args)->{
            if(method.getName().equals("call")){check(((Object[])args[0]).length==1&&((Object[])args[0])[0]==level,"Original receives the same level");calls[0]++;return position;}
            throw new UnsupportedOperationException(method.getName());
        });
        Method constructor=hook(Class.forName("net.minecraft.server.level.ServerPlayer",false,loader),"constructionPosition");
        dimension.set(level,holding);check(constructor.invoke(null,level,original).equals(position)&&calls[0]==0,"Holding construction must not read shared spawn/heightmap");
        dimension.set(level,other);check(constructor.invoke(null,level,original).equals(position)&&calls[0]==1,"Normal construction invokes the supplied operation once");
        System.out.println("SPAWNSELECTOR_HOOK_REGRESSION PASS: actual loop guard counts/targets, START condition, static constructor operation");
    }
}

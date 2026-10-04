import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.objectweb.asm.*;

/** Resolves bytecode references without loading Minecraft, launching the game, or touching saves. */
public class LinkageAudit {
    static final Map<String, byte[]> classes = new HashMap<>();
    static final Map<String, Info> infos = new HashMap<>();
    static final Set<String> failures = new TreeSet<>();
    static int checked;
    record Info(String parent, String[] interfaces, Set<String> methods, Set<String> fields) {}
    static boolean relevant(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge/") || owner.startsWith("dev/tide/");
    }
    static Info info(String name) {
        if (name == null) return null;
        if (infos.containsKey(name)) return infos.get(name);
        byte[] data=classes.get(name);
        if (data==null && name.startsWith("java/")) {
            try (InputStream stream=ClassLoader.getSystemResourceAsStream(name+".class")) {
                if(stream!=null) data=stream.readAllBytes();
            } catch(IOException ex) { throw new UncheckedIOException(ex); }
        }
        if (data==null) return null;
        ClassReader cr=new ClassReader(data);
        Set<String> methods=new HashSet<>(), fields=new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) { methods.add(n+d); return null; }
            @Override public FieldVisitor visitField(int a,String n,String d,String s,Object v) { fields.add(n+d); return null; }
        },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        Info result=new Info(cr.getSuperName(),cr.getInterfaces(),methods,fields); infos.put(name,result); return result;
    }
    static boolean resolves(String owner,String name,String desc,boolean method,Set<String> visited) {
        if (owner==null || !visited.add(owner)) return false;
        if (owner.equals("java/lang/Object")) return Set.of("<init>()V","equals(Ljava/lang/Object;)Z","hashCode()I","toString()Ljava/lang/String;","getClass()Ljava/lang/Class;").contains(name+desc);
        Info i=info(owner); if (i==null) return false;
        if ((method?i.methods:i.fields).contains(name+desc)) return true;
        if (name.equals("<init>")) return false;
        if(resolves(i.parent,name,desc,method,visited)) return true;
        for(String parent:i.interfaces) if(resolves(parent,name,desc,method,visited)) return true;
        return false;
    }
    static void check(String caller,String owner,String name,String desc,boolean method) {
        if (!relevant(owner)) return;
        checked++;
        if (!resolves(owner,name,desc,method,new HashSet<>())) failures.add(caller+" -> "+owner+"."+name+desc);
    }
    static void handle(String caller,Handle h) {
        check(caller,h.getOwner(),h.getName(),h.getDesc(),h.getTag()>=Opcodes.H_INVOKEVIRTUAL);
    }
    public static void main(String[] args)throws Exception {
        List<String> paths=Files.readAllLines(Path.of(args[0]));
        for(String path:paths)try(ZipFile jar=new ZipFile(path)) {
            var entries=jar.entries();
            while(entries.hasMoreElements()) {
                var e=entries.nextElement();
                if(e.getName().endsWith(".class")&&!e.getName().startsWith("META-INF/"))
                    classes.putIfAbsent(e.getName().substring(0,e.getName().length()-6),jar.getInputStream(e).readAllBytes());
            }
        }
        for(var entry:classes.entrySet()) {
            if(!entry.getKey().startsWith("dev/tide/"))continue;
            String caller=entry.getKey();
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a,String name,String d,String s,String[] e) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op,String owner,String n,String desc,boolean itf) { check(caller,owner,n,desc,true); }
                        @Override public void visitFieldInsn(int op,String owner,String n,String desc) { check(caller,owner,n,desc,false); }
                        @Override public void visitInvokeDynamicInsn(String n,String desc,Handle b,Object... values) {
                            handle(caller,b); for(Object value:values)if(value instanceof Handle h)handle(caller,h);
                        }
                    };
                }
            },0);
        }
        failures.forEach(System.err::println);
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" unresolved references");
        System.out.println("PASS: "+checked+" runtime Minecraft/Forge/selector references resolved against supplied dependency jars");
    }
}

import java.lang.reflect.Method;
import java.util.UUID;
import dev.tide.spawnselector.compat.OriginsCompat;

/** Exercises compatibility-failure policy without requiring Origins or a live world. */
public final class OriginsCompatTest {
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Method failure=OriginsCompat.class.getDeclaredMethod("failSoft",UUID.class,Throwable.class);
        Method shape=OriginsCompat.class.getDeclaredMethod("isCompatibilityFailure",Throwable.class);
        failure.setAccessible(true); shape.setAccessible(true);
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        Throwable temporary=new IllegalStateException("Deliberate readiness invocation failure fixture");
        check(!(Boolean)failure.invoke(null,alice,temporary),"First temporary failure retries");
        check(!(Boolean)failure.invoke(null,alice,temporary),"Second temporary failure retries");
        check((Boolean)failure.invoke(null,alice,temporary),"Third API failure bypasses Alice");
        check(!(Boolean)failure.invoke(null,bob,temporary),"Alice's API error cannot bypass Bob");
        OriginsCompat.forget(alice);
        check(!(Boolean)failure.invoke(null,alice,temporary),"Reconnect resets temporary compatibility state");
        check((Boolean)shape.invoke(null,new NoSuchMethodError()),"Broken linkage is a compatibility failure");
        check((Boolean)shape.invoke(null,new ClassCastException()),"Broken API shape is a compatibility failure");
        check(!(Boolean)shape.invoke(null,temporary),"Ordinary invocation failures use bounded per-player retry");
        OriginsCompat.forget(alice); OriginsCompat.forget(bob);
        System.out.println("PASS: Origins failure classification, bounded API fallback, per-player isolation, reconnect reset");
    }
}

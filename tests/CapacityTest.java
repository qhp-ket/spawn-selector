import dev.tide.spawnselector.ClaimLedger;
import dev.tide.spawnselector.StructureInstanceId;
import java.util.*;

public final class CapacityTest {
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        UUID alice=UUID.randomUUID(), bob=UUID.randomUUID(), carol=UUID.randomUUID();
        for(int capacity:new int[]{1,2,-1}) {
            ClaimLedger ledger=new ClaimLedger();
            check(ledger.claim(alice,capacity),"Alice's first claim");
            check(ledger.claim(alice,capacity) && ledger.size()==1,"Repeated claim is idempotent");
            boolean bobWon=ledger.claim(bob,capacity);
            check(bobWon==(capacity!=1),"Bob observes Alice's server-thread commit");
            boolean carolWon=ledger.claim(carol,capacity);
            check(carolWon==(capacity==-1),"Only one winner for the last bounded slot");
            ledger.complete(alice); ledger.release(alice);
            check(ledger.completed(alice) && ledger.size()>=1,"Completed selection is permanent");
            ledger.release(bob); ledger.recover();
            check(ledger.size()==1 && ledger.completed(alice),"Cancel/disconnect/restart releases reservations only");
            ClaimLedger restarted=new ClaimLedger();
            ledger.snapshot().forEach(restarted::restore); restarted.recover();
            check(restarted.snapshot().equals(ledger.snapshot()),"Permanent claims survive serialization policy");
        }
        for(int i=0;i<10000;i++) {
            ClaimLedger ledger=new ClaimLedger();
            List<UUID> players=new ArrayList<>(List.of(alice,bob,carol)); Collections.shuffle(players,new Random(i));
            int winners=0; for(UUID player:players) if(ledger.claim(player,1)) winners++;
            check(winners==1,"Randomized final-slot ordering cannot overbook");
        }
        var a=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",-15,28);
        var same=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",-15,28);
        var b=new StructureInstanceId("minecraft:overworld","minecraft:village_plains",-14,28);
        var otherDimension=new StructureInstanceId("mod:world","minecraft:village_plains",-15,28);
        check(a.equals(same) && a.key().equals(same.key()),"Stable instance identity");
        check(!a.equals(b) && !a.equals(otherDimension),"Different starts/dimensions stay independent");
        System.out.println("PASS: capacities 1/2/-1, final-slot races in server-thread order, release, restart, permanent claims, stable IDs");
    }
}

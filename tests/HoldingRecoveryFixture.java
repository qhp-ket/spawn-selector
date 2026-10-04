import java.nio.file.*;
import java.util.UUID;
import net.minecraft.nbt.*;

/** Deliberately rewinds only our stopped test player's NBT, leaving completed SavedData intact. */
public final class HoldingRecoveryFixture {
    private static void holding(CompoundTag player) {
        player.putString("Dimension","spawnselector:holding");
        ListTag position=new ListTag();
        for(double value:new double[]{8.5,64,8.5}) position.add(DoubleTag.valueOf(value));
        player.put("Pos",position);
        for(String key:new String[]{"SpawnX","SpawnY","SpawnZ","SpawnAngle","SpawnForced","SpawnDimension"}) player.remove(key);
    }
    public static void main(String[] args) throws Exception {
        Path permitted=Path.of("run/client/saves").toAbsolutePath().normalize();
        Path world=Path.of(args[0]).toAbsolutePath().normalize();
        if(!world.startsWith(permitted) || world.equals(permitted)) throw new IllegalArgumentException("Fixture must stay inside project client saves");
        try(var channel=java.nio.channels.FileChannel.open(world.resolve("session.lock"),StandardOpenOption.WRITE);
            var lock=channel.tryLock()) {
            if(lock==null) throw new IllegalStateException("Stop the test world before mutating its fixture");
            Path level=world.resolve("level.dat");
            Files.copy(level,world.resolve("level.pre-recovery.dat"),StandardCopyOption.REPLACE_EXISTING);
            CompoundTag root=NbtIo.readCompressed(level.toFile());
            CompoundTag player=root.getCompound("Data").getCompound("Player");
            if(!player.contains("UUID")) throw new IllegalStateException("Missing test player");
            UUID id=player.getUUID("UUID"); holding(player); NbtIo.writeCompressed(root,level.toFile());
            Path standalone=world.resolve("playerdata/"+id+".dat");
            if(Files.exists(standalone)) {
                Files.copy(standalone,world.resolve("player.pre-recovery.dat"),StandardCopyOption.REPLACE_EXISTING);
                CompoundTag tag=NbtIo.readCompressed(standalone.toFile()); holding(tag); NbtIo.writeCompressed(tag,standalone.toFile());
            }
        }
        System.out.println("Prepared stopped project test world's stale-holding recovery fixture");
    }
}

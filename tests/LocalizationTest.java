package dev.tide.spawnselector;

import com.google.gson.*;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;

/** Exercise real component serialization and language resolution without launching Minecraft. */
public final class LocalizationTest {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void language(Path path) throws Exception {
        var json=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String,String> strings=new HashMap<>();json.entrySet().forEach(e->strings.put(e.getKey(),e.getValue().getAsString()));
        Language.inject(new Language() {
            @Override public String getOrDefault(String key) { return strings.getOrDefault(key,key); }
            @Override public String getOrDefault(String key,String fallback) { return strings.getOrDefault(key,fallback); }
            @Override public boolean has(String key) { return strings.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
    }
    public static void main(String[] args) throws Exception {
        Path resources=Path.of(args[0]);Language original=Language.getInstance();
        try {
            var name=Component.translatable("spawnselector.entry.village.name");
            var packet=new Packets.View(7,Component.translatable("spawnselector.message.locating",name),
                java.util.stream.IntStream.rangeClosed(1,3).mapToObj(number -> new Packets.Card("instance-token-"+number,Component.translatable("spawnselector.instance.name",name,number),
                    Component.translatable("spawnselector.instance.description",128,-240,
                        Component.translatable("spawnselector.instance.capacity",1,2)),"minecraft:emerald","")).toList(),
                SpawnUiConfig.defaults(),"minecraft:overworld",-124L,"ack-token");
            FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
            Packets.View decoded;
            try {
                packet.encode(buffer);decoded=Packets.View.decode(buffer);
                check(!buffer.isReadable(),"Component packet must consume the exact payload");
            } finally { buffer.release(); }
            check(decoded.state()==7 && decoded.revealToken().equals("ack-token") && decoded.revealPosition()==-124L,"Wire lifecycle fields survive");
            check(Component.Serializer.toJson(decoded.message()).contains("translate"),"Wire must preserve keys and nested components");
            language(resources.resolve("assets/spawnselector/lang/en_us.json"));
            check(decoded.message().getString().equals("Locating Village…"),"Same server packet resolves in English");
            check(decoded.cards().get(0).description().getString().equals("Approx. position (128, -240) · 1/2 players"),"Nested parameters resolve in English");
            for(int i=0;i<3;i++) check(decoded.cards().get(i).name().getString().equals("Village · Location "+(i+1)),"All three instance names resolve in English");
            language(resources.resolve("assets/spawnselector/lang/zh_cn.json"));
            check(decoded.message().getString().contains(Component.translatable("spawnselector.entry.village.name").getString()) && !decoded.message().getString().contains("spawnselector."),"Same decoded packet follows a second client's language");
            for(int i=0;i<3;i++) check(decoded.cards().get(i).name().getString().equals("村庄 · 地点 "+(i+1)),"All three instance names resolve in Chinese");
            check(decoded.cards().get(0).description().getString().equals("位置约 (128, -240) · 1/2 人"),"Coordinates and capacity resolve in Chinese");
            JsonElement styled=JsonParser.parseString("{\"text\":\"\",\"extra\":[{\"text\":\"Warning\\n\",\"color\":\"#FFAA00\",\"bold\":true},{\"text\":\"Details\",\"italic\":true,\"underlined\":true}]}");
            Component text=Component.Serializer.fromJson(styled);
            check(text.getString().equals("Warning\nDetails"),"Native arbitrary component text and newlines");
            check(ConfigComponents.component(styled).equals(text),"Read-only preview retains styles");
            check(ConfigComponents.mode(styled).equals("custom"),"Literal descriptions explicitly marked untranslated");
            check(ConfigComponents.mode(JsonParser.parseString("[{\"translate\":\"spawnselector.entry.village.name\"},{\"text\":\"!\"}]")).equals("mixed"),"Mixed text mode remains supported");
            JsonElement translated=JsonParser.parseString("{\"translate\":\"spawnselector.entry.village.name\",\"color\":\"#123456\",\"bold\":true,\"italic\":true}");
            String shown=ConfigComponents.text(translated,"");
            check(ConfigComponents.editedName(translated,shown,shown).equals(translated),"Untouched name retains exact translation and style JSON");
            JsonElement renamed=ConfigComponents.editedName(translated,shown,"Custom village");
            Component renamedComponent=ConfigComponents.component(renamed);
            check(renamedComponent.getString().equals("Custom village")&&!renamed.getAsJsonObject().has("translate"),"GUI name edit converts i18n to literal content");
            check(renamedComponent.getStyle().equals(ConfigComponents.component(translated).getStyle()),"GUI name edit retains original color, bold and italic");
            check(ConfigComponents.isRename(translated,renamed),"String-only rename can be distinguished for merging latest disk styles");
            check(ConfigComponents.editedName(styled,"Warning\nDetails","Warning\nDetails").equals(styled),"Untouched compound text remains exact");
            JsonElement segments=JsonParser.parseString("[{\"text\":\"First\",\"color\":\"gold\",\"bold\":true},{\"text\":\"Second\",\"color\":\"red\"}]");
            check(ConfigComponents.component(ConfigComponents.rename(segments,"Replacement")).getStyle().equals(ConfigComponents.component(segments).getStyle()),"Replacing a compound name keeps its root/first segment style");
            check(ConfigComponents.editedName(translated,shown,shown).equals(translated),"Reverting typed text to initial value restores the original component");
            var defaults=JsonParser.parseString(Files.readString(resources.resolve("default_spawnselector.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:defaults.getAsJsonObject("entries").entrySet()) {
                var entry=SpawnEntry.parse(new net.minecraft.resources.ResourceLocation(value.getKey()),value.getValue().getAsJsonObject());
                check(!entry.name().getString().contains("spawnselector."),"Default entry name resolves");
                check(!entry.description().getString().contains("spawnselector."),"Default entry description resolves");
                check(Component.Serializer.toJson(entry.name()).contains("translate")==value.getKey().equals("spawnselector:village"),"Only village's default name uses i18n");
                check(Component.Serializer.toJson(entry.description()).contains("spawnselector.entry.village.description"),"Default village description retains its translation key");
                check(entry.description().getString().equals("在村庄外围寻找安全的地表出生点。"),"Default village description resolves in Chinese");
                language(resources.resolve("assets/spawnselector/lang/en_us.json"));
                check(entry.description().getString().equals("Find a safe surface spawn outside a village."),"Same default village description resolves in English");
                language(resources.resolve("assets/spawnselector/lang/zh_cn.json"));
            }
            System.out.println("PASS: actual component packet round trip, nested arguments, per-client English/Chinese resolution, native styled text and read-only preview");
        } finally { Language.inject(original); }
    }
}

package dev.tide.spawnselector;

import com.google.gson.*;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.fml.loading.FMLPaths;

/** Runtime and GUI share one loader; only an explicit GUI save writes or migrates files. */
final class ExternalConfig {
    private static ConfigFiles files(){return new ConfigFiles(FMLPaths.CONFIGDIR.get());}
    private static JsonObject lastGood;
    record EditorData(JsonObject root,String error) {}
    private ExternalConfig() {}
    static JsonObject load() {
        try{JsonObject loaded=files().read().root();lastGood=loaded.deepCopy();return loaded;}
        catch(IOException|RuntimeException ex){SpawnSelector.LOG.error("Invalid Spawn Selector config; bundled/data-pack defaults or last valid overrides remain active",ex);return lastGood==null?new JsonObject():lastGood.deepCopy();}
    }
    static EditorData loadForEditor() {
        JsonObject defaults=defaults();
        try{JsonObject loaded=resolve(defaults,files().read().root());validate(loaded);return new EditorData(loaded,"");}
        catch(IOException|RuntimeException ex){
            SpawnSelector.LOG.error("Cannot edit invalid Spawn Selector config",ex);
            return new EditorData(defaults,Component.translatable("spawnselector.config.invalid_file").getString()+" "+ex.getMessage());
        }
    }
    static JsonObject defaults() {
        return resource("default_spawnselector.json");
    }
    private static JsonObject resource(String name) {
        try(InputStream stream=ExternalConfig.class.getClassLoader().getResourceAsStream(name)) {
            if(stream==null)throw new IllegalStateException("Missing bundled defaults");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static final class Legacy {
        static final JsonObject ENTRIES=resource("legacy_spawn_entries.json");
    }
    /** Retired bundled entries are templates only, never automatically offered to new users. */
    static JsonObject legacyEntry(String id) {
        JsonElement value=Legacy.ENTRIES.get(id);
        return value==null?null:value.getAsJsonObject().deepCopy();
    }
    static JsonObject resolve(JsonObject defaults,JsonObject overrides) {
        JsonObject result=merge(defaults,overrides);
        JsonObject entries=section(result,"entries"),bundled=section(defaults,"entries");
        for(var entry:section(overrides,"entries").entrySet()) {
            JsonObject legacy=legacyEntry(entry.getKey());
            if(!bundled.has(entry.getKey())&&legacy!=null&&entry.getValue().isJsonObject())
                entries.add(entry.getKey(),merge(legacy,entry.getValue().getAsJsonObject()));
        }
        if(result.has("entries"))result.add("entries",entries);
        return result;
    }
    static void save(JsonObject resolved,JsonObject baseline) throws IOException {files().save(resolved,baseline,defaults());}
    static Path directory(){return files().directory();}
    static Path ensureDirectory() throws IOException{return files().ensureDirectory();}
    static Path settings(){return files().settings();}
    static Path entryFile(String id) throws IOException{return files().entryPath(id,files().read());}
    static void validate(JsonObject root) {
        for(var e:section(root,"entries").entrySet()) {
            try {
                JsonObject entry=e.getValue().getAsJsonObject();
                for(String key:List.of("target","required_mod","icon","dimension","locator","preview","order","search_radius","min_distance","max_distance","preferred_side","candidate_count","capacity_per_instance","enabled","require_open_sky"))
                    if(entry.has(key)&&!entry.get(key).isJsonPrimitive())throw new IllegalArgumentException("Expected scalar field: "+key);
                if(entry.has("exclusions"))for(JsonElement filter:entry.getAsJsonArray("exclusions"))
                    if(!filter.isJsonPrimitive())throw new IllegalArgumentException("Expected scalar exclusion");
                if(!entry.has("enabled")||entry.get("enabled").getAsBoolean())SpawnEntry.parse(new net.minecraft.resources.ResourceLocation(ConfigFiles.normalizeId(e.getKey())),entry);
            }catch(RuntimeException ex){throw new IllegalArgumentException(e.getKey()+": "+ex.getMessage(),ex);}
        }
        JsonObject layout=section(root,"layout");
        String[] keys={"entries_per_page","panel_width","card_height","preview_width","preview_height"};
        int[][] bounds={{1,10},{240,800},{20,40},{32,512},{18,288}};
        for(int i=0;i<keys.length;i++)if(layout.has(keys[i])) {
            int value=layout.get(keys[i]).getAsInt();if(value<bounds[i][0]||value>bounds[i][1])throw new IllegalArgumentException("Invalid layout value: "+keys[i]);
        }
        for(String key:List.of("allow_permanent_skip","show_icons","show_previews"))if(layout.has(key))layout.get(key).getAsBoolean();
        for(String key:List.of("title","confirm_label","back_label","skip_label","empty_message","footer"))
            SpawnUiConfig.component(layout,key,Component.empty(),key.equals("footer")||key.equals("empty_message")?1024:512);
    }
    static JsonObject section(JsonObject root,String key) {
        JsonElement value=root.get(key);
        if(value==null)return new JsonObject();
        if(!value.isJsonObject())throw new IllegalArgumentException(key+" must be an object");
        return value.getAsJsonObject();
    }
    private static boolean componentKey(String key) {
        return Set.of("name","description","title","confirm_label","back_label","skip_label","empty_message","footer").contains(key);
    }
    private static boolean componentValue(JsonElement value) {
        return value.isJsonPrimitive()||value.isJsonArray()||(value.isJsonObject()&&(value.getAsJsonObject().has("text")||value.getAsJsonObject().has("translate")));
    }
    static JsonElement diff(JsonElement before,JsonElement after) {
        if(Objects.equals(before,after))return null;
        if(before!=null&&before.isJsonObject()&&after!=null&&after.isJsonObject()) {
            JsonObject result=new JsonObject(),a=before.getAsJsonObject(),b=after.getAsJsonObject();
            for(String key:a.keySet())if(!b.has(key))result.add(key,JsonNull.INSTANCE);
            for(var e:b.entrySet()) {
                JsonElement delta=componentKey(e.getKey())?Objects.equals(a.get(e.getKey()),e.getValue())?null:e.getValue().deepCopy():diff(a.get(e.getKey()),e.getValue());
                if(delta!=null)result.add(e.getKey(),delta);
            }
            return result.size()==0?null:result;
        }
        return after==null?JsonNull.INSTANCE:after.deepCopy();
    }
    static JsonObject merge(JsonObject base,JsonObject override) {
        JsonObject result=base.deepCopy();
        for(var e:override.entrySet()) {
            JsonElement previous=result.get(e.getKey()),value=e.getValue();
            if(value.isJsonNull())result.remove(e.getKey());
            else if(previous!=null&&previous.isJsonObject()&&value.isJsonObject()&&!(componentKey(e.getKey())&&componentValue(value)))
                result.add(e.getKey(),merge(previous.getAsJsonObject(),value.getAsJsonObject()));
            else result.add(e.getKey(),value.deepCopy());
        }
        return result;
    }
}

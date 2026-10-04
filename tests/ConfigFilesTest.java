package dev.tide.spawnselector;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Actual file migration and stale-editor saves, entirely under Gradle's build directory. */
public final class ConfigFilesTest {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JsonObject read(Path file)throws Exception{return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void write(Path file,JsonObject value)throws Exception{Files.writeString(file,JSON.toJson(value),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        Path folder=Path.of(args[0]);Files.createDirectories(folder);folder=Files.createTempDirectory(folder,"config-");
        ConfigFiles store=new ConfigFiles(folder);
        JsonObject defaults=read(Path.of(args[1]).resolve("default_spawnselector.json"));
        check(defaults.getAsJsonObject("entries").keySet().equals(java.util.Set.of("spawnselector:village")),"New installs have only the vanilla village option");
        JsonObject oldOverrides=JsonParser.parseString("{\"entries\":{\"spawnselector:bobbar\":{\"order\":20},\"spawnselector:cloister\":{\"order\":30}}}").getAsJsonObject();
        JsonObject oldResolved=ExternalConfig.resolve(defaults,oldOverrides);
        ExternalConfig.validate(oldResolved);
        check(oldResolved.getAsJsonObject("entries").size()==3&&!oldResolved.getAsJsonObject("entries").has("spawnselector:chest_museum"),"Only explicitly configured retired entries are restored, not unrelated old defaults");
        check(oldResolved.getAsJsonObject("entries").getAsJsonObject("spawnselector:bobbar").get("order").getAsInt()==20,"Existing partial config keeps its overrides");
        check(ExternalConfig.resolve(defaults,new JsonObject()).equals(defaults),"Legacy templates never add entries to a clean config");
        JsonObject custom=oldOverrides.deepCopy();custom.getAsJsonObject("entries").getAsJsonObject("spawnselector:bobbar").addProperty("target","example:custom");
        check(ExternalConfig.resolve(defaults,custom).getAsJsonObject("entries").getAsJsonObject("spawnselector:bobbar").get("target").getAsString().equals("example:custom"),"Explicit custom target overrides the compatibility template");
        Path legacy=folder.resolve("spawnselector.json");
        String original="{\"schema_version\":1,\"entries\":{\"village\":{\"order\":77,\"description\":{\"text\":\"Custom\\nDescription\",\"bold\":true}}}}";
        Files.writeString(legacy,original,StandardCharsets.UTF_8);
        ConfigFiles.Snapshot before=store.read();
        check(!Files.exists(store.directory()),"Read-only legacy loading does not create directories");
        JsonObject baseline=ExternalConfig.merge(defaults,before.root());
        ConfigFiles.Snapshot split=store.save(baseline,baseline.deepCopy(),defaults);
        check(Files.readString(legacy,StandardCharsets.UTF_8).equals(original),"Legacy source remains byte-for-byte unchanged after migration");
        check(split.files().size()==1 && read(store.settings()).get("schema_version").getAsInt()==2,"Split settings and the single default entry created");
        check(split.root().getAsJsonObject("entries").getAsJsonObject("spawnselector:village").get("description").equals(baseline.getAsJsonObject("entries").getAsJsonObject("spawnselector:village").get("description")),"Legacy custom text survives migration exactly");
        Files.writeString(legacy,"broken legacy ignored after migration",StandardCharsets.UTF_8);
        store.read();
        Path village=store.entryPath("spawnselector:village",split),renamed=village.resolveSibling("my-village-file.json");Files.move(village,renamed);
        check(store.entryPath("spawnselector:village",store.read()).equals(renamed),"File rename keeps stable entry id and is honored by saving");
        baseline=ExternalConfig.merge(defaults,store.read().root());JsonObject gui=baseline.deepCopy();
        gui.getAsJsonObject("entries").getAsJsonObject("spawnselector:village").addProperty("order",84);
        JsonObject external=read(renamed);
        JsonElement text=JsonParser.parseString("{\"text\":\"\",\"extra\":[{\"text\":\"External\\n\",\"color\":\"#123456\",\"bold\":true},{\"translate\":\"my.pack.key\",\"italic\":true,\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"help\"}}}]}");
        external.add("description",text);external.add("name",JsonParser.parseString("{\"text\":\"My village\"}"));external.addProperty("pack_note","keep me");write(renamed,external);
        store.save(gui,baseline,defaults);
        JsonObject saved=read(renamed);
        check(saved.get("order").getAsInt()==84,"GUI parameter applied to latest disk entry");
        check(saved.get("description").equals(text)&&saved.get("pack_note").getAsString().equals("keep me"),"Concurrent arbitrary text and unknown metadata remain intact");
        check(!saved.getAsJsonObject("name").has("translate"),"Literal replacement cannot retain default translation key");
        String raw=Files.readString(renamed,StandardCharsets.UTF_8);baseline=ExternalConfig.merge(defaults,store.read().root());store.save(baseline,baseline,defaults);
        check(Files.readString(renamed,StandardCharsets.UTF_8).equals(raw),"No-op save does not reserialize unchanged files");
        JsonObject nameEdit=baseline.deepCopy();
        nameEdit.getAsJsonObject("entries").getAsJsonObject("spawnselector:village").add("name",ConfigComponents.rename(baseline.getAsJsonObject("entries").getAsJsonObject("spawnselector:village").get("name"),"Renamed village"));
        external=read(renamed);
        external.add("name",JsonParser.parseString("{\"text\":\"My village\",\"color\":\"#5544FF\",\"bold\":true,\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"External style\"}}}"));
        write(renamed,external);store.save(nameEdit,baseline,defaults);
        JsonObject editedName=read(renamed).getAsJsonObject("name");
        check(editedName.get("text").getAsString().equals("Renamed village")&&editedName.get("color").getAsString().equals("#5544FF")&&editedName.get("bold").getAsBoolean()&&editedName.has("hoverEvent"),"GUI rename owns text while keeping concurrently edited disk styles");
        check(read(renamed).get("description").equals(text),"GUI rename leaves the complex description untouched");
        baseline=ExternalConfig.merge(defaults,store.read().root());
        JsonObject deleted=baseline.deepCopy();deleted.getAsJsonObject("entries").remove("spawnselector:village");store.save(deleted,baseline,defaults);
        check(!read(renamed).get("enabled").getAsBoolean(),"Deleting a built-in entry creates a disabled record");
        JsonObject disabled=read(renamed),malformed=disabled.deepCopy();malformed.add("target",new JsonObject());write(renamed,malformed);
        boolean invalidDisabled=false;try{store.save(baseline,baseline,defaults);}catch(Exception ex){invalidDisabled=ex.getMessage().contains("target");}
        check(invalidDisabled&&read(renamed).equals(malformed),"Malformed disabled entry cannot crash editor fields or be overwritten");write(renamed,disabled);
        JsonObject collision=read(renamed);collision.addProperty("id","demo:fort");Path collisionFile=renamed.resolveSibling("external-fort.json");write(collisionFile,collision);
        JsonObject creating=baseline.deepCopy();creating.getAsJsonObject("entries").add("demo:fort",baseline.getAsJsonObject("entries").get("spawnselector:village").deepCopy());
        boolean conflict=false;try{store.save(creating,baseline,defaults);}catch(Exception ex){conflict=ex.getMessage().contains("conflicts");}
        check(conflict&&read(collisionFile).equals(collision),"Externally added entry cannot be overwritten by a colliding GUI draft");Files.delete(collisionFile);
        Path duplicate=renamed.resolveSibling("duplicate.json");Files.copy(renamed,duplicate);
        String settings=Files.readString(store.settings(),StandardCharsets.UTF_8);boolean failed=false;
        try{store.save(baseline,baseline,defaults);}catch(Exception ex){failed=ex.getMessage().contains("Duplicate entry");}
        check(failed&&Files.readString(store.settings(),StandardCharsets.UTF_8).equals(settings),"Duplicate ids fail before replacing any config");Files.delete(duplicate);
        failed=false;try{store.entryPath("spawnselector:../../escape",store.read());}catch(Exception ex){failed=true;}
        check(failed,"Generated config paths cannot traverse directories");
        JsonObject future=read(store.settings());future.addProperty("schema_version",999);write(store.settings(),future);failed=false;
        try{store.save(baseline,baseline,defaults);}catch(Exception ex){failed=true;}
        check(failed&&read(store.settings()).get("schema_version").getAsInt()==999,"Future schema cannot be overwritten");
        check(StructureCatalog.idFromPath("data/demo/tags/worldgen/structure/cities/large.json",true).toString().equals("demo:cities/large"),"Nested structure tag paths discovered");
        check(StructureCatalog.idFromPath("data/demo/tags/blocks/cities.json",true)==null,"Other registry tags excluded");
        check(StructureCatalog.idFromPath("data/demo/worldgen/structure/city.json",false).toString().equals("demo:city"),"Structure id discovery retained");
        Path opening=Files.createTempDirectory(folder,"open-folder-");ConfigFiles unopened=new ConfigFiles(opening);
        Path directory=unopened.ensureDirectory();
        check(directory.equals(opening.resolve("spawnselector").toAbsolutePath())&&Files.isDirectory(directory),"Folder button targets the mod directory even before config exists");
        check(!Files.exists(unopened.settings())&&unopened.read().root().size()==0,"Opening the folder does not activate or save split config");
        var identifiers=java.util.List.of("demo:emerald_ring","minecraft:emerald","minecraft:emerald_block","minecraft:stone");
        check(IdentifierSuggestions.matching(identifiers,"emer",128).size()==3,"Path prefix completes across namespaces");
        check(IdentifierSuggestions.matching(identifiers,"minecraft:emer",128).equals(identifiers.subList(1,3)),"Explicit namespace restricts completion");
        check(IdentifierSuggestions.matching(identifiers,"",2).size()==2,"Suggestion results have a fixed bound");
        check(IdentifierSuggestions.matching(identifiers,"unregistered:manual",128).isEmpty(),"Unknown manual IDs are not replaced by unrelated suggestions");
        System.out.println("PASS: real split config migration, stable ids, stale GUI/external text preservation, duplicate/future schema safety, tag paths");
    }
}

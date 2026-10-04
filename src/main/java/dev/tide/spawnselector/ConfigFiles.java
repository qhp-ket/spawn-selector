package dev.tide.spawnselector;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Shared split-file storage. Reading never migrates or writes the user's config. */
final class ConfigFiles {
    record Snapshot(JsonObject root, Map<String, Path> files) {}
    private final Path directory, legacy;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    ConfigFiles(Path configDirectory) {
        directory=configDirectory.resolve("spawnselector").toAbsolutePath().normalize();
        legacy=configDirectory.resolve("spawnselector.json");
    }
    Path directory() { return directory; }
    Path ensureDirectory() throws IOException { return Files.createDirectories(checked(directory)); }
    Path settings() { return directory.resolve("settings.json"); }
    Snapshot read() throws IOException {
        if (!Files.exists(settings())) {
            JsonObject root=Files.exists(legacy)?readObject(legacy,1):new JsonObject();
            if(root.has("entries")) {
                JsonObject normalized=new JsonObject();
                for(var e:ExternalConfig.section(root,"entries").entrySet()) {
                    String id=normalizeId(e.getKey());
                    if(normalized.has(id))throw new IOException(legacy+": duplicate normalized entry id "+id);
                    normalized.add(id,e.getValue());
                }
                root.add("entries",normalized);
            }
            return new Snapshot(root,Map.of());
        }
        JsonObject root=readObject(settings(),2), entries=new JsonObject();
        if(root.has("entries")) throw new IOException(settings()+": entries belong in entries/*.json");
        Map<String,Path> files=new HashMap<>();
        Path folder=directory.resolve("entries");
        if(Files.isDirectory(folder)) try(var stream=Files.walk(folder,16)) {
            List<Path> paths=stream.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".json")).sorted().limit(2049).toList();
            if(paths.size()>2048) throw new IOException(folder+": too many entry files (maximum 2048)");
            for(Path path:paths) {
                JsonObject entry=readObject(path,2);
                if(!entry.has("id")) throw new IOException(path+": missing stable entry id");
                String id=normalizeId(entry.get("id").getAsString());
                if(files.putIfAbsent(id,path)!=null) throw new IOException("Duplicate entry "+id+" in "+files.get(id)+" and "+path);
                entry.remove("id");entry.remove("schema_version");entries.add(id,entry);
            }
        }
        root.add("entries",entries); return new Snapshot(root,Map.copyOf(files));
    }
    private static JsonObject readObject(Path path,int maxSchema) throws IOException {
        try {
            if(Files.size(path)>1024*1024)throw new IOException(path+": file exceeds 1 MiB");
            JsonElement json=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8));
            if(!json.isJsonObject())throw new IllegalArgumentException("root must be an object");
            JsonObject root=json.getAsJsonObject();
            int version=root.has("schema_version")?root.get("schema_version").getAsInt():0;
            if(version<0||version>maxSchema)throw new IllegalArgumentException("unsupported schema_version "+version);
            return root;
        } catch(RuntimeException ex) { throw new IOException(path+": "+ex.getMessage(),ex); }
    }
    static String normalizeId(String id) {
        ResourceLocation key=new ResourceLocation(id.contains(":")?id:"spawnselector:"+id);
        return key.toString();
    }
    Path entryPath(String id,Snapshot snapshot) throws IOException {
        id=normalizeId(id);
        Path existing=snapshot.files().get(id);
        if(existing!=null)return checked(existing);
        ResourceLocation key=new ResourceLocation(id);
        if(Arrays.stream(key.getPath().split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))
            throw new IOException("Entry id cannot contain empty or relative path segments: "+id);
        String relative=(key.getNamespace().equals("spawnselector")?"":key.getNamespace()+"/")+key.getPath()+".json";
        return checked(directory.resolve("entries").resolve(relative));
    }
    private Path checked(Path path) throws IOException {
        Path full=path.toAbsolutePath().normalize();
        if(!full.startsWith(directory))throw new IOException("Config path escapes "+directory);
        for(Path part=full;part!=null&&part.startsWith(directory);part=part.getParent())
            if(Files.isSymbolicLink(part))throw new IOException("Refusing to write config through symbolic link "+part);
        return full;
    }
    /** Apply only GUI differences to the latest files; external text edits stay authoritative. */
    Snapshot save(JsonObject resolved,JsonObject baseline,JsonObject defaults) throws IOException {
        Snapshot disk=read();
        for(String id:ExternalConfig.section(resolved,"entries").keySet())
            if(!ExternalConfig.section(baseline,"entries").has(id)&&ExternalConfig.section(disk.root(),"entries").has(id))
                throw new IOException("New GUI entry conflicts with an externally added entry: "+id);
        JsonObject latest=ExternalConfig.resolve(defaults,disk.root());
        JsonElement delta=ExternalConfig.diff(baseline,resolved);
        JsonObject result=delta==null?latest:ExternalConfig.merge(latest,delta.getAsJsonObject());
        JsonObject entries=ExternalConfig.section(result,"entries"), old=ExternalConfig.section(baseline,"entries");
        // A GUI rename owns the string, not styles concurrently edited in the JSON file.
        JsonObject edited=ExternalConfig.section(resolved,"entries"),latestEntries=ExternalConfig.section(latest,"entries");
        for(var entry:edited.entrySet()) {
            String id=entry.getKey();
            if(!old.has(id)||!latestEntries.has(id)||!entries.has(id))continue;
            JsonElement before=old.getAsJsonObject(id).get("name"),after=entry.getValue().getAsJsonObject().get("name");
            if(ConfigComponents.isRename(before,after))entries.getAsJsonObject(id).add("name",
                ConfigComponents.rename(latestEntries.getAsJsonObject(id).get("name"),ConfigComponents.text(after,"")));
        }
        // Removing a bundled/datapack entry means disabling it, not resurrecting its default.
        for(var entry:old.entrySet()) if(!entries.has(entry.getKey())) {
            JsonObject disabled=entry.getValue().getAsJsonObject().deepCopy();disabled.addProperty("enabled",false);entries.add(entry.getKey(),disabled);
        }
        ExternalConfig.validate(result);
        Map<Path,JsonObject> writes=new LinkedHashMap<>();
        for(var entry:entries.entrySet()) {
            String id=normalizeId(entry.getKey());JsonObject value=entry.getValue().getAsJsonObject();
            if(!value.has("enabled")||value.get("enabled").getAsBoolean())SpawnEntry.parse(new ResourceLocation(id),value);
            JsonObject document=value.deepCopy();document.addProperty("id",id);document.addProperty("schema_version",2);
            Path file=entryPath(id,disk);
            if(!Files.isRegularFile(file)||!readObject(file,2).equals(document))writes.put(file,document);
        }
        JsonObject settings=result.deepCopy();settings.remove("entries");settings.addProperty("schema_version",2);
        if(!Files.isRegularFile(settings())||!readObject(settings(),2).equals(settings))writes.put(checked(settings()),settings);
        // Validate and stage every file before replacing any. Settings activates first migration last.
        Map<Path,Path> staged=new LinkedHashMap<>();Map<Path,byte[]> backups=new LinkedHashMap<>();
        List<Path> applied=new ArrayList<>();
        try {
            for(var write:writes.entrySet()) {
                Path file=write.getKey();Files.createDirectories(file.getParent());
                backups.put(file,Files.exists(file)?Files.readAllBytes(file):null);
                Path temp=Files.createTempFile(file.getParent(),".spawnselector-",".tmp");staged.put(file,temp);
                Files.writeString(temp,JSON.toJson(write.getValue())+"\n",StandardCharsets.UTF_8);
            }
            for(var write:staged.entrySet()) { applied.add(write.getKey());move(write.getValue(),write.getKey()); }
        } catch(IOException failure) {
            Collections.reverse(applied);
            for(Path file:applied) try {
                byte[] backup=backups.get(file);
                if(backup==null)Files.deleteIfExists(file);
                else {Path restore=Files.createTempFile(file.getParent(),".spawnselector-restore-",".tmp");Files.write(restore,backup);move(restore,file);}
            } catch(IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        } finally {for(Path temp:staged.values())Files.deleteIfExists(temp);}
        return read();
    }
    private static void move(Path from,Path to) throws IOException {
        try {Files.move(from,to,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
}

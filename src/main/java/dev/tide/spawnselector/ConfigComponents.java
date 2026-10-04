package dev.tide.spawnselector;

import com.google.gson.*;
import net.minecraft.network.chat.Component;

/** Component previews and plain-name editing; untouched components retain their original JSON. */
final class ConfigComponents {
    static JsonElement editedName(JsonElement original,String shownBefore,String edited) {
        return shownBefore.equals(edited)?original==null?null:original.deepCopy():rename(original,edited);
    }
    static JsonElement rename(JsonElement original,String name) {
        return Component.Serializer.toJsonTree(Component.literal(name).withStyle(component(original).getStyle()));
    }
    static boolean isRename(JsonElement before,JsonElement after) {
        return after!=null&&!java.util.Objects.equals(before,after)&&rename(before,text(after,"")).equals(after);
    }
    static String text(JsonElement value,String fallback) {
        if(value==null)return fallback;
        try{Component parsed=value.isJsonPrimitive()?Component.literal(value.getAsString()):Component.Serializer.fromJson(value);return parsed==null?fallback:parsed.getString();}
        catch(RuntimeException ex){return fallback;}
    }
    static Component component(JsonElement value) {
        try{return value==null?Component.empty():value.isJsonPrimitive()?Component.literal(value.getAsString()):Component.Serializer.fromJson(value);}
        catch(RuntimeException ex){return Component.empty();}
    }
    static String mode(JsonElement value) {
        boolean[] kinds=new boolean[2];classify(value,kinds);
        return kinds[0]&&kinds[1]?"mixed":kinds[0]?"i18n":"custom";
    }
    private static void classify(JsonElement value,boolean[] kinds) {
        if(value==null||value.isJsonNull())return;
        if(value.isJsonArray()){value.getAsJsonArray().forEach(v->classify(v,kinds));return;}
        if(value.isJsonPrimitive()){if(!value.getAsString().isEmpty())kinds[1]=true;return;}
        JsonObject o=value.getAsJsonObject();
        if(o.has("translate"))kinds[0]=true;
        else if(o.has("text")&&!o.get("text").getAsString().isEmpty())kinds[1]=true;
        if(o.has("extra"))classify(o.get("extra"),kinds);
    }
    private ConfigComponents() {}
}

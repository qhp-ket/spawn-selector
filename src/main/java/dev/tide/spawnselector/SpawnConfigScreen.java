package dev.tide.spawnselector;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraftforge.fml.ModList;

/** Parameter and plain-name editor with styled previews and external JSON editing. */
public final class SpawnConfigScreen extends Screen {
    private enum Page { ENTRY, SEARCH, TEXT, GLOBAL }
    private record Row(String key,String label,String fallback,int limit,boolean number) {}
    private final Screen parent;
    private JsonObject root,baseline;
    private String error="",message="",selectedId;
    private String initialName="";
    private JsonElement initialNameJson;
    private Page page=Page.ENTRY;
    private int listScroll,formScroll,textScroll;
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private final Map<String,Boolean> toggles=new HashMap<>();
    private final Map<String,List<String>> completions=new HashMap<>();
    private IdentifierSuggestions suggestions;
    public SpawnConfigScreen(Screen parent) {
        super(Component.translatable("spawnselector.config.title"));this.parent=parent;reloadModel();
    }
    private Component label(String key){return Component.translatable("spawnselector.config."+key);}
    private void reloadModel() {
        ExternalConfig.EditorData data=ExternalConfig.loadForEditor();root=data.root();baseline=root.deepCopy();error=data.error();message=error;
        if(!root.has("entries"))root.add("entries",new JsonObject());
        if(!root.has("layout"))root.add("layout",new JsonObject());
        if(selectedId==null||!entries().has(selectedId))selectedId=ids().stream().findFirst().orElse(null);
    }
    private JsonObject entries(){return ExternalConfig.section(root,"entries");}
    private JsonObject entry(){return selectedId==null?new JsonObject():entries().getAsJsonObject(selectedId);}
    private JsonObject layout(){return ExternalConfig.section(root,"layout");}
    private List<String> ids(){return entries().keySet().stream().sorted(Comparator.comparingInt((String s)->number(entries().getAsJsonObject(s),"order",100)).thenComparing(s->s)).toList();}
    private static int number(JsonObject o,String key,int fallback){try{return o.has(key)?o.get(key).getAsInt():fallback;}catch(RuntimeException ex){return fallback;}}
    private static String value(JsonObject o,String key,String fallback){return o.has(key)?o.get(key).getAsString():fallback;}
    private int sidebar(){return width<480?94:Math.min(156,width/4);}
    private int right(){return sidebar()+24;}
    private int span(){return width-right()-12;}
    private int bodyTop(){return 94;}
    private int rows(){
        int available=height-78-bodyTop()+6,fit=Math.max(1,available/38);
        return form().size()>fit?Math.max(1,(available-16)/38):fit;
    }
    private int listRows(){return Math.max(1,(height-132)/24);}
    private boolean writable(){return error.isEmpty()&&!(minecraft.level!=null&&!minecraft.hasSingleplayerServer());}
    private Button button(Component text,int x,int y,int w,Runnable action){Button b=Button.builder(text,v->action.run()).bounds(x,y,Math.max(20,w),20).build();addRenderableWidget(b);return b;}
    private List<Row> form() {
        return switch(page) {
            case ENTRY -> List.of(new Row("name","name","",1024,false),new Row("target","target","minecraft:village_plains",256,false),new Row("required_mod","required_mod","",128,false),new Row("icon","icon","minecraft:compass",256,false),new Row("dimension","dimension","minecraft:overworld",128,false),new Row("order","order","100",8,true),new Row("enabled","enabled_label","true",0,false));
            case SEARCH -> List.of(new Row("search_radius","radius","32",8,true),new Row("min_distance","min_distance","8",8,true),new Row("max_distance","max_distance","48",8,true),new Row("preferred_side","side","north",16,false),new Row("advanced","advanced","",0,false));
            case GLOBAL -> List.of(new Row("entries_per_page","page_size","4",8,true),new Row("allow_permanent_skip","skip_global","true",0,false),new Row("panel_width","panel_width","420",8,true),new Row("card_height","card_height","24",8,true),new Row("show_icons","show_icons","true",0,false),new Row("show_previews","show_previews","false",0,false));
            case TEXT -> List.of();
        };
    }
    private boolean stage() {
        try {
            JsonObject target=(page==Page.GLOBAL?layout():entry()).deepCopy();
            for(var field:fields.entrySet()) {
                Row row=form().stream().filter(r->r.key().equals(field.getKey())).findFirst().orElseThrow();
                if(row.key().equals("name")) {
                    String name=field.getValue().getValue();
                    JsonElement edited=ConfigComponents.editedName(initialNameJson,initialName,name);
                    if(edited==null)target.remove("name");else target.add("name",edited);
                    continue;
                }
                String value=field.getValue().getValue().trim();
                if(row.number())target.addProperty(row.key(),Integer.parseInt(value));else target.addProperty(row.key(),value);
            }
            toggles.forEach(target::addProperty);
            if(page==Page.GLOBAL) {
                int n=number(target,"entries_per_page",4),w=number(target,"panel_width",420),h=number(target,"card_height",24);
                if(n<1||n>10||w<240||w>800||h<20||h>40)throw new IllegalArgumentException(label("layout_range").getString());
                root.add("layout",target);
            }else if(selectedId!=null){entries().add(selectedId,target);}
            return true;
        }catch(RuntimeException ex){message=Component.translatable("spawnselector.config.error",ex.getMessage()).getString();return false;}
    }
    @Override protected void init() {
        fields.clear();toggles.clear();
        suggestions=new IdentifierSuggestions(font,height);
        int x=right(),w=span();
        for(int i=0;i<Page.values().length;i++) {
            Page next=Page.values()[i];int tabX=x+i*w/4,tabW=(i+1)*w/4-i*w/4-3;
            Button b=button(label("tab_"+next.name().toLowerCase(Locale.ROOT)),tabX,30,tabW,()->{if(stage()){page=next;formScroll=0;textScroll=0;rebuildWidgets();}});b.active=page!=next;
        }
        Button open=button(label(page==Page.GLOBAL?"open_settings":"open_entry"),x,56,(w-6)/2,this::saveAndOpen);
        open.setTooltip(Tooltip.create(label("open_hint")));open.active=writable()&&(page==Page.GLOBAL||selectedId!=null);
        button(label("reload_files"),x+(w+6)/2,56,(w-6)/2,()->{reloadModel();rebuildWidgets();}).setTooltip(Tooltip.create(label("reload_hint")));
        List<String> ids=ids();listScroll=Math.max(0,Math.min(listScroll,Math.max(0,ids.size()-listRows())));
        for(int row=0;row<listRows()&&listScroll+row<ids.size();row++) {
            String id=ids.get(listScroll+row);String title=ConfigComponents.text(entries().getAsJsonObject(id).get("name"),id);
            Component text=Component.literal((id.equals(selectedId)?"> ":"")+font.plainSubstrByWidth(title,sidebar()-36));
            button(text,30,40+row*24,sidebar()-22,()->{if(stage()){selectedId=id;formScroll=0;textScroll=0;rebuildWidgets();}}).setTooltip(Tooltip.create(Component.literal(id+"\n"+title)));
        }
        int half=(sidebar()-4)/2;
        button(label("up"),8,height-80,half,()->move(-1));button(label("down"),12+half,height-80,half,()->move(1));
        button(label("add"),8,height-54,half,this::create);
        button(label("delete"),12+half,height-54,half,()->{if(selectedId!=null){entries().remove(selectedId);selectedId=ids().stream().findFirst().orElse(null);rebuildWidgets();}});
        button(label("open_folder"),8,height-28,sidebar(),this::openFolder).setTooltip(Tooltip.create(Component.literal(ExternalConfig.directory().toString())));
        Button save=button(label("save"),x,height-28,(w-6)/2,()->{if(save())rebuildWidgets();});save.active=writable();
        button(label("back"),x+(w+6)/2,height-28,(w-6)/2,this::onClose);
        if(page!=Page.TEXT&&(page==Page.GLOBAL||selectedId!=null)) {
            List<Row> form=form();formScroll=Math.max(0,Math.min(formScroll,Math.max(0,form.size()-rows())));
            JsonObject data=page==Page.GLOBAL?layout():entry();
            for(int i=0;i<rows()&&formScroll+i<form.size();i++) {
                Row row=form.get(formScroll+i);int y=bodyTop()+i*38+12;
                if(row.key().equals("advanced")) {button(label("advanced"),x,y,w,()->{if(stage())minecraft.setScreen(new SpawnEntryOptionsScreen(this,selectedId,entry()));});continue;}
                if(row.limit()==0) {
                    boolean enabled=!data.has(row.key())?Boolean.parseBoolean(row.fallback()):data.get(row.key()).getAsBoolean();toggles.put(row.key(),enabled);
                    button(toggleLabel(row.key()),x,y,w,()->{toggles.put(row.key(),!toggles.get(row.key()));rebuildToggle(row.key());});
                }else {
                    int fieldWidth=row.key().equals("target")?w-80:row.key().equals("icon")?w-26:w;
                    EditBox field=new EditBox(font,x,y,fieldWidth,20,label(row.label()));field.setMaxLength(row.limit());
                    field.setValue(row.key().equals("name")?ConfigComponents.text(data.get("name"),selectedId):value(data,row.key(),row.fallback()));fields.put(row.key(),field);addRenderableWidget(field);
                    if(row.key().equals("name")) {
                        initialName=field.getValue();initialNameJson=data.has("name")?data.get("name").deepCopy():null;
                        field.setTooltip(Tooltip.create(label("name_hint")));
                    }
                    if(Set.of("required_mod","dimension","icon").contains(row.key())) {
                        suggestions.add(field,()->completionValues(row.key()));
                        field.setTooltip(Tooltip.create(label(row.key().equals("dimension")?"dimension_completion":"completion")));
                    }
                    if(row.key().equals("target"))button(Component.translatable("spawnselector.picker.title"),x+w-76,y,76,this::picker);
                }
            }
        }
    }
    private List<String> completionValues(String key) {
        return completions.computeIfAbsent(key,k-> {
            if(k.equals("required_mod"))return ModList.get().getMods().stream().map(m->m.getModId()).distinct().sorted().toList();
            if(k.equals("icon"))return BuiltInRegistries.ITEM.keySet().stream().filter(id->!id.equals(new ResourceLocation("minecraft:air"))).map(ResourceLocation::toString).sorted().toList();
            Set<String> ids=new TreeSet<>(List.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end"));
            if(minecraft.getConnection()!=null) {
                ids.clear();minecraft.getConnection().levels().forEach(d->ids.add(d.location().toString()));
            }
            return List.copyOf(ids);
        });
    }
    private void openFolder() {
        try{Util.getPlatform().openFile(ExternalConfig.ensureDirectory().toFile());}
        catch(IOException|RuntimeException ex){message=Component.translatable("spawnselector.config.open_failed",ex.getMessage()).getString();}
    }
    private void icon(GuiGraphics g,String value,int x,int y,int mx,int my) {
        ResourceLocation id=ResourceLocation.tryParse(value);
        boolean valid=id!=null&&BuiltInRegistries.ITEM.containsKey(id)&&BuiltInRegistries.ITEM.get(id)!=Items.AIR;
        g.fill(x-2,y-2,x+18,y+18,valid?0x60444444:0x80AA3333);
        ItemStack stack=new ItemStack(valid?BuiltInRegistries.ITEM.get(id):Items.COMPASS);g.renderItem(stack,x,y);
        if(mx>=x-2&&mx<x+18&&my>=y-2&&my<y+18)
            g.renderTooltip(font,valid?stack.getHoverName().copy().append("\n"+value):Component.translatable("spawnselector.config.invalid_icon",value),mx,my);
    }
    private Component toggleLabel(String key){return Component.translatable(toggles.get(key)?"spawnselector.config.on":"spawnselector.config.off");}
    private void rebuildToggle(String key){if(stage())rebuildWidgets();}
    private boolean save() {
        if(!writable()||!stage())return false;
        try{ExternalConfig.save(root,baseline);reloadModel();message=label("saved").getString();return true;}
        catch(IOException|RuntimeException ex){message=Component.translatable("spawnselector.config.save_failed",ex.getMessage()).getString();return false;}
    }
    private void saveAndOpen() {
        if(!save())return;
        try{Util.getPlatform().openFile((page==Page.GLOBAL?ExternalConfig.settings():ExternalConfig.entryFile(selectedId)).toFile());}
        catch(IOException|RuntimeException ex){message=ex.getMessage();}rebuildWidgets();
    }
    private void picker(){if(stage())minecraft.setScreen(new StructurePickerScreen(this,this::applyTarget));}
    private void applyTarget(StructurePickerScreen.Choice choice) {
        JsonObject o=entry();String previousTarget=value(o,"target","");o.addProperty("target",choice.value());
        if(choice.entry()!=null) {
            StructureCatalog.Entry selected=choice.entry();
            if(selected.tag())o.addProperty("required_mod","");
            else {
                ResourceLocation previous=ResourceLocation.tryParse(previousTarget.startsWith("#")?previousTarget.substring(1):previousTarget);
                String required=value(o,"required_mod","");
                if(required.isBlank()||(previous!=null&&required.equals(previous.getNamespace())))o.addProperty("required_mod",selected.requiredMod());
            }
            if(ConfigComponents.text(o.get("name"),"").equals(label("new_entry").getString()))o.addProperty("name",selected.label());
            if(selectedId.startsWith("spawnselector:new_entry")&&!ExternalConfig.section(baseline,"entries").has(selectedId)) {
                String base="spawnselector:"+(selected.tag()?"tag_":"")+selected.id().getNamespace()+"_"+selected.id().getPath().replace('/','_');
                String candidate=base;int n=2;while(entries().has(candidate)&&!candidate.equals(selectedId))candidate=base+"_"+n++;
                entries().remove(selectedId);selectedId=candidate;entries().add(selectedId,o);
            }
        }
    }
    private void create() {
        if(!stage())return;int n=1;while(entries().has("spawnselector:new_entry"+n))n++;selectedId="spawnselector:new_entry"+n;
        JsonObject o=ExternalConfig.defaults().getAsJsonObject("entries").getAsJsonObject("spawnselector:village").deepCopy();
        o.addProperty("name",label("new_entry").getString());o.addProperty("description","");o.addProperty("target","minecraft:village_plains");o.addProperty("order",ids().size()*10+10);entries().add(selectedId,o);
        page=Page.ENTRY;formScroll=0;fields.clear();toggles.clear();minecraft.setScreen(new StructurePickerScreen(this,this::applyTarget));
    }
    private void move(int delta) {
        if(!stage()||selectedId==null)return;List<String> ids=new ArrayList<>(ids());int from=ids.indexOf(selectedId),to=from+delta;
        if(to<0||to>=ids.size())return;Collections.swap(ids,from,to);for(int i=0;i<ids.size();i++)entries().getAsJsonObject(ids.get(i)).addProperty("order",(i+1)*10);rebuildWidgets();
    }
    private List<net.minecraft.util.FormattedCharSequence> previewLines() {
        List<net.minecraft.util.FormattedCharSequence> lines=new ArrayList<>();
        for(String key:List.of("name","description")) {
            lines.addAll(font.split(Component.translatable("spawnselector.config."+key).append(" · ").append(Component.translatable("spawnselector.text."+ConfigComponents.mode(entry().get(key)))),span()-12));
            Component component=ConfigComponents.component(entry().get(key));if(component!=null)lines.addAll(font.split(component,span()-12));
            lines.add(Component.empty().getVisualOrderText());
        }
        lines.addAll(font.split(label("text_readonly"),span()-12));return lines;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float tick) {
        renderBackground(g);g.drawCenteredString(font,title,width/2,10,0xFFFFFF);
        int x=right(),w=span();g.fill(4,28,sidebar()+12,height-86,0x70000000);g.fill(x-5,82,width-7,height-66,0x70000000);
        if(page==Page.TEXT&&selectedId!=null) {
            List<net.minecraft.util.FormattedCharSequence> lines=previewLines();int count=Math.max(1,(height-78-bodyTop())/(font.lineHeight+3));textScroll=Math.max(0,Math.min(textScroll,Math.max(0,lines.size()-count)));
            for(int i=0;i<count&&textScroll+i<lines.size();i++)g.drawString(font,lines.get(textScroll+i),x+6,bodyTop()+i*(font.lineHeight+3),0xFFFFFF);
        }else {
            List<Row> form=form();
            for(int i=0;i<rows()&&formScroll+i<form.size();i++)g.drawString(font,font.plainSubstrByWidth(label(form.get(formScroll+i).label()).getString(),w),x,bodyTop()+i*38,0xBBBBBB);
            if(form.size()>rows())g.drawString(font,Component.translatable("spawnselector.config.scroll_rows",formScroll+1,Math.min(form.size(),formScroll+rows()),form.size()),x,height-90,0x888888);
        }
        String status=!writable()&&error.isEmpty()?label("remote").getString():message;
        int y=height-53;for(var line:font.split(Component.literal(status),w).stream().limit(2).toList()){g.drawString(font,line,x,y,0xE5CF98);y+=font.lineHeight+1;}
        super.render(g,mx,my,tick);
        List<String> ids=ids();
        for(int row=0;row<listRows()&&listScroll+row<ids.size();row++)icon(g,value(entries().getAsJsonObject(ids.get(listScroll+row)),"icon","minecraft:compass"),10,42+row*24,mx,my);
        EditBox iconField=fields.get("icon");
        if(iconField!=null)icon(g,iconField.getValue(),x+w-19,iconField.getY()+2,mx,my);
        if(!status.isEmpty()&&mx>=x&&mx<x+w&&my>=height-55&&my<height-30)g.renderTooltip(font,Component.literal(status),mx,my);
        suggestions.render(g,mx,my);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){return suggestions.keyPressed(key)||super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseClicked(double mx,double my,int button){return suggestions.mouseClicked(mx,my,button)||super.mouseClicked(mx,my,button);}
    @Override public boolean mouseScrolled(double mx,double my,double delta) {
        if(suggestions.mouseScrolled(mx,my,delta))return true;
        int direction=(int)Math.signum(delta);
        if(mx<right()) {if(stage()){listScroll=Math.max(0,Math.min(Math.max(0,ids().size()-listRows()),listScroll-direction));rebuildWidgets();}}
        else if(page==Page.TEXT)textScroll=Math.max(0,textScroll-direction);
        else if(stage()){formScroll=Math.max(0,Math.min(Math.max(0,form().size()-rows()),formScroll-direction));rebuildWidgets();}
        return true;
    }
    @Override public void resize(net.minecraft.client.Minecraft mc,int w,int h){stage();super.resize(mc,w,h);}
    @Override public void tick(){fields.values().forEach(EditBox::tick);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}

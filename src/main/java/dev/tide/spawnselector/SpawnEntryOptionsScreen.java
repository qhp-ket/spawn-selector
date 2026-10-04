package dev.tide.spawnselector;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Entry-specific pool and terrain settings, kept out of the crowded basic editor. */
final class SpawnEntryOptionsScreen extends Screen {
    private final Screen parent;
    private final String id;
    private final JsonObject entry;
    private EditBox count, capacity, exclusions;
    private Button sky;
    private boolean requireOpenSky;
    private String message="";
    SpawnEntryOptionsScreen(Screen parent,String id,JsonObject entry) {
        super(Component.translatable("spawnselector.config.advanced")); this.parent=parent; this.id=id; this.entry=entry;
    }
    private EditBox field(int y,String value,int max) {
        EditBox box=new EditBox(font,(width-panel())/2,y,panel(),20,Component.empty());
        box.setMaxLength(max); box.setValue(value); addRenderableWidget(box); return box;
    }
    private int panel(){return Math.min(480,width-32);}
    @Override protected void init() {
        String countDraft=count==null?(entry.has("candidate_count")?entry.get("candidate_count").getAsString():"3"):count.getValue();
        String capacityDraft=capacity==null?(entry.has("capacity_per_instance")?entry.get("capacity_per_instance").getAsString():"-1"):capacity.getValue();
        String filters=exclusions==null?(entry.has("exclusions")?String.join(",",entry.getAsJsonArray("exclusions").asList().stream().map(JsonElement::getAsString).toList()):""):exclusions.getValue();
        count=field(44,countDraft,8);
        capacity=field(80,capacityDraft,8);
        if(sky==null)requireOpenSky=!entry.has("require_open_sky")||entry.get("require_open_sky").getAsBoolean();
        sky=Button.builder(skyLabel(),b->{requireOpenSky=!requireOpenSky;b.setMessage(skyLabel());})
            .bounds((width-panel())/2,116,panel(),20).build();
        addRenderableWidget(sky);
        exclusions=field(152,filters,8192);
        exclusions.setTooltip(Tooltip.create(Component.translatable("spawnselector.config.advanced_hint")));
        addRenderableWidget(Button.builder(Component.translatable("spawnselector.config.apply"),b->apply()).bounds(width/2-100,height-32,96,20).build());
        addRenderableWidget(Button.builder(Component.translatable("spawnselector.config.back"),b->minecraft.setScreen(parent)).bounds(width/2+4,height-32,96,20).build());
    }
    private void apply() {
        try {
            JsonObject copy=entry.deepCopy();
            copy.addProperty("candidate_count",Integer.parseInt(count.getValue()));
            copy.addProperty("capacity_per_instance",Integer.parseInt(capacity.getValue()));
            copy.addProperty("require_open_sky",requireOpenSky);
            JsonArray filters=new JsonArray(); Arrays.stream(exclusions.getValue().split(",")).map(String::trim)
                .filter(s->!s.isEmpty()).forEach(filters::add); copy.add("exclusions",filters);
            SpawnEntry.parse(new ResourceLocation(id),copy);
            for(String key:List.of("candidate_count","capacity_per_instance","require_open_sky","exclusions")) entry.add(key,copy.get(key));
            minecraft.setScreen(parent);
        } catch(RuntimeException ex) { message=Component.translatable("spawnselector.config.error",ex.getMessage()).getString(); }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float tick) {
        renderBackground(graphics); graphics.drawCenteredString(font,getTitle(),width/2,12,0xFFFFFF);
        String[] labels={Component.translatable("spawnselector.config.candidate_count").getString(),Component.translatable("spawnselector.config.capacity").getString(),Component.translatable("spawnselector.config.sky").getString(),Component.translatable("spawnselector.config.exclusions").getString()};
        int labelLeft=(width-panel())/2,labelWidth=panel();
        for(int i=0;i<labels.length;i++) graphics.drawString(font,font.plainSubstrByWidth(labels[i],labelWidth),labelLeft,32+i*36,0xAAAAAA);
        var hintLines=font.split(Component.translatable("spawnselector.config.advanced_hint"),panel()).stream().limit(2).toList();
        int hintY=height-38-(hintLines.size()+(message.isEmpty()?0:1))*(font.lineHeight+2);
        for(var line:hintLines) { graphics.drawString(font,line,(width-font.width(line))/2,hintY,0xAAAAAA);hintY+=font.lineHeight+2; }
        if(!message.isEmpty()) graphics.drawCenteredString(font,font.plainSubstrByWidth(message,panel()),width/2,hintY,0xFFAA55);
        super.render(graphics,mouseX,mouseY,tick);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void tick(){count.tick();capacity.tick();exclusions.tick();}
    @Override public boolean isPauseScreen() { return false; }
    private Component skyLabel() {
        return Component.translatable("spawnselector.config."+(requireOpenSky?"sky_required":"sky_optional"));
    }
}

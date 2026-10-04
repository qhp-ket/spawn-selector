package dev.tide.spawnselector;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Searchable structure picker used only from the Mods configuration screen. */
final class StructurePickerScreen extends Screen {
    record Choice(String value, StructureCatalog.Entry entry) {}
    private final Screen parent;
    private final Consumer<Choice> applied;
    private final List<StructureCatalog.Entry> all;
    private final List<String> namespaces;
    private EditBox search;
    private Button filter;
    private Button confirm;
    private int filterIndex,typeIndex;
    private int scroll;
    private String message = "";
    private StructureCatalog.Entry selectedEntry;

    StructurePickerScreen(Screen parent, Consumer<Choice> applied) {
        super(Component.translatable("spawnselector.picker.title"));
        this.parent = parent;
        this.applied = applied;
        this.all = StructureCatalog.load();
        this.namespaces = all.stream().map(StructureCatalog.Entry::namespace).distinct().sorted().toList();
    }

    private List<StructureCatalog.Entry> visible() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        String namespace = filterIndex == 0 ? "" : namespaces.get(filterIndex - 1);
        return all.stream().filter(e -> typeIndex==0 || (typeIndex==2)==e.tag()).filter(e -> namespace.isBlank() || e.namespace().equals(namespace))
            .filter(e -> query.isBlank() || e.target().toLowerCase(Locale.ROOT).contains(query)
                || e.sourceName().toLowerCase(Locale.ROOT).contains(query)
                || e.label().toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    private static boolean validTarget(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        return ResourceLocation.tryParse(raw) != null;
    }
    private int panelWidth() { return Math.min(520, width - 24); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int listTop() { return 82; }
    private int listRows() { return Math.max(1, (height - listTop() - 66) / 30); }

    @Override protected void init() {
        int panel = panelWidth(), left = panelLeft();
        String previous=search==null?"":search.getValue();
        search = new EditBox(font, left, 28, panel, 20, Component.translatable("spawnselector.picker.search"));
        search.setMaxLength(256);search.setValue(previous); search.setResponder(value -> { scroll = 0; if (selectedEntry != null && !selectedEntry.target().equals(value.trim())) selectedEntry = null; }); addRenderableWidget(search);
        filter = Button.builder(filterLabel(), button -> { filterIndex = (filterIndex + 1) % (namespaces.size() + 1); button.setMessage(filterLabel()); scroll = 0; }).bounds(left, 54, (panel - 6)/2, 20).build(); addRenderableWidget(filter);
        addRenderableWidget(Button.builder(typeLabel(), b -> {typeIndex=(typeIndex+1)%3;b.setMessage(typeLabel());scroll=0;}).bounds(left+(panel+6)/2,54,(panel-6)/2,20).build());
        addRenderableWidget(Button.builder(Component.translatable("spawnselector.config.back"),button->minecraft.setScreen(parent)).bounds(left,height-30,96,20).build());
        confirm = Button.builder(Component.translatable("spawnselector.picker.apply"), button -> apply()).bounds(left + panel - 96, height - 30, 96, 20).build(); addRenderableWidget(confirm);
        search.setFocused(true);
    }
    private Component typeLabel() { return switch(typeIndex) {
        case 1 -> Component.translatable("spawnselector.picker.type_structures");
        case 2 -> Component.translatable("spawnselector.picker.type_tags");
        default -> Component.translatable("spawnselector.picker.type_all");
    }; }
    private Component filterLabel() {
        if (filterIndex == 0) return Component.translatable("spawnselector.picker.all");
        String namespace = namespaces.get(filterIndex - 1);
        String name = all.stream().filter(e -> e.namespace().equals(namespace)).findFirst().map(StructureCatalog.Entry::sourceName).orElse(namespace);
        return Component.translatable("spawnselector.picker.source", name, namespace);
    }
    private void apply() {
        String value = search.getValue().trim();
        if (!validTarget(value)) { message = Component.translatable("spawnselector.picker.invalid").getString(); return; }
        applied.accept(new Choice(value, selectedEntry)); minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.drawCenteredString(font, getTitle(), width / 2, 8, 0xFFFFFF);
        List<StructureCatalog.Entry> visible = visible(); int left = panelLeft(), panel = panelWidth(), top = listTop(), rows = listRows(); int maxScroll = Math.max(0, visible.size() - rows); scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (visible.isEmpty()) graphics.drawCenteredString(font, Component.translatable("spawnselector.picker.empty").getString(), width / 2, top + 12, 0xFF7777);
        else for (int i = 0; i < rows && scroll + i < visible.size(); i++) {
            StructureCatalog.Entry entry=visible.get(scroll+i);int y=top+i*30;
            boolean hovered=mouseX>=left&&mouseX<left+panel&&mouseY>=y&&mouseY<y+28;
            boolean chosen=search!=null&&search.getValue().trim().equals(entry.target());
            graphics.fill(left,y,left+panel,y+28,chosen?0x90405030:hovered?0x80505050:0x60000000);
            graphics.drawString(font,font.plainSubstrByWidth(entry.target(),panel-16),left+8,y+3,entry.tag()?0x77DDEE:0xFFFFFF);
            Component info=Component.literal(entry.sourceName()+" · ").append(Component.translatable(entry.tag()?"spawnselector.picker.tag":"spawnselector.picker.structure"));
            if(entry.members()>=0)info=info.copy().append(" · ").append(Component.translatable("spawnselector.picker.members",entry.members()));
            graphics.drawString(font,font.plainSubstrByWidth(info.getString(),panel-16),left+8,y+15,0xAAAAAA);
        }
        if (!message.isBlank()) graphics.drawString(font, message, left, height - 54, 0xFFFF5555);
        if(message.isBlank())graphics.drawString(font,font.plainSubstrByWidth(Component.translatable("spawnselector.picker.hint").getString(),panel),left,height-44,0xAAAAAA);
        if (confirm != null) confirm.active = validTarget(search == null ? "" : search.getValue());
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = panelLeft(), top = listTop(), rows = listRows(); List<StructureCatalog.Entry> visible = visible(); int row = (int)((mouseY - top) / 30);
            if (mouseX >= left && mouseX < left + panelWidth() && mouseY >= top && mouseY < top+rows*30 && row >= 0 && row < rows && scroll + row < visible.size()) { StructureCatalog.Entry entry=visible.get(scroll + row); search.setValue(entry.target()); selectedEntry=entry; message = ""; return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) { int maxScroll = Math.max(0, visible().size() - listRows()); scroll = Math.max(0, Math.min(maxScroll, scroll - (int)Math.signum(delta))); return true; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}

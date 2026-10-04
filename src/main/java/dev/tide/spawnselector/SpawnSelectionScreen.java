package dev.tide.spawnselector;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.*;

/** Data-driven item icons, styled components and optional resource-pack previews. */
public final class SpawnSelectionScreen extends Screen {
    private Packets.View view;
    private List<Packets.Card> cards=List.of();
    private int selected,page,descriptionScroll,descriptionLines,descriptionRows,descriptionTop,descriptionBottom;
    private boolean busy;
    private boolean pauseWhenOpen;
    public SpawnSelectionScreen(Packets.View view){
        super(view.layout().title());
        updateData(view);
    }
    private Packets.Layout layout(){return view.layout();}
    private boolean stackedButtons(){return !busy&&layout().allowPermanentSkip()&&panelWidth()<360;}
    private int bottom(){return height-40;}
    private int buttonTop(){return bottom()-(view.state()==7&&!busy?24:0)-(stackedButtons()?24:0);}
    private Component statusMessage(){
        return busy&&view.state()!=2&&view.state()!=6
            ? Component.translatable("spawnselector.message.submitting") : view.message();
    }
    private int hintHeight(){
        if(font==null)return 30;
        int lines=Math.min(2,font.split(statusMessage(),panelWidth()).size())
            +Math.min(2,font.split(layout().footer(),panelWidth()).size());
        return lines*(font.lineHeight+2)+6;
    }
    private int perPage(){
        int reserve=top()+32+(font==null?40:4*(font.lineHeight+2));
        int byHeight=Math.max(1,(buttonTop()-hintHeight()-reserve)/Math.max(20,layout().cardHeight()));
        return Math.min(Mth.clamp(layout().entriesPerPage(),1,10),byHeight);
    }
    private int panelWidth(){return Math.min(Mth.clamp(layout().panelWidth(),240,800),width-24);}
    private int top(){return height<300?46:48;}
    private int visibleCards(){return Math.max(0,Math.min(perPage(),cards.size()-page*perPage()));}
    private void updateData(Packets.View next){
        if(view==null||view.state()!=next.state())descriptionScroll=0;
        view=next;busy=next.state()==2 || next.state()==6;
        // Only the actual choice page may pause an integrated server. Locating and
        // safe-spawn search require server ticks, including after this screen is rebuilt.
        pauseWhenOpen=next.state()==1 || next.state()==7 || next.state()==5;
        if(next.state()==5) { cards=List.of(); selected=0; page=0; }
        if(next.state()==1 || next.state()==7){cards=next.cards();selected=Math.min(selected,Math.max(0,cards.size()-1));page=selected/Math.max(1,perPage());}
    }
    public void update(Packets.View next){
        boolean messageOnly=view.state()==2&&next.state()==2&&next.cards().isEmpty();
        updateData(next);
        if(!messageOnly)rebuildWidgets();
    }
    @Override protected void init(){
        page=selected/Math.max(1,perPage());
        if(view.state()==5) {
            addRenderableWidget(Button.builder(Component.translatable("spawnselector.ui.recovery"),b->{busy=true;pauseWhenOpen=false;Packets.act("@vanilla");rebuildWidgets();})
                .bounds((width-panelWidth())/2,bottom(),panelWidth(),20).build());
            return;
        }
        int per=perPage(),panel=panelWidth(),left=(width-panel)/2,top=top(),cardHeight=Mth.clamp(layout().cardHeight(),20,40);
        if(busy){
            if (!cards.isEmpty()) addRenderableWidget(Button.builder(layout().backLabel(),b->Packets.act("@back"))
                .bounds(left,bottom(),panel,20).build());
            return;
        }
        int iconSpace=layout().showIcons()?28:0;
        for(int i=0;i<per&&page*per+i<cards.size();i++){
            int index=page*per+i;
            Component label=Component.literal(selected==index?"● ":"○ ").append(cards.get(index).name().copy());
            Button button=Button.builder(label,b->{selected=index;descriptionScroll=0;rebuildWidgets();})
                .bounds(left+iconSpace,top+i*cardHeight,panel-iconSpace,20).build();
            button.active=!busy;button.setTooltip(Tooltip.create(cards.get(index).name()));addRenderableWidget(button);
        }
        int pages=Math.max(1,(cards.size()+per-1)/per),navY=top+visibleCards()*cardHeight+4;
        if(pages>1){
            Button previous=Button.builder(Component.literal("‹"),b->{page--;selected=page*per;descriptionScroll=0;rebuildWidgets();}).bounds(left,navY,24,20).build();
            previous.active=!busy&&page>0;addRenderableWidget(previous);
            Button next=Button.builder(Component.literal("›"),b->{page++;selected=page*per;descriptionScroll=0;rebuildWidgets();}).bounds(left+panel-24,navY,24,20).build();
            next.active=!busy&&page+1<pages;addRenderableWidget(next);
        }
        int bottom=bottom(),confirmY=bottom-(stackedButtons()?24:0);
        if (view.state()==7) addRenderableWidget(Button.builder(layout().backLabel(),b->Packets.act("@back"))
            .bounds(left,confirmY-24,panel,20).build());
        int confirmWidth=layout().allowPermanentSkip()&&!stackedButtons()?panel/2-3:panel;
        Button confirm=Button.builder(layout().confirmLabel(),b->{
            if(cards.isEmpty()||busy)return;busy=true;pauseWhenOpen=false;Packets.act(cards.get(selected).id());rebuildWidgets();
        }).bounds(left,confirmY,confirmWidth,20).build();
        confirm.active=!busy&&!cards.isEmpty();addRenderableWidget(confirm);
        if(layout().allowPermanentSkip()){
            Button skip=Button.builder(layout().skipLabel(),b->{busy=true;pauseWhenOpen=false;Packets.act("@skip");rebuildWidgets();})
                .bounds(stackedButtons()?left:left+panel/2+3,bottom,stackedButtons()?panel:panel-panel/2-3,20).build();
            skip.active=!busy;addRenderableWidget(skip);
        }
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        // Origins uses the normal in-world gradient for its origin selection screen.
        // Use the same background for the first selection and the later loading state.
        drawOriginsBackground(g);
        if (view.state() == 6) {
            // During the final cross-dimension reveal, show only a clear loading
            // message. The old choice title/cards must not remain visible behind it.
            String loading = Component.translatable("spawnselector.message.loading").getString();
            g.pose().pushPose();
            g.pose().translate(width / 2.0f, height / 2.0f - 8.0f, 0.0f);
            g.pose().scale(1.5f, 1.5f, 1.0f);
            g.drawCenteredString(font, loading, 0, 0, 0xFFFFFF);
            g.pose().popPose();
            return;
        }
        int per=perPage(),panel=panelWidth(),left=(width-panel)/2,top=top(),cardHeight=Mth.clamp(layout().cardHeight(),20,40);
        int titleY=20;
        g.fill(left-8,8,left+panel+8,height-8,0x90000000);
        for(var line:font.split(layout().title(),panel).stream().limit(2).toList()){g.drawString(font,line,(width-font.width(line))/2,titleY,0xFFFFFF);titleY+=font.lineHeight+2;}
        if(!busy&&layout().showIcons())for(int i=0;i<per&&page*per+i<cards.size();i++){
            ResourceLocation icon=ResourceLocation.tryParse(cards.get(page*per+i).icon());
            Item item=icon!=null&&BuiltInRegistries.ITEM.containsKey(icon)?BuiltInRegistries.ITEM.get(icon):Items.COMPASS;
            g.renderItem(new ItemStack(item),left+4,top+i*cardHeight+2);
        }
        int pages=Math.max(1,(cards.size()+per-1)/per),navY=top+visibleCards()*cardHeight+4;
        if(pages>1)g.drawCenteredString(font,(page+1)+" / "+pages,width/2,navY+6,0xAAAAAA);
        int hintTop=buttonTop()-hintHeight();
        int y=navY+(pages>1?24:0)+8,textWidth=panel-10;
        if(busy&&!cards.isEmpty()){g.drawCenteredString(font,cards.get(selected).name(),width/2,top,0xFFFFFF);y=top+24;}
        descriptionTop=y;descriptionBottom=hintTop-8;
        g.fill(left,descriptionTop-5,left+panel,descriptionTop-4,0x50555555);
        // A locally busy selector can briefly be restored after Mojang's dimension
        // loading screen. It is not an empty choice page, so do not show its red
        // empty-list message while the pending server state is being applied.
        Component description=cards.isEmpty() ? (busy ? Component.empty() : layout().emptyMessage()) : cards.get(selected).description();
        if(!cards.isEmpty()&&layout().showPreviews()&&height>=360){
            String previewId=cards.get(selected).preview();
            ResourceLocation preview=previewId.isBlank()?null:ResourceLocation.tryParse(previewId);
            int previewWidth=Math.min(layout().previewWidth(),panel/2),previewHeight=Math.min(layout().previewHeight(),hintTop-y-4);
            if(previewHeight>0&&preview!=null&&minecraft.getResourceManager().getResource(preview).isPresent()){
                g.blit(preview,left+panel-previewWidth,y,0,0,previewWidth,previewHeight,layout().previewWidth(),layout().previewHeight());
                textWidth-=previewWidth+12;
            }
        }
        var lines=font.split(description,textWidth);descriptionLines=lines.size();
        int available=Math.max(0,descriptionBottom-descriptionTop);
        descriptionRows=available/(font.lineHeight+2);
        boolean overflow=descriptionLines>descriptionRows;
        boolean scrollHint=overflow&&available>=2*(font.lineHeight+2)+font.lineHeight+4;
        if(scrollHint)descriptionRows=Math.max(1,(available-font.lineHeight-4)/(font.lineHeight+2));
        descriptionScroll=Math.max(0,Math.min(descriptionScroll,Math.max(0,descriptionLines-descriptionRows)));
        if(descriptionRows>0){
            g.enableScissor(left,descriptionTop,left+textWidth,descriptionTop+descriptionRows*(font.lineHeight+2));
            for(int i=0;i<descriptionRows&&descriptionScroll+i<lines.size();i++)g.drawString(font,lines.get(descriptionScroll+i),left,descriptionTop+i*(font.lineHeight+2),0xFFFFFF);
            g.disableScissor();
            if(overflow){
                if(scrollHint)g.drawString(font,font.plainSubstrByWidth(Component.translatable("spawnselector.ui.scroll_description").getString(),panel),left,descriptionBottom-font.lineHeight,0x999999);
                int track=descriptionRows*(font.lineHeight+2),thumb=Math.max(8,track*descriptionRows/Math.max(1,descriptionLines));
                int offset=(track-thumb)*descriptionScroll/Math.max(1,descriptionLines-descriptionRows);
                g.fill(left+panel-3,descriptionTop,left+panel-1,descriptionTop+track,0x50555555);
                g.fill(left+panel-3,descriptionTop+offset,left+panel-1,descriptionTop+offset+thumb,0xFFBBBBBB);
            }
        }
        int hintY=hintTop;
        for(Component hint:List.of(statusMessage(),layout().footer())) {
            int color=hint==layout().footer()?0xFFFFFF:0xE5CF98;
            for(var line:font.split(hint,panel).stream().limit(2).toList()) {
                g.drawString(font,line,(width-font.width(line))/2,hintY,color);hintY+=font.lineHeight+2;
            }
        }
        super.render(g,mouseX,mouseY,partialTick);
        if(mouseX>=left&&mouseX<left+panel&&mouseY>=hintTop&&mouseY<buttonTop())g.renderTooltip(font,statusMessage(),mouseX,mouseY);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){
        int left=(width-panelWidth())/2;
        if(x>=left&&x<left+panelWidth()&&y>=descriptionTop&&y<descriptionBottom){descriptionScroll=Math.max(0,Math.min(Math.max(0,descriptionLines-descriptionRows),descriptionScroll-(int)Math.signum(delta)));return true;}
        return super.mouseScrolled(x,y,delta);
    }
    private void drawOriginsBackground(GuiGraphics g){
        // Origins' dirt-background mode uses the vanilla options_background texture,
        // rather than a solid black overlay or the dirt block texture.
        renderDirtBackground(g);
    }
    // Match Origins while the player is choosing. Busy states must keep the integrated server
    // ticking, while SelectionService provides damage and movement protection.
    @Override public boolean isPauseScreen(){return pauseWhenOpen;}
    @Override public boolean shouldCloseOnEsc(){return false;}
    @Override public void onClose(){/* Mandatory until confirmed or permanently skipped. */}
}

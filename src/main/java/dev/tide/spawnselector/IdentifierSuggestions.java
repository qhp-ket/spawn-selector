package dev.tide.spawnselector;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Local registry ID completion. No command dispatch, server requests, or world generation. */
final class IdentifierSuggestions {
    private record Source(EditBox field,Supplier<List<String>> values) {}
    private final List<Source> sources=new ArrayList<>();
    private final Font font;
    private final int screenHeight;
    private EditBox active;
    private String query="",dismissed;
    private List<String> matches=List.of();
    private int selected,first,x,y,width,rows;
    private static final int ROW=14,MAX_MATCHES=128;
    IdentifierSuggestions(Font font,int screenHeight){this.font=font;this.screenHeight=screenHeight;}
    void add(EditBox field,Supplier<List<String>> values){sources.add(new Source(field,values));}
    static List<String> matching(List<String> sorted,String input,int limit) {
        String prefix=input.toLowerCase(Locale.ROOT);
        List<String> result=new ArrayList<>();
        for(String id:sorted) {
            int colon=id.indexOf(':');
            if(!id.equals(input)&&(id.startsWith(prefix)||(colon>=0&&!prefix.contains(":")&&id.substring(colon+1).startsWith(prefix)))) {
                result.add(id);if(result.size()>=limit)break;
            }
        }
        return List.copyOf(result);
    }
    private void update() {
        Source focused=sources.stream().filter(s->s.field().isFocused()).findFirst().orElse(null);
        EditBox field=focused==null?null:focused.field();
        String value=field==null?"":field.getValue();
        if(field!=active||!value.equals(query)) {
            if(active!=null)active.setSuggestion(null);
            active=field;query=value;dismissed=null;selected=first=0;
            List<String> values=focused==null?List.of():focused.values().get();
            matches=matching(values,query,MAX_MATCHES);
            if(Collections.binarySearch(values,query)>=0)dismissed=query;
        }
        if(active==null||active.getCursorPosition()!=query.length()||Objects.equals(dismissed,query)){hideGhost();return;}
        rows=Math.min(6,matches.size());x=active.getX();width=active.getWidth();
        int below=active.getY()+active.getHeight()+2;
        // Prefer below; move above near the bottom of a small/scaled window.
        if(below+rows*ROW+2<=screenHeight-8)y=below;
        else {
            rows=Math.min(rows,Math.max(0,(active.getY()-10)/ROW));
            y=active.getY()-rows*ROW-2;
        }
        selected=Math.min(selected,Math.max(0,matches.size()-1));
        first=Math.max(0,Math.min(first,Math.max(0,matches.size()-rows)));
        if(selected<first)first=selected;
        if(selected>=first+rows)first=selected-rows+1;
        if(visible()) {
            String candidate=matches.get(selected);
            active.setSuggestion(candidate.startsWith(query)?candidate.substring(query.length()):null);
        }else hideGhost();
    }
    private void hideGhost(){if(active!=null)active.setSuggestion(null);rows=0;}
    private boolean visible(){return active!=null&&rows>0&&!matches.isEmpty()&&!Objects.equals(dismissed,query);}
    private void dismiss(){dismissed=query;hideGhost();}
    private void accept(){String choice=matches.get(selected);active.setValue(choice);active.moveCursorToEnd();update();dismiss();}
    boolean keyPressed(int key) {
        update();
        if(key==GLFW.GLFW_KEY_SPACE&&Screen.hasControlDown()) {dismissed=null;update();return true;}
        if(!visible())return false;
        if(key==GLFW.GLFW_KEY_ESCAPE){dismiss();return true;}
        if(key==GLFW.GLFW_KEY_TAB||key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){accept();return true;}
        if(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN){selected=Math.floorMod(selected+(key==GLFW.GLFW_KEY_UP?-1:1),matches.size());update();return true;}
        return false;
    }
    boolean mouseClicked(double mx,double my,int button) {
        update();
        if(visible()&&mx>=x&&mx<x+width&&my>=y&&my<y+rows*ROW) {
            if(button==0){selected=first+(int)(my-y)/ROW;accept();}return true;
        }
        if(visible())dismiss();return false;
    }
    boolean mouseScrolled(double mx,double my,double delta) {
        update();
        if(!visible()||mx<x||mx>=x+width||my<y||my>=y+rows*ROW)return false;
        selected=Math.max(0,Math.min(matches.size()-1,selected-(int)Math.signum(delta)));update();return true;
    }
    void render(GuiGraphics g,int mx,int my) {
        update();if(!visible())return;
        g.pose().pushPose();g.pose().translate(0,0,400);
        g.fill(x-1,y-1,x+width+1,y+rows*ROW+1,0xFF888888);
        g.fill(x,y,x+width,y+rows*ROW,0xF0181818);
        for(int i=0;i<rows;i++) {
            int index=first+i,top=y+i*ROW;
            if(index==selected)g.fill(x,top,x+width,top+ROW,0xFF35546D);
            g.drawString(font,font.plainSubstrByWidth(matches.get(index),width-8),x+4,top+3,index==selected?0xFFFFFF:0xCCCCCC);
        }
        g.pose().popPose();
        if(mx>=x&&mx<x+width&&my>=y&&my<y+rows*ROW) {
            String hovered=matches.get(first+(my-y)/ROW);
            if(font.width(hovered)>width-8)g.renderTooltip(font,Component.literal(hovered),mx,my);
        }
    }
}

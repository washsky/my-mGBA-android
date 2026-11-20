/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.util.Locale;

/** Small native HUD: pixel density aware, non-interactive, updated twice a second. */
public final class MGBAHud {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile MGBAHud active;
    private final Activity activity;
    private final RelativeLayout parent;
    private final LinearLayout panel,rateRow;
    private final TextView title,number,speed;
    private int shoulderRight,shoulderBottom;
    private MGBAHud(Activity activity,RelativeLayout parent) {
        this.activity=activity;this.parent=parent;
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(11),dp(7),dp(11),dp(7));
        panel.setClickable(false);panel.setFocusable(false);
        GradientDrawable background=new GradientDrawable();background.setColor(0xC417212B);
        background.setCornerRadius(dp(10));background.setStroke(dp(1),0x684F6F7A);panel.setBackground(background);
        title=text(10,0xFFB4C5CE);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);panel.addView(title);
        rateRow=new LinearLayout(activity);rateRow.setGravity(Gravity.BOTTOM);rateRow.setBaselineAligned(true);
        number=text(21,0xFFF2FAFF);number.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));number.setIncludeFontPadding(false);
        TextView unit=text(10,0xFFB4C5CE);unit.setText("FPS");unit.setPadding(dp(5),0,0,dp(2));
        rateRow.addView(number);rateRow.addView(unit);panel.addView(rateRow);
        speed=text(11,0xFF73D8EC);speed.setSingleLine(true);panel.addView(speed);
        parent.addView(panel,new RelativeLayout.LayoutParams(-2,-2));panel.setVisibility(View.GONE);
        parent.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol || b-t!=ob-ot)position();});
    }
    private int dp(int value){return MGBAUi.dp(activity,value);}
    private TextView text(int size,int color){TextView v=new TextView(activity);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("tnum");return v;}
    public static void attach(Activity activity,RelativeLayout parent){active=new MGBAHud(activity,parent);}
    public static void detach(Activity activity){if(active!=null && active.activity==activity)active=null;}
    private void position(){
        int margin=dp(10),left=shoulderRight+margin;
        int width=parent.getWidth()>0?parent.getWidth():activity.getResources().getDisplayMetrics().widthPixels;
        int available=width/2-dp(26)-margin-left;
        int top=margin;
        if(available<dp(105)){left=margin;top=shoulderBottom+margin;available=Math.min(dp(170),width-2*margin);}
        int max=Math.max(dp(80),Math.min(dp(175),available));
        title.setMaxWidth(max-dp(22));speed.setMaxWidth(max-dp(22));
        RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)panel.getLayoutParams();
        if(p.leftMargin!=left || p.topMargin!=top){p.leftMargin=left;p.topMargin=top;panel.setLayoutParams(p);}
    }
    /** Called from SDL. Posting never waits for the UI thread or touches emulator state. */
    public static void update(String game,float fps,float emu,float target,boolean showFps,
                              boolean accelerating,boolean remote,boolean showOsd,int right,int bottom){
        MGBAHud hud=active;if(hud==null)return;
        MAIN.post(()->{
            if(active!=hud || hud.activity.isFinishing())return;
            hud.shoulderRight=right;hud.shoulderBottom=bottom;hud.position();
            hud.title.setText(game);hud.title.setVisibility(game.isEmpty()?View.GONE:View.VISIBLE);
            hud.rateRow.setVisibility(showFps?View.VISIBLE:View.GONE);
            hud.number.setText(fps>0?String.format(Locale.ROOT,"%.1f",fps):"—");
            boolean showSpeed=showOsd && (accelerating || remote);
            hud.speed.setVisibility(showSpeed?View.VISIBLE:View.GONE);
            String velocity=emu>0?String.format(Locale.ROOT,"%.1f×",emu/60f):"—";
            hud.speed.setText(accelerating?velocity+" / "+(target>0?String.format(Locale.ROOT,"%.0f×",target):"MAX"):"模拟速度 "+velocity);
            hud.panel.setVisibility(showFps || showSpeed || !game.isEmpty()?View.VISIBLE:View.GONE);
            hud.panel.setContentDescription("画面 "+Math.round(fps)+" 帧每秒"+(showSpeed?"，模拟速度 "+velocity:""));
        });
    }
}

package com.velpro.checkin;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Small native styling helpers, no UI framework or network dependency. */
final class Ui {
    static final int BG = Color.rgb(247,245,249), INK = Color.rgb(35,42,68);
    static final int MUTED = Color.rgb(102,112,138), BLUE = Color.rgb(63,116,232);
    static final int GREEN = Color.rgb(30,124,105), RED = Color.rgb(175,55,68);
    static int dp(Context c,int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable surface(int color,int radius,Context c) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c,radius)); return g;
    }
    static GradientDrawable oval(int... colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);
        g.setShape(GradientDrawable.OVAL); return g;
    }
    static void bars(Activity a) {
        a.getWindow().setStatusBarColor(BG); a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    static LinearLayout page(Activity a) {
        bars(a);
        ScrollView s = new ScrollView(a); s.setFillViewport(true); s.setBackgroundColor(BG);
        LinearLayout l = column(a); l.setPadding(dp(a,24),dp(a,20),dp(a,24),dp(a,28));
        s.addView(l,new ScrollView.LayoutParams(-1,-2)); a.setContentView(s); return l;
    }
    static TextView text(Context c,String value,int size,int color) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(c,3),1); return t;
    }
    static TextView heading(Context c,String value,int size) {
        TextView t = text(c,value,size,INK); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    static void gap(LinearLayout root,int size) { View v = new View(root.getContext()); root.addView(v,new LinearLayout.LayoutParams(1,dp(root.getContext(),size))); }
    static LinearLayout card(LinearLayout root) {
        Context c = root.getContext(); LinearLayout card = column(c);
        root.setClipChildren(false); root.setClipToPadding(false); // leave room for card shadows
        card.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,18)); card.setBackground(surface(Color.WHITE,22,c));
        card.setElevation(dp(c,4));
        root.addView(card,new LinearLayout.LayoutParams(-1,-2)); return card;
    }
    static Button button(Context c,String value,boolean primary) {
        Button b = new Button(c); b.setText(value); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : BLUE);
        b.setMinHeight(dp(c,52)); b.setMinimumHeight(dp(c,52)); b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));
        GradientDrawable bg = surface(primary ? BLUE : Color.WHITE,16,c);
        b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225C6DDD),bg,surface(Color.WHITE,16,c)));
        b.setStateListAnimator(null); return b;
    }
    static TextView avatar(Context c,String letter,int color) {
        TextView t=heading(c,letter,24); t.setGravity(Gravity.CENTER);
        t.setBackground(oval(color,Color.WHITE)); t.setTextColor(BLUE);
        return t;
    }
    static Button roundIcon(Context c,String glyph,int color) {
        Button b=button(c,glyph,false); b.setTextColor(color); b.setTextSize(26);
        b.setPadding(0,0,0,0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x223F74E8),oval(Color.WHITE,Color.WHITE),oval(Color.WHITE,Color.WHITE)));
        return b;
    }
    static void divider(LinearLayout card) {
        Context c=card.getContext(); View line=new View(c); line.setBackgroundColor(0xffECE9F0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,1)); p.topMargin=dp(c,14); p.bottomMargin=dp(c,6);
        card.addView(line,p);
    }
    static void styleSwitch(Switch sw) {
        int[][] states={{-android.R.attr.state_enabled},{android.R.attr.state_checked},{}};
        sw.setTrackTintList(new ColorStateList(states,new int[]{0xffDEDEE5,BLUE,0xffCED2DC}));
        sw.setThumbTintList(new ColorStateList(states,new int[]{0xffF0EFF3,Color.WHITE,Color.WHITE}));
        sw.setTextSize(13); sw.setTextColor(MUTED);
    }
    static void header(Activity a,LinearLayout root,String title) {
        LinearLayout line = new LinearLayout(a); line.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(a,"‹ 返回",false); back.setOnClickListener(v -> a.finish());
        line.addView(back,new LinearLayout.LayoutParams(dp(a,80),dp(a,48)));
        TextView t = heading(a,title,24); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2); p.leftMargin=dp(a,14);
        line.addView(t,p); root.addView(line); gap(root,24);
    }
}

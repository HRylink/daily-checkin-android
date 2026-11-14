package com.velpro.checkin;

import android.app.Activity;
import android.os.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** A separate timeline of attempts; an attempt is not a new grant. */
public class HistoryActivity extends Activity {
    private LinearLayout records;
    private String previous = null;
    private boolean visible;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (!visible) return; load(); ui.postDelayed(this,2000); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout root = Ui.page(this);
        Ui.header(this,root,"签到记录");
        root.addView(Ui.text(this,"最近 100 次尝试 · 按时间倒序\n同一天多次检查不代表重复发放奖励",12,Ui.MUTED)); Ui.gap(root,20);
        records = Ui.column(this); root.addView(records); load();
    }
    private void load() {
        List<String> rows;
        try { rows = new History(new File(getFilesDir(),"history.jsonl")).recent(100); }
        catch (Exception e) {
            records.removeAllViews(); records.addView(Ui.text(this,"记录暂时无法读取，请检查存储空间后重新进入",14,Ui.RED)); return;
        }
        String current = rows.toString(); if (current.equals(previous)) return; previous=current;
        records.removeAllViews();
        if (rows.isEmpty()) {
            LinearLayout empty = Ui.card(records); empty.addView(Ui.heading(this,"还没有签到记录",20)); Ui.gap(empty,12);
            empty.addView(Ui.text(this,"返回首页，点圆形按钮开始。成功、失败和未确认的尝试都会留在这里。",14,Ui.MUTED));
            Button back = Ui.button(this,"返回首页去签到",true); back.setOnClickListener(v -> finish());
            Ui.gap(empty,20); empty.addView(back,new LinearLayout.LayoutParams(-1,-2)); return;
        }
        String lastDate = "";
        for (String row : rows) {
            String[] parts = row.split("  ",4); if (parts.length != 4) continue;
            String day = parts[0].split(" ",2)[0];
            if (!day.equals(lastDate)) { records.addView(Ui.heading(this,day,16)); Ui.gap(records,12); lastDate=day; }
            boolean ok = "✓".equals(parts[2]); LinearLayout card = Ui.card(records);
            TextView title = Ui.heading(this,parts[1] + "  ·  " + (ok ? "已确认" : "失败 / 未确认"),16);
            title.setTextColor(ok ? Ui.GREEN : Ui.RED); card.addView(title); Ui.gap(card,8);
            card.addView(Ui.text(this,parts[0],12,Ui.MUTED)); Ui.gap(card,12);
            TextView message = Ui.text(this,parts[3],14,Ui.INK); message.setTextIsSelectable(true); card.addView(message); Ui.gap(records,14);
        }
    }
    @Override protected void onResume() { super.onResume(); visible=true; ui.removeCallbacks(refresh); ui.post(refresh); }
    @Override protected void onPause() { visible=false; ui.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { ui.removeCallbacks(refresh); super.onDestroy(); }
}

package com.velpro.checkin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.net.CookieHandler;
import java.text.SimpleDateFormat;
import java.util.*;

/** A calm launch screen. Normal work belongs to the foreground Service, not this Activity. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button go;
    private ProgressBar ring;
    private TextView status, setup, date;
    private boolean visible;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (!visible) return; refreshState(); ui.postDelayed(this,1000); }
    };
    private int dp(int n) { return Ui.dp(this,n); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); CookieHandler.setDefault(null); buildUi(); refreshState();
    }
    private void buildUi() {
        LinearLayout root = Ui.page(this);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.column(this); titles.addView(Ui.heading(this,"每日签到",28));
        Ui.gap(titles,8); date = Ui.text(this,"",13,Ui.MUTED); titles.addView(date);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button settings = Ui.button(this,"设置",false);
        settings.setOnClickListener(v -> startActivity(new Intent(this,SettingsActivity.class)));
        header.addView(settings,new LinearLayout.LayoutParams(dp(72),dp(48))); root.addView(header);
        Ui.gap(root,28);
        setup = Ui.text(this,"",13,Ui.BLUE); setup.setGravity(Gravity.CENTER);
        setup.setPadding(dp(12),dp(12),dp(12),dp(12)); setup.setMinHeight(dp(48));
        setup.setBackground(Ui.surface(0xFFECEFFA,16,this));
        setup.setOnClickListener(v -> startActivity(new Intent(this,SettingsActivity.class)));
        root.addView(setup,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,24);

        FrameLayout orbit = new FrameLayout(this); orbit.setBackground(Ui.oval(0xFFEAEDFC,0xFFF0EBFD));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(264),dp(264)); op.gravity=Gravity.CENTER_HORIZONTAL;
        root.addView(orbit,op);
        View halo = new View(this); GradientDrawable outline = Ui.oval(Color.TRANSPARENT,Color.TRANSPARENT);
        outline.setStroke(dp(1),0xFFD3D9F6); halo.setBackground(outline);
        orbit.addView(halo,new FrameLayout.LayoutParams(dp(244),dp(244),Gravity.CENTER));
        go = new Button(this); go.setAllCaps(false); go.setTextSize(23); go.setTextColor(Color.WHITE);
        go.setTypeface(Typeface.DEFAULT,Typeface.BOLD); go.setGravity(Gravity.CENTER);
        go.setPadding(0,0,0,0); go.setBackgroundTintList(null); go.setStateListAnimator(null);
        go.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                Ui.oval(0xFF537CED,0xFF7661DB),Ui.oval(Color.WHITE,Color.WHITE)));
        go.setElevation(dp(6)); go.setOnClickListener(v -> startRun());
        orbit.addView(go,new FrameLayout.LayoutParams(dp(208),dp(208),Gravity.CENTER));
        ring = new ProgressBar(this); ring.setIndeterminateTintList(ColorStateList.valueOf(Ui.BLUE));
        ring.setVisibility(View.GONE); ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        orbit.addView(ring,new FrameLayout.LayoutParams(dp(248),dp(248),Gravity.CENTER));
        Ui.gap(root,24);
        Button records = Ui.button(this,"每日签到记录   ›",false);
        records.setOnClickListener(v -> startActivity(new Intent(this,HistoryActivity.class)));
        root.addView(records,new LinearLayout.LayoutParams(-1,dp(56)));
        Ui.gap(root,20);
        LinearLayout result = Ui.card(root); result.addView(Ui.heading(this,"签到动态",16)); Ui.gap(result,12);
        status = Ui.text(this,"",14,Ui.MUTED); status.setTextIsSelectable(true); result.addView(status);
        Ui.gap(root,18);
        TextView footer = Ui.text(this,"手动发起 · 后台继续 · 记录仅保存在本机",11,Ui.MUTED);
        footer.setGravity(Gravity.CENTER); root.addView(footer);
    }
    private void actionText(boolean busy) {
        String title = busy ? "签到中" : "每日签到";
        SpannableString s = new SpannableString(title + "\n" + (busy ? "可切换到其他应用" : "点击检查并领取"));
        s.setSpan(new RelativeSizeSpan(0.53f),title.length()+1,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        go.setText(s); go.setContentDescription(busy ? "每日签到，后台执行中" : "每日签到，点击开始已启用站点签到");
        go.setAlpha(busy ? 0.8f : 1f); ring.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    private void startRun() {
        if (CheckinService.isRunning()) { Toast.makeText(this,"任务正在执行，请勿重复点击",Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
        try {
            startForegroundService(new Intent(this,CheckinService.class).setAction(CheckinService.ACTION_RUN));
            go.setEnabled(false); actionText(true); status.setText("任务已启动，现在可以切换到其他应用。");
        } catch (RuntimeException e) { status.setText("无法启动后台任务，请保持应用打开后重试。"); }
    }
    private void refreshState() {
        boolean busy = CheckinService.isRunning(); go.setEnabled(!busy); actionText(busy);
        date.setText(new SimpleDateFormat("MM月dd日  EEEE",Locale.CHINA).format(new Date()));
        boolean has25 = !Store.get(this,"user").isEmpty() && !Store.get(this,"pass").isEmpty();
        boolean hasAR = SettingsActivity.hasGitHub(this);
        setup.setText(has25 && hasAR ? "账号已准备好 · 点圆形按钮开始" : "首次使用 / 授权失效？点这里完成设置  ›");
        String value = Store.get(this,"status");
        status.setText(value.isEmpty() ? "设置一次，之后一键签到。网络波动会核验结果，不重复提交奖励请求。" : value);
    }
    @Override protected void onResume() { super.onResume(); visible=true; ui.removeCallbacks(refresh); ui.post(refresh); }
    @Override protected void onPause() { visible=false; ui.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { ui.removeCallbacks(refresh); super.onDestroy(); } // never stop Service
}

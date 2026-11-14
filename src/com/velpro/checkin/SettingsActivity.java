package com.velpro.checkin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/** Account management: legacy credentials, a generic account list, and URL-driven adding. */
public class SettingsActivity extends Activity {
    private EditText user, password;
    private Button save, auth, addAccount;
    private TextView authStatus, busyStatus, notificationStatus;
    private LinearLayout accountList, credentials;
    private boolean showGitHubAuth;
    private final List<View> accountControls = new ArrayList<>();
    private final Map<String,TextView> balanceViews = new LinkedHashMap<>();
    private static final Balances BALANCES = new Balances(); // no Activity retained; per-site 30s throttle survives recreation
    private volatile boolean visible;
    private boolean balanceLoading, lastServiceRunning;
    private long nextBalanceRefresh;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (!visible) return; refreshState(); refreshBalances(); ui.postDelayed(this,1000); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout root=Ui.page(this); settingsHeader(root);
        addAccount=Ui.button(this,"+ 添加账号",true);
        addAccount.setOnClickListener(v -> { if (!CheckinService.isRunning() && !balanceLoading) showAddDialog(); });
        root.addView(addAccount,new LinearLayout.LayoutParams(-1,Ui.dp(this,56))); Ui.gap(root,20);

        root.addView(Ui.heading(this,"我的账号",16)); Ui.gap(root,12);
        accountList=Ui.column(this); root.addView(accountList,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout instructions=Ui.column(this); root.addView(instructions); Ui.gap(instructions,12);
        LinearLayout info=new LinearLayout(this); info.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=Ui.avatar(this,"ⓘ",Ui.INK); icon.setBackground(Ui.oval(Ui.INK,Ui.INK)); icon.setTextColor(android.graphics.Color.WHITE);
        info.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32)));
        TextView title=Ui.heading(this,"使用说明",16); LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-2,-2); titleParams.leftMargin=Ui.dp(this,10);
        info.addView(title,titleParams); instructions.addView(info); Ui.gap(instructions,10);
        instructions.addView(Ui.text(this,"添加账号后，可分别管理各账号的签到状态与余额信息。",12,Ui.MUTED)); Ui.gap(instructions,6);
        instructions.addView(Ui.text(this,"开启签到后，点首页圆形按钮会为该账号领取；未开启的账号自动跳过。",12,Ui.MUTED)); Ui.gap(root,20);
        save=Ui.button(this,"保存设置",true); save.setOnClickListener(v -> saveCredentials());
        root.addView(save,new LinearLayout.LayoutParams(-1,Ui.dp(this,56))); Ui.gap(root,18);
        notificationStatus=settingRow(root,"🔔","通知设置","",this::openNotifications); Ui.gap(root,12);
        settingRow(root,"▤","签到记录","查看",() -> startActivity(new Intent(this,HistoryActivity.class))); Ui.gap(root,18);
        busyStatus=Ui.text(this,"",11,Ui.MUTED); root.addView(busyStatus); Ui.gap(root,18);
        TextView footer=Ui.text(this,"版本 v" + Version.NAME + " · 每日签到",12,Ui.MUTED); footer.setGravity(Gravity.CENTER); root.addView(footer); Ui.gap(root,12);
        settingRow(root,"▣","GitHub 仓库","查看源码",this::openRepository);
        renderAccounts(); refreshState();
    }
    private void settingsHeader(LinearLayout root) {
        FrameLayout line=new FrameLayout(this);
        TextView title=Ui.heading(this,"账号管理",23); title.setGravity(Gravity.CENTER);
        line.addView(title,new FrameLayout.LayoutParams(-1,Ui.dp(this,48)));
        Button back=Ui.roundIcon(this,"‹",Ui.BLUE); back.setContentDescription("返回"); back.setOnClickListener(v -> finish());
        line.addView(back,new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.LEFT));
        root.addView(line,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,20);
    }
    private Button accountHeader(LinearLayout card,String name,String letter,int color,Runnable edit) {
        LinearLayout line=new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(Ui.avatar(this,letter,color),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        TextView title=Ui.heading(this,name,17); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.leftMargin=Ui.dp(this,12);
        line.addView(title,p);
        Button pencil=Ui.roundIcon(this,"✎",Ui.MUTED); pencil.setContentDescription("编辑 / 重新登录 " + name);
        pencil.setOnClickListener(v -> { if (!CheckinService.isRunning() && !balanceLoading) edit.run(); });
        line.addView(pencil,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48))); card.addView(line); Ui.gap(card,10); return pencil;
    }
    private void credentialForm(LinearLayout card,String draftUser,String draftPassword,boolean expanded) {
        credentials=Ui.column(this); card.addView(credentials); Ui.gap(credentials,10);
        user=new EditText(this); user.setSingleLine(true); user.setHint("77625 用户名");
        user.setText(draftUser != null ? draftUser : Store.get(this,"user"));
        password=new EditText(this); password.setSingleLine(true); password.setHint("77625 密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(draftPassword != null ? draftPassword : Store.get(this,"pass"));
        for (EditText input : new EditText[]{user,password}) {
            input.setTextSize(14); input.setTextColor(Ui.INK); input.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);
            input.setBackground(Ui.surface(Ui.BG,10,this)); credentials.addView(input,new LinearLayout.LayoutParams(-1,Ui.dp(this,48))); Ui.gap(credentials,8);
        }
        credentials.addView(Ui.text(this,"凭据仅加密保存在本机；填写后点底部「保存设置」。",11,Ui.MUTED));
        if (!expanded && !user.getText().toString().isEmpty() && !password.getText().toString().isEmpty()) credentials.setVisibility(View.GONE);
    }
    private void expandCredentials() {
        if (credentials == null || user == null) return;
        credentials.setVisibility(View.VISIBLE); user.requestFocus();
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(user,0);
    }
    private void balanceRow(LinearLayout card,TextView balance,Switch sw) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(balance,new LinearLayout.LayoutParams(0,-2,1)); sw.setText("启用签到"); Ui.styleSwitch(sw);
        row.addView(sw,new LinearLayout.LayoutParams(-2,-2)); card.addView(row);
    }
    private void setBalance(TextView view,String value) {
        android.text.SpannableString shown=new android.text.SpannableString(value);
        int start=value.indexOf("：")+1;
        int end=value.indexOf(" · "); if (end < 0) end=value.length();
        if (start > 0 && end > start)
            shown.setSpan(new android.text.style.ForegroundColorSpan(Ui.BLUE),start,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(shown);
    }
    private Button deleteRow(LinearLayout card,String siteId) {
        Ui.divider(card); LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.RIGHT);
        Button del=Ui.button(this,"🗑 删除",false); del.setTextColor(Ui.RED); del.setContentDescription("删除 " + siteId);
        del.setOnClickListener(v -> deleteAccount(siteId)); row.addView(del,new LinearLayout.LayoutParams(-2,Ui.dp(this,48))); card.addView(row); return del;
    }
    private void deleteAccount(String siteId) {
        if (CheckinService.isRunning() || balanceLoading) return;
        if ("77625".equals(siteId)) {
            Store.put(this,"user",""); Store.put(this,"pass","");
            if (user != null) user.setText(""); if (password != null) password.setText("");
        } else if ("agentrouter".equals(siteId)) {
            Store.put(this,"ar_cookie",""); Store.put(this,"ar_auth",""); showGitHubAuth=false;
            try { CookieManager.getInstance().removeAllCookies(ok -> CookieManager.getInstance().flush()); } catch (RuntimeException ignored) { }
        }
        AccountStore.remove(Store.kv(this),siteId); renderAccounts(); refreshState();
        Toast.makeText(this,"已移除账号并清除本机凭据与余额",Toast.LENGTH_SHORT).show();
    }
    private TextView settingRow(LinearLayout root,String icon,String name,String detail,Runnable action) {
        LinearLayout card=Ui.card(root); LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this,icon,20,Ui.BLUE),new LinearLayout.LayoutParams(Ui.dp(this,36),-2));
        row.addView(Ui.heading(this,name,15),new LinearLayout.LayoutParams(0,-2,1));
        TextView status=Ui.text(this,detail+"  ›",14,Ui.MUTED); row.addView(status); card.addView(row);
        card.setOnClickListener(v -> action.run()); return status;
    }
    private void openRepository() {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(Version.REPOSITORY_URL))); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(this,"无法打开 GitHub 仓库",Toast.LENGTH_SHORT).show(); }
    }
    private void openNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},3); return;
        }
        try { startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())); }
        catch (RuntimeException e) { Toast.makeText(this,"无法打开系统通知设置",Toast.LENGTH_SHORT).show(); }
    }
    private boolean notificationsAllowed() {
        return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                && getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
    }
    private static AccountStore.Account findAccount(List<AccountStore.Account> accounts,String id) {
        for (AccountStore.Account a : accounts) if (id.equals(a.siteId)) return a; return null;
    }
    private void bindSwitch(Switch sw,AccountStore.Account a,String id) {
        sw.setOnCheckedChangeListener(null); sw.setTag(a != null && !a.invalid);
        sw.setChecked(a != null && a.enabled && !a.invalid);
        sw.setOnCheckedChangeListener((button,checked) -> {
            boolean previous=a != null && a.enabled && !a.invalid;
            if (CheckinService.isRunning() || balanceLoading || a == null || a.invalid) {
                if (checked != previous) button.setChecked(previous); return;
            }
            AccountStore.setEnabled(Store.kv(this),id,checked); renderAccounts(); refreshState();
        });
    }
    static boolean hasGitHub(Context c) {
        String cookie = "";
        try { cookie = CookieManager.getInstance().getCookie("https://github.com/login/oauth/authorize"); }
        catch (RuntimeException ignored) { }
        return cookie != null && cookie.contains("user_session=") && !"1".equals(Store.get(c,"ar_auth"));
    }
    private void saveCredentials() {
        if (CheckinService.isRunning() || balanceLoading) return;
        if (user == null || password == null) {
            Toast.makeText(this,"设置已保存；未设置 77625 账号",Toast.LENGTH_SHORT).show(); return;
        }
        String u = user.getText().toString().trim(), p = password.getText().toString();
        if (u.isEmpty() && p.isEmpty() && Store.get(this,"user").isEmpty() && Store.get(this,"pass").isEmpty()) {
            Toast.makeText(this,"设置已保存；未设置 77625 账号",Toast.LENGTH_SHORT).show(); return;
        }
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this,"账号和密码不能为空；也可不设置，仅签到 AgentRouter",Toast.LENGTH_LONG).show(); return;
        }
        AccountStore.KV kv=Store.kv(this);
        AccountStore.Account previous=findAccount(AccountStore.load(kv,hasGitHub(this)),"77625");
        boolean ok = Store.put(this,"user",u) & Store.put(this,"pass",p);
        if (ok) {
            AccountStore.add(kv,"77625",u);
            if (previous != null) AccountStore.setEnabled(kv,"77625",previous.enabled);
            renderAccounts();
        }
        Toast.makeText(this,ok ? "账号已保存，下次无需输入" : "保存失败，请检查存储空间",Toast.LENGTH_LONG).show();
    }
    /** First-launch migration only: never promote a pending failed verification to a verified account. */
    private void ensureLegacyAccounts(AccountStore.KV kv) {
        if (!Store.get(this, "accounts").isEmpty() || !Store.get(this, "pending_accounts").isEmpty()) return;
        List<AccountStore.Account> current = AccountStore.load(kv, hasGitHub(this));
        Set<String> ids = new HashSet<>();
        for (AccountStore.Account a : current) if (!a.invalid) ids.add(a.siteId);
        if (!Store.get(this,"user").isEmpty() && !Store.get(this,"pass").isEmpty() && !ids.contains("77625"))
            AccountStore.add(kv, "77625", Store.get(this,"user"));
        if (hasGitHub(this) && !ids.contains("agentrouter"))
            AccountStore.add(kv, "agentrouter", "GitHub 已授权");
        if (!Store.get(this,"wb_access").isEmpty() && !ids.contains("workbuddy-ai"))
            AccountStore.add(kv, "workbuddy-ai", Store.get(this,"wb_user"));
        if (!Store.get(this,"trae_token").isEmpty() && !Store.get(this,"trae_device_key").isEmpty()
                && !ids.contains("trae-cn"))
            AccountStore.add(kv, "trae-cn", Store.get(this,"trae_user"));
    }
    private void renderAccounts() {
        AccountStore.KV kv = Store.kv(this);
        ensureLegacyAccounts(kv);
        List<AccountStore.Account> accounts = AccountStore.load(kv, hasGitHub(this));
        String draftUser=user == null ? null : user.getText().toString();
        String draftPassword=password == null ? null : password.getText().toString();
        boolean expanded=credentials != null && credentials.getVisibility() == View.VISIBLE;
        accountList.removeAllViews(); accountControls.clear(); balanceViews.clear();
        user=null; password=null; credentials=null; auth=null; authStatus=null;
        int count=0;
        for (AccountStore.Account a : accounts) {
            SiteRegistry.Site s = a.custom != null ? SiteRegistry.customSite(a.siteId, a.custom) : SiteRegistry.byId(a.siteId);
            if (s == null) continue;
            String shown=a.custom != null ? a.custom.name : a.user == null || a.user.isEmpty() ? defaultName(s) : a.user;
            if (a.invalid) shown += "（验证失败）";
            accountRow(shown,a,s,kv,draftUser,draftPassword,expanded); count++;
        }
        if (count == 0) accountList.addView(Ui.text(this,"尚未添加账号，点顶部「+ 添加账号」输入站点网址。",13,Ui.MUTED));
    }
    private static String defaultName(SiteRegistry.Site s) {
        if ("legacy25".equals(s.kind)) return "未填写账号";
        if ("agentrouter".equals(s.kind)) return "尚未授权 GitHub";
        return "已登录账号";
    }
    private void accountRow(String label, AccountStore.Account a, SiteRegistry.Site s, AccountStore.KV kv,
            String draftUser,String draftPassword,boolean expanded) {
        LinearLayout card=Ui.card(accountList);
        boolean legacy="legacy25".equals(s.kind), agent="agentrouter".equals(s.kind);
        String letter=s.name.isEmpty() ? "?" : s.name.substring(0,s.name.offsetByCodePoints(0,1));
        int[] colors={0xffDFEAFB,0xffEEE3F6,0xffE0F0E8,0xffF8E9D8};
        Runnable edit=() -> {
            if (legacy) expandCredentials();
            else if (agent) { showGitHubAuth=true; if (auth != null) auth.setVisibility(View.VISIBLE); }
            else if (a.custom != null) showCustomDialog(a.custom.url,a.siteId,a.custom);
            else launch(s);
        };
        Button pencil=accountHeader(card,label,letter,colors[Math.floorMod(a.siteId.hashCode(),colors.length)],edit);
        card.addView(Ui.text(this,a.custom != null ? a.custom.url : s.name,11,Ui.MUTED)); Ui.gap(card,8);
        TextView balance=Ui.text(this,"",15,Ui.MUTED);
        if (a.invalid) balance.setText(a.error.isEmpty() ? "登录失效，请重新登录" : a.error);
        else { setBalance(balance,AccountStore.balanceText(kv,a.siteId,System.currentTimeMillis())); balanceViews.put(a.siteId,balance); }
        Switch sw=new Switch(this); bindSwitch(sw,a,a.siteId); balanceRow(card,balance,sw);
        if (legacy) credentialForm(card,draftUser,draftPassword,expanded);
        if (agent) {
            Ui.gap(card,10); authStatus=Ui.text(this,"",12,Ui.MUTED); card.addView(authStatus); Ui.gap(card,8);
            auth=Ui.button(this,"登录 / 重新授权 GitHub",false); auth.setOnClickListener(v -> launch(s));
            auth.setVisibility(showGitHubAuth || !hasGitHub(this) ? View.VISIBLE : View.GONE);
            card.addView(auth,new LinearLayout.LayoutParams(-1,-2)); accountControls.add(auth);
        }
        Button del=deleteRow(card,a.siteId);
        card.setOnClickListener(v -> {
            if (CheckinService.isRunning() || balanceLoading) return;
            if (Balances.supported(a.siteId) && !a.invalid) refreshBalances(); // 刷新余额仍遵守原节流
            else edit.run();
        });
        accountControls.add(pencil); accountControls.add(sw); accountControls.add(del); accountControls.add(card); Ui.gap(accountList,18);
    }
    private void showAddDialog() {
        LinearLayout panel = Ui.column(this); panel.setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,8));
        panel.addView(Ui.heading(this,"添加账号",18)); Ui.gap(panel,8);
        panel.addView(Ui.text(this,"输入或粘贴网址后自动识别，确认站点后点「开始登录」。",13,Ui.MUTED)); Ui.gap(panel,12);
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("https://www.workbuddy.ai");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); input.setTextSize(15); input.setTextColor(Ui.INK);
        panel.addView(input,new LinearLayout.LayoutParams(-1,Ui.dp(this,52))); Ui.gap(panel,10);
        TextView result = Ui.text(this,"",13,Ui.BLUE); panel.addView(result); Ui.gap(panel,8);
        Button start = Ui.button(this,"输入网址自动识别",true); start.setEnabled(false);
        panel.addView(start,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(panel,12);
        LinearLayout candidates=Ui.column(this); panel.addView(candidates);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll)
                .setNeutralButton("自定义站点（通用）",(d,w) -> showCustomDialog(input.getText().toString().trim()))
                .setNegativeButton("关闭",null).show();
        Runnable detect=() -> {
            String typed=input.getText().toString().trim(); candidates.removeAllViews();
            start.setVisibility(View.VISIBLE); start.setEnabled(false); start.setOnClickListener(null);
            if (typed.isEmpty()) { result.setText("输入网址后自动识别站点"); start.setText("输入网址自动识别"); return; }
            SiteRegistry.Detection d=SiteRegistry.detect(typed);
            if (d.sites.size() == 1) {
                SiteRegistry.Site chosen=d.sites.get(0);
                result.setText("识别为：" + chosen.name + "\n" + chosen.loginHint);
                start.setText("开始登录 " + chosen.name); start.setEnabled(true);
                start.setOnClickListener(v -> { dialog.dismiss(); launch(chosen); });
            } else if (d.sites.size() > 1) {
                result.setText("识别为：多个站点，请选择"); start.setVisibility(View.GONE);
                for (SiteRegistry.Site chosen : d.sites) {
                    Button choice=Ui.button(this,"开始登录 " + chosen.name,true);
                    choice.setOnClickListener(v -> { dialog.dismiss(); launch(chosen); });
                    candidates.addView(choice,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(candidates,8);
                }
            } else {
                if (SiteRegistry.TRAE_GLOBAL_HINT.equals(d.hint)) {
                    result.setText(d.hint); start.setText(d.hint);
                } else {
                    result.setText("未识别到已知站点，可直接添加该网站（填写领取接口）。");
                    start.setText("添加该网站"); start.setEnabled(true);
                    start.setOnClickListener(v -> { dialog.dismiss(); showCustomDialog(typed); });
                }
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { }
            @Override public void afterTextChanged(Editable text) { detect.run(); }
        });
        detect.run();
    }
    private EditText customField(LinearLayout panel, String label, boolean multiline) {
        panel.addView(Ui.text(this,label,13,Ui.INK));
        EditText input = new EditText(this); input.setHint(label); input.setTextSize(14); input.setTextColor(Ui.INK);
        input.setSingleLine(!multiline);
        if (multiline) { input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setMinLines(2); }
        panel.addView(input,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(panel,8); return input;
    }
    private void showCustomDialog(String suggested) { showCustomDialog(suggested,null,null); }
    private void showCustomDialog(String suggested,String editingId,SiteRegistry.Custom existing) {
        if (CheckinService.isRunning() || balanceLoading) return;
        LinearLayout panel = Ui.column(this); panel.setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,8));
        panel.addView(Ui.heading(this,existing == null ? "自定义站点（通用）" : "编辑自定义站点",18)); Ui.gap(panel,8);
        panel.addView(Ui.text(this,existing == null
                ? "添加时会立即领取一次以验证凭据。仅支持 https 和同源 Cookie；HTTP 2xx 视为通过，请自行查看站点原话确认业务结果。"
                : "保存配置不会调用领取接口。更换接口域名会清除旧 Cookie，需重新登录；点「重新登录 / 验证」仍会立即领取一次。",12,Ui.MUTED));
        Ui.gap(panel,12);
        EditText name = customField(panel,"名称",false);
        EditText url = customField(panel,"领取接口地址（https）",false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (CustomSite.validUrl(suggested)) url.setText(suggested);
        panel.addView(Ui.text(this,"方法",13,Ui.INK));
        Spinner method = new Spinner(this);
        ArrayAdapter<String> methods = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"GET","POST"});
        methods.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); method.setAdapter(methods);
        panel.addView(method,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        EditText body = customField(panel,"POST 请求体（可选，原样发送）",true);
        EditText headers = customField(panel,"额外请求头（可选，每行 名称: 值）",true);
        if (existing != null) {
            name.setText(existing.name); url.setText(existing.url); method.setSelection("POST".equals(existing.method) ? 1 : 0);
            body.setText(existing.body); headers.setText(existing.headers);
        }
        panel.addView(Ui.text(this,"例如 X-Requested-With: XMLHttpRequest。不要填写 Cookie 或 Authorization；Cookie 由登录页捕获并加密。POST 超时/断连不重放，当天不再重试。",12,Ui.MUTED));
        TextView error = Ui.text(this,"",13,Ui.BLUE); panel.addView(error);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setView(scroll)
                .setPositiveButton(existing == null ? "打开登录页" : "保存配置",null).setNegativeButton("取消",null);
        if (existing != null) builder.setNeutralButton("重新登录 / 验证",(d,w) -> launchCustom(editingId,existing));
        AlertDialog dialog=builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (CheckinService.isRunning() || balanceLoading) { error.setText("后台任务或余额查询正在执行，请稍候"); return; }
            SiteRegistry.Custom c = new SiteRegistry.Custom(); c.name = name.getText().toString().trim();
            c.url = url.getText().toString().trim(); c.method = method.getSelectedItem().toString();
            c.body = body.getText().toString(); c.headers = headers.getText().toString();
            String reason = CustomSite.validate(c);
            if (!CustomSite.validUrl(c.url) || !reason.isEmpty()) { error.setText(reason); return; }
            if (editingId != null) {
                try {
                    AccountStore.KV kv=Store.kv(this);
                    boolean originChanged=!CustomSite.home(existing.url).equals(CustomSite.home(c.url));
                    String cookie=originChanged ? "" : kv.get(AccountStore.customCookieKey(editingId));
                    // Disable first: partial storage failure must never send an old Cookie to a new origin.
                    if (originChanged) AccountStore.markInvalid(kv,editingId,"接口域名已改变，请重新登录 / 验证");
                    if (!AccountStore.saveCustom(kv,editingId,c,cookie)) { error.setText("保存配置失败"); return; }
                    dialog.dismiss(); renderAccounts(); refreshState();
                    Toast.makeText(this,"配置已保存，未调用领取接口",Toast.LENGTH_SHORT).show();
                } catch (RuntimeException e) { error.setText("保存配置失败，请检查本机存储"); }
                return;
            }
            String id = AccountStore.nextCustomId(Store.kv(this));
            dialog.dismiss(); launchCustom(id, c);
        });
    }
    private void launchCustom(String id, SiteRegistry.Custom config) {
        if (CheckinService.isRunning() || balanceLoading) {
            Toast.makeText(this,"后台任务正在执行，请等待结束后再添加账号",Toast.LENGTH_LONG).show(); return;
        }
        try {
            startActivityForResult(new Intent(this,CustomLoginActivity.class)
                    .putExtra(CustomLoginActivity.EXTRA_SITE_ID,id).putExtra(CustomLoginActivity.EXTRA_CONFIG,config.toJson().toString()),2);
        } catch (Exception e) { Toast.makeText(this,"站点配置无效，无法打开登录页",Toast.LENGTH_LONG).show(); }
    }
    private void launch(SiteRegistry.Site s) {
        if (CheckinService.isRunning() || balanceLoading) {
            Toast.makeText(this,"后台任务正在执行，请等待结束后再添加账号",Toast.LENGTH_LONG).show(); return;
        }
        if (s == null) return;
        AccountStore.KV kv = Store.kv(this);
        if ("SettingsActivity".equals(s.loginActivity)) {
            Toast.makeText(this,s.loginHint,Toast.LENGTH_LONG).show();
            if (findAccount(AccountStore.load(kv,hasGitHub(this)),s.id) == null)
                AccountStore.add(kv,s.id,""); // UI placeholder: 未填写账号; no login or claim here
            renderAccounts(); refreshState(); expandCredentials(); return;
        }
        if ("OAuthActivity".equals(s.loginActivity)) {
            startActivityForResult(new Intent(this,OAuthActivity.class),1); return;
        }
        if ("WbLoginActivity".equals(s.loginActivity)) {
            // Verification, not navigation, is what writes this site into the accounts list.
            startActivity(new Intent(this,WbLoginActivity.class).putExtra(WbLoginActivity.EXTRA_SITE_ID,s.id));
            return;
        }
        if ("TraeLoginActivity".equals(s.loginActivity)) {
            startActivity(new Intent(this,TraeLoginActivity.class));
        }
    }
    private void refreshBalances() {
        if (!visible || balanceLoading || CheckinService.isRunning()) return;
        long tick=SystemClock.elapsedRealtime();
        if (tick < nextBalanceRefresh) return;
        nextBalanceRefresh=tick + Balances.INTERVAL_MS;
        balanceLoading=true; refreshState();
        final Context app=getApplicationContext();
        final boolean github=hasGitHub(this);
        new Thread(() -> {
            try {
                Store.restoreBalance77625(app); // historical read only, never the login POST
                AccountStore.KV kv=Store.kv(app);
                for (AccountStore.Account a : AccountStore.load(kv,github)) {
                    if (!visible || CheckinService.isRunning()) break;
                    BALANCES.refresh(kv,a,System.currentTimeMillis());
                }
            } catch (RuntimeException ignored) { // storage/parse failures never crash the settings page
            } finally {
                ui.post(() -> { balanceLoading=false; if (visible && !isDestroyed()) refreshState(); });
            }
        },"balance-read-only").start();
    }
    private void refreshState() {
        boolean serviceBusy=CheckinService.isRunning();
        if (lastServiceRunning && !serviceBusy) renderAccounts();
        lastServiceRunning=serviceBusy;
        boolean busy=serviceBusy || balanceLoading; save.setEnabled(!busy);
        if (auth != null) auth.setEnabled(!busy);
        if (user != null) user.setEnabled(!busy); if (password != null) password.setEnabled(!busy); addAccount.setEnabled(!busy);
        for (View v : accountControls) v.setEnabled(!busy && !Boolean.FALSE.equals(v.getTag()));
        notificationStatus.setText((notificationsAllowed() ? "已开启" : "未开启") + "  ›");
        AccountStore.KV kv=Store.kv(this); long now=System.currentTimeMillis();
        for (Map.Entry<String,TextView> item : balanceViews.entrySet())
            setBalance(item.getValue(),AccountStore.balanceText(kv,item.getKey(),now));
        if (authStatus != null) {
            boolean ready=hasGitHub(this);
            authStatus.setText(ready ? "已保留 GitHub 登录态，失效时会提示重新授权" : "尚未登录，或需要重新授权 GitHub");
            if (!ready && auth != null) auth.setVisibility(View.VISIBLE);
        }
        String list = Store.get(this, "accounts");
        busyStatus.setText(serviceBusy ? "后台签到中，账号和授权暂不可修改" : balanceLoading ? "正在静默查询只读余额…"
                : (list.isEmpty() ? "尚未建立账号清单；覆盖安装会自动迁移旧数据。"
                                  : "账号和记录仅保存在本机。覆盖安装可保留；卸载会清除。"));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == 1 && result == RESULT_OK && hasGitHub(this)) {
            AccountStore.add(Store.kv(this),"agentrouter","GitHub 已授权"); showGitHubAuth=false;
        }
        renderAccounts(); refreshState();
    }
    @Override protected void onResume() { super.onResume(); visible=true; nextBalanceRefresh=0; renderAccounts(); ui.removeCallbacks(refresh); ui.post(refresh); }
    @Override protected void onPause() { visible=false; ui.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { visible=false; ui.removeCallbacks(refresh); super.onDestroy(); }
}

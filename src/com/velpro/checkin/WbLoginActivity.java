package com.velpro.checkin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;

/** Interactive WorkBuddy login for either edition; a read-only status call verifies the token. */
public class WbLoginActivity extends Activity {
    public static final String EXTRA_SITE_ID = "siteId";
    private WebView web;
    private TextView status;
    private WorkBuddy.Site wbSite;
    private WorkBuddy wb;
    private SiteRegistry.Site site;
    private String siteId = "workbuddy-ai";
    private volatile int generation;
    private volatile boolean done;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (CheckinService.isRunning()) {
            Toast.makeText(this,"后台任务正在执行，请等待结束后再添加账号",Toast.LENGTH_LONG).show();
            finish(); return;
        }
        String requested = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SITE_ID);
        if (requested != null && !requested.isEmpty()) siteId = requested;
        site = SiteRegistry.byId(siteId);
        if (site == null || site.wbSite == null || !"workbuddy".equals(site.kind)) {
            siteId = "workbuddy-ai"; site = SiteRegistry.byId(siteId);
        }
        wbSite = site.wbSite;
        wb = new WorkBuddy(wbSite, 12000, 20000, 1000, 600);
        LinearLayout root = Ui.page(this); Ui.header(this,root,"添加 " + site.name);
        root.addView(Ui.text(this,"请在下方页面完成 " + site.name + " 登录；令牌保存后还会通过只读状态接口验证。",13,Ui.MUTED));
        Ui.gap(root,12); status = Ui.text(this,"正在准备登录…",14,Ui.BLUE); root.addView(status); Ui.gap(root,12);
        Button retry = Ui.button(this,"重新打开登录页",false);
        retry.setOnClickListener(v -> start()); root.addView(retry,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,12);
        web = new WebView(this); WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return req.isForMainFrame() && blocked(req.getUrl());
            }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return blocked(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { blocked(Uri.parse(url)); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !done) status.setText("登录页面加载失败，请检查网络后重试。");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError err) {
                handler.cancel(); status.setText("网站证书验证失败，已停止登录。");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,Ui.dp(this,520)));
        start();
    }
    private void start() {
        if (done || CheckinService.isRunning()) return;
        int g = ++generation;
        status.setText("正在准备登录…");
        Thread t = new Thread(() -> {
            try {
                WorkBuddy.Start s = wb.begin();
                runOnUiThread(() -> {
                    if (g != generation || isFinishing() || isDestroyed()) return;
                    Uri u = Uri.parse(s.url);
                    if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
                        fail(g,"登录地址不安全，已停止"); return;
                    }
                    status.setText("请在页面中完成登录；登录成功后会验证凭据。");
                    web.loadUrl(s.url);
                    poll(g,s);
                });
            } catch (Exception e) { fail(g,"登录准备失败：" + e.getMessage()); }
        },"wb-login-start"); t.setDaemon(true); t.start();
    }
    private void poll(int g, WorkBuddy.Start s) {
        Thread t = new Thread(() -> {
            try {
                WorkBuddy.Auth a = wb.finish(s);
                String shown = a.user == null || a.user.isEmpty() ? a.uid : a.user;
                AccountStore.KV kv = Store.kv(this);
                AccountStore.saveWorkBuddy(kv, siteId, a); // keep credentials even if verification fails
                runOnUiThread(() -> { if (g == generation && !done) status.setText("正在通过只读状态接口验证凭据…"); });
                SiteRegistry.Verify v = wb.verify(a);
                runOnUiThread(() -> {
                    if (g != generation || done || isFinishing() || isDestroyed()) return;
                    web.stopLoading();
                    if (v.ok) {
                        done = true;
                        AccountStore.add(kv, siteId, shown); // only a verified account joins the list
                        status.setText("验证通过，请关闭本页返回账号管理。");
                        Toast.makeText(this,site.name + " 已添加",Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK,new Intent().putExtra("ok",true));
                    } else {
                        AccountStore.markInvalid(kv, siteId, v.message);
                        String reason = v.invalid ? "凭据无效，请重新登录" : "网络异常，无法验证凭据；请检查网络后重试";
                        status.setText(reason + "。凭据已保留，但此账号暂不参与签到。\n" + v.message);
                        Toast.makeText(this,reason,Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) { fail(g,"登录未完成：" + e.getMessage()); }
        },"wb-login-poll"); t.setDaemon(true); t.start();
    }
    private void fail(int g, String msg) {
        runOnUiThread(() -> {
            if (g != generation || isFinishing() || isDestroyed()) return;
            status.setText(msg); Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        });
    }
    private boolean blocked(Uri u) {
        if (u == null) return true;
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
            web.stopLoading(); status.setText("已阻止非安全登录跳转，请重试。"); return true;
        }
        return false;
    }
    @Override protected void onDestroy() {
        generation++;
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}

package com.velpro.checkin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;

/** Interactive HTTPS login. Only the endpoint's Cookie is encrypted and used for one claim verification. */
public class CustomLoginActivity extends Activity {
    public static final String EXTRA_SITE_ID = "siteId", EXTRA_CONFIG = "customConfig";
    private WebView web;
    private TextView status;
    private Button button, retry;
    private SiteRegistry.Custom config;
    private AccountStore.KV kv;
    private String siteId;
    private volatile boolean busy, done;
    private volatile int generation;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (CheckinService.isRunning()) {
            Toast.makeText(this,"后台任务正在执行，请等待结束后再添加账号",Toast.LENGTH_LONG).show(); finish(); return;
        }
        kv = Store.kv(this);
        try {
            siteId = getIntent().getStringExtra(EXTRA_SITE_ID);
            if (siteId == null || !siteId.matches("custom_[0-9]+")) throw new IllegalArgumentException();
            String raw = getIntent().getStringExtra(EXTRA_CONFIG);
            config = raw == null ? AccountStore.customConfigs(kv).get(siteId) : SiteRegistry.Custom.fromJson(new JSONObject(raw));
            String error = CustomSite.validate(config);
            if (!error.isEmpty()) throw new IllegalArgumentException(error);
        } catch (Exception e) {
            Toast.makeText(this,"自定义站点配置无效，请返回设置重新添加",Toast.LENGTH_LONG).show(); finish(); return;
        }
        LinearLayout root = Ui.page(this); Ui.header(this,root,"添加 " + config.name);
        root.addView(Ui.text(this,"请在页面内完成登录，然后点「我已完成登录」。添加时会立即领取一次以验证凭据。\n"
                + "Cookie 必须属于领取接口同源站点；HTTP 2xx 视为验证通过，不解析站点业务结果。\n" + config.url,13,Ui.MUTED));
        Ui.gap(root,12); status = Ui.text(this,"",14,Ui.BLUE); root.addView(status); Ui.gap(root,12);
        button = Ui.button(this,"我已完成登录",true);
        button.setOnClickListener(v -> verify()); root.addView(button,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,8);
        retry = Ui.button(this,"重新打开",false);
        retry.setOnClickListener(v -> open()); root.addView(retry,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,12);
        web = new WebView(this); WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) { return blocked(req.getUrl()); }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blocked(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) { blocked(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (!CustomSite.validUrl(req.getUrl().buildUpon().fragment(null).build().toString()))
                    return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame() && !busy && !done) status.setText("登录页面加载失败，请检查网络或重新打开。");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); if (!busy && !done) status.setText("网站证书验证失败，已停止登录。");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,Ui.dp(this,520))); open();
    }
    private boolean blocked(Uri u) {
        if (u == null || !CustomSite.validUrl(u.buildUpon().fragment(null).build().toString())) {
            web.stopLoading(); if (!busy && !done) status.setText("已阻止非 https 或不安全的登录跳转。"); return true;
        }
        return false;
    }
    private void open() {
        if (busy || done || CheckinService.isRunning()) return;
        boolean blocked = "POST".equals(config.method) && AccountStore.customBlocked(kv, siteId);
        button.setEnabled(!blocked);
        status.setText(blocked ? CustomSite.UNCONFIRMED : "请完成登录；验证时会调用领取接口一次。POST 超时/断连当天不再重试。");
        web.loadUrl(CustomSite.home(config.url));
    }
    private void verify() {
        if (busy || done || CheckinService.isRunning()) return;
        if ("POST".equals(config.method) && AccountStore.customBlocked(kv, siteId)) {
            status.setText(CustomSite.UNCONFIRMED); button.setEnabled(false); return;
        }
        String raw;
        try { raw = CookieManager.getInstance().getCookie(config.url); }
        catch (RuntimeException e) { status.setText("无法读取登录 Cookie，请重新登录。"); return; }
        final String cookie = raw == null ? "" : raw.trim();
        if (cookie.isEmpty()) { status.setText("未捕获到领取接口的 Cookie，请在同源网站完成登录后再试。"); return; }
        // Store.kv routes *_cookie through AndroidKeyStore AES-GCM, never plain preferences.
        if (!AccountStore.saveCustom(kv, siteId, config, cookie)) {
            status.setText("Cookie 加密保存失败，本次未发送请求，请检查存储空间后重试。"); return;
        }
        if ("POST".equals(config.method) && !AccountStore.beginCustomPost(kv, siteId)) {
            status.setText("无法保存 POST 防重发状态，本次未发送；若已发出请求，请到网站查看。"); return;
        }
        busy = true; button.setEnabled(false); retry.setEnabled(false); web.stopLoading();
        int g = ++generation; status.setText("凭据已加密保存，正在领取一次以验证…");
        Thread t = new Thread(() -> {
            CustomSite.Result r = new CustomSite().check(config, cookie);
            // Complete persistence even if the page is closed while the request is in flight.
            AccountStore.finishCustom(kv, siteId, r);
            boolean added = r.ok && AccountStore.addCustom(kv, siteId, config, cookie);
            if (!r.ok) AccountStore.markInvalid(kv, siteId, r.message);
            runOnUiThread(() -> {
                if (g != generation || isFinishing() || isDestroyed()) return;
                busy = false;
                if (added) {
                    done = true;
                    status.setText("验证通过，账号已加入清单；本次可能已领取今日额度。\n" + r.message);
                    Toast.makeText(this,config.name + " 已添加",Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK,new Intent().putExtra("ok",true));
                } else {
                    retry.setEnabled(true); button.setEnabled(!r.unconfirmed);
                    status.setText((r.ok ? "领取成功，但账号清单保存失败；请先到网站查看，勿重复领取。" : r.message)
                            + "\n凭据已保留；未验证账号不加入签到清单，请检查站点返回信息。"
                            + (r.unconfirmed ? "\n" + CustomSite.UNCONFIRMED : ""));
                    if (r.ok) button.setEnabled(false);
                }
            });
        },"custom-login-verify"); t.setDaemon(true); t.start();
    }
    @Override protected void onDestroy() {
        generation++;
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}

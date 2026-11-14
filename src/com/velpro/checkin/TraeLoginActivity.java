package com.velpro.checkin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import java.util.UUID;
import org.json.JSONObject;

/** Interactive Trae CN login. The localhost callback is intercepted before any HTTP request. */
public class TraeLoginActivity extends Activity {
    private static final int CALLBACK_PORT = 8765;
    private static final String CALLBACK = "http://127.0.0.1:" + CALLBACK_PORT + "/authorize";
    private WebView web;
    private TextView status;
    private Trae.Device device;
    private final Trae trae = new Trae();
    private volatile int generation;
    private volatile boolean busy;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (CheckinService.isRunning()) {
            Toast.makeText(this,"后台任务正在执行，请等待结束后再添加账号",Toast.LENGTH_LONG).show();
            finish(); return;
        }
        LinearLayout root = Ui.page(this); Ui.header(this,root,"添加 Trae CN");
        root.addView(Ui.text(this,"请在下方页面完成 Trae CN 授权；回调由应用拦截，不会在本机启动服务器。",13,Ui.MUTED));
        Ui.gap(root,12); status = Ui.text(this,"正在准备设备身份…",14,Ui.BLUE); root.addView(status); Ui.gap(root,12);
        Button retry = Ui.button(this,"重新打开授权页",false);
        retry.setOnClickListener(v -> start()); root.addView(retry,new LinearLayout.LayoutParams(-1,-2)); Ui.gap(root,12);
        web = new WebView(this); WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return req.isForMainFrame() && handle(req.getUrl());
            }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handle(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { handle(Uri.parse(url)); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !busy) status.setText("授权页面加载失败，请检查网络后重试。");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError err) {
                handler.cancel(); status.setText("网站证书验证失败，已停止授权。");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,Ui.dp(this,520)));
        start();
    }
    private void start() {
        int g = ++generation; busy = false;
        status.setText("正在准备设备身份…");
        Thread t = new Thread(() -> {
            try {
                Trae.Device d = Trae.newDevice();
                runOnUiThread(() -> {
                    if (g != generation || isFinishing() || isDestroyed()) return;
                    device = d; status.setText("请在页面中完成 Trae CN 授权。");
                    web.loadUrl(authorizationUrl(d));
                });
            } catch (Exception e) { fail(g,"设备身份生成失败：" + e.getMessage()); }
        },"trae-login-start"); t.setDaemon(true); t.start();
    }
    private static String authorizationUrl(Trae.Device d) {
        Uri.Builder b = Uri.parse(Trae.WEB_HOST + "/authorization").buildUpon();
        b.appendQueryParameter("login_version","1").appendQueryParameter("auth_from","solo")
         .appendQueryParameter("login_channel","native_ide").appendQueryParameter("plugin_version",Trae.PLUGIN_VERSION)
         .appendQueryParameter("auth_type","local").appendQueryParameter("client_id",Trae.CLIENT_ID)
         .appendQueryParameter("redirect","0").appendQueryParameter("login_trace_id",UUID.randomUUID().toString())
         .appendQueryParameter("auth_callback_url",CALLBACK)
         .appendQueryParameter("machine_id",d.machineId).appendQueryParameter("device_id",d.deviceId)
         .appendQueryParameter("x_device_id",d.deviceId).appendQueryParameter("x_machine_id",d.machineId)
         .appendQueryParameter("x_device_brand",Trae.BRAND).appendQueryParameter("x_device_type","windows")
         .appendQueryParameter("x_os_version","Windows 10 Pro").appendQueryParameter("x_env","")
         .appendQueryParameter("x_app_version",Trae.IDE_VERSION).appendQueryParameter("x_app_type","stable")
         .appendQueryParameter("hide_saas_login","false");
        return b.build().toString();
    }
    private boolean handle(Uri u) {
        if (busy) return true;
        if (u == null) return true;
        if ("http".equalsIgnoreCase(u.getScheme())) {
            if ("127.0.0.1".equals(u.getHost()) && u.getPort() == CALLBACK_PORT
                    && "/authorize".equals(u.getPath())) {
                busy = true; web.stopLoading(); complete(u); return true;
            }
            web.stopLoading(); status.setText("已阻止非回调的明文跳转，请重试。"); return true;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
            web.stopLoading(); status.setText("已阻止非安全授权跳转，请重试。"); return true;
        }
        return false;
    }
    private void complete(Uri u) {
        int g = generation;
        try {
            JSONObject jwt = object(u.getQueryParameter("userJwt"));
            JSONObject info = object(u.getQueryParameter("userInfo"));
            String token = first(jwt.opt("Token"), jwt.opt("token"));
            String refresh = first(jwt.opt("RefreshToken"), jwt.opt("refreshToken"), u.getQueryParameter("refreshToken"));
            String uid = first(info.opt("UserID"), info.opt("userId"), u.getQueryParameter("userId"));
            String name = first(info.opt("ScreenName"), info.opt("screenName"), uid);
            String host = first(u.getQueryParameter("host"), info.opt("Host"));
            String clientId = first(jwt.opt("ClientID"), jwt.opt("clientId"), u.getQueryParameter("clientId"), Trae.CLIENT_ID);
            long expires = when(first(jwt.opt("TokenExpireAt"), jwt.opt("tokenExpireAt")));
            if (token.isEmpty() && refresh.isEmpty()) throw new Exception("回调缺少登录令牌");
            if (device == null) throw new Exception("设备身份尚未准备好");
            Trae.Device d = device;
            status.setText("正在绑定设备并验证凭据…");
            Thread t = new Thread(() -> {
                try {
                    Trae.Auth a;
                    final String boundClientId = clientId;
                    if (!token.isEmpty()) {
                        a = new Trae.Auth();
                        a.token = token; a.refresh = refresh; a.uid = uid; a.user = name;
                        a.clientId = clientId; a.expires = expires;
                        a.deviceId = d.deviceId; a.machineId = d.machineId;
                        a.deviceKey = d.privatePem; a.publicPem = d.publicPem;
                        a = trae.bind(a);
                    } else {
                        a = trae.completeSignIn(host, refresh, uid, name, d);
                        a.clientId = clientId;
                    }
                    final Trae.Auth saved = a;
                    saved.clientId = boundClientId;
                    final String shown = saved.user == null || saved.user.isEmpty() ? saved.uid : saved.user;
                    final AccountStore.KV kv = Store.kv(this);
                    AccountStore.saveTrae(kv, "trae-cn", saved); // keep credentials even if verification fails
                    runOnUiThread(() -> {
                        if (g == generation && !isFinishing() && !isDestroyed())
                            status.setText("正在通过只读状态接口验证凭据…");
                    });
                    final SiteRegistry.Verify v = trae.verify(saved);
                    runOnUiThread(() -> {
                        if (g != generation || isFinishing() || isDestroyed()) return;
                        if (v.ok) {
                            AccountStore.add(kv, "trae-cn", shown); // only a verified account joins the list
                            setResult(RESULT_OK,new Intent().putExtra("ok",true));
                            Toast.makeText(this,"Trae CN 已添加",Toast.LENGTH_LONG).show(); finish();
                        } else {
                            AccountStore.markInvalid(kv, "trae-cn", v.message);
                            busy = false;
                            String reason = v.invalid ? "凭据无效，请重新登录" : "网络异常，无法验证凭据；请检查网络后重试";
                            status.setText(reason + "。凭据已保留，但此账号暂不参与签到。\n" + v.message);
                            Toast.makeText(this,reason,Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) { fail(g,"Trae CN 登录失败：" + e.getMessage()); }
            },"trae-login-finish"); t.setDaemon(true); t.start();
        } catch (Exception e) { busy = false; fail(g,"回调解析失败：" + e.getMessage()); }
    }
    private void fail(int g, String msg) {
        runOnUiThread(() -> {
            if (g != generation || isFinishing() || isDestroyed()) return;
            busy = false; status.setText(msg); Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        });
    }
    private static JSONObject object(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return new JSONObject();
        if (!s.startsWith("{")) s = Uri.decode(s);
        return new JSONObject(s);
    }
    private static String first(Object... v) {
        for (Object x : v) {
            if (x == null || x == JSONObject.NULL) continue;
            String s = x.toString();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return "";
    }
    private static long when(Object v) {
        if (v == null || v == JSONObject.NULL) return 0;
        try {
            long n = (long) Double.parseDouble(v.toString());
            return n < 100_000_000_000L ? n * 1000 : n;
        } catch (Exception e) { return 0; }
    }
    @Override protected void onDestroy() {
        generation++;
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}

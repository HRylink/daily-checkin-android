package com.velpro.checkin;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.*;

/** Interactive GitHub login stays in WebView. Only code+state go to the isolated API client. */
public class OAuthActivity extends Activity {
    private WebView web;
    private boolean busy;
    private String expectedState = "";
    private final Net net = new Net();
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (CheckinService.isRunning()) { done(false, false, "后台任务正在执行，请等待结束后再授权"); return; }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        cm.setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return req.isForMainFrame() && handle(req.getUrl());
            }
            @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handle(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { handle(Uri.parse(url)); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !busy) done(false, false, "GitHub 页面加载失败，请检查手机代理/网络后重试");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError err) {
                handler.cancel();
                done(false, false, "网站证书验证失败，已停止登录");
            }
        });
        setContentView(web);
        Store.put(this,"github_ua",s.getUserAgentString());
        android.widget.Toast.makeText(this,"正在退出旧会话并准备GitHub授权…",android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                net.restoreAgentCookie(Store.get(this,"ar_cookie"));
                String state = net.beginAgentRouter();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    expectedState = state;
                    // setCookie is asynchronous: finish every deletion before loading authorize URL.
                    clearRouterCookie(cm,0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> done(false,false,"授权准备失败：" + net.failureMessage(e)));
            }
        },"checkin-interactive-auth").start();
    }
    private void clearRouterCookie(CookieManager cm, int index) {
        String[] urls = {"https://ps.air-outer.com", "https://ps.air-outer.com", "https://agentrouter.org", "https://agentrouter.org"};
        String[] domains = {"", "; Domain=ps.air-outer.com", "", "; Domain=agentrouter.org"};
        if (isFinishing() || isDestroyed()) return;
        if (index == urls.length) { cm.flush(); web.loadUrl(Net.authorizeUrl(expectedState)); return; }
        cm.setCookie(urls[index], "session=; Max-Age=0; Path=/; Secure" + domains[index],
                ignored -> clearRouterCookie(cm, index + 1));
    }
    private boolean handle(Uri u) {
        if (busy) return true;
        if (u == null) return true;
        if (Net.isCallback(u.toString())) {
            String code = u.getQueryParameter("code"), state = u.getQueryParameter("state");
            if (code == null || code.isEmpty() || !expectedState.equals(state)) {
                done(false, false, "授权取消或 OAuth state 不匹配，请重新登录"); return true;
            }
            busy = true;
            web.stopLoading();
            new Thread(() -> {
                Net.R r = net.finishAgentRouter(code, state);
                AccountStore.recordResult(Store.kv(getApplicationContext()),"agentrouter",r,System.currentTimeMillis());
                runOnUiThread(() -> done(r.ok, r.loggedIn, r.msg));
            }).start();
            return true;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || !"github.com".equalsIgnoreCase(u.getHost())
                || u.getUserInfo() != null || !(u.getPort() == -1 || u.getPort() == 443)) {
            web.stopLoading();
            done(false, false, "已阻止非 GitHub 的登录跳转，请重试");
            return true;
        }
        return false;
    }
    private void done(boolean ok, boolean loggedIn, String msg) {
        if (isFinishing() || isDestroyed()) return;
        busy = true;
        CookieManager.getInstance().flush();
        boolean recorded = Store.add(this,"AgentRouter",ok,msg);
        if (loggedIn) Store.put(this,"ar_auth","0");
        if (!Store.put(this,"ar_cookie",net.agentCookie())) msg += "（登录会话保存失败）";
        Store.put(this,"status",(loggedIn ? "GitHub授权完成：" : "GitHub授权未完成：") + msg
                + (loggedIn ? "\n已保留登录态，之后点一键即可后台签到。" : "")
                + (recorded ? "" : "\n领取记录保存失败，请检查存储空间。"));
        setResult(loggedIn ? RESULT_OK : RESULT_CANCELED,
                new Intent().putExtra("ok", ok).putExtra("loggedIn", loggedIn).putExtra("msg", msg));
        finish();
    }
    @Override public void onBackPressed() { done(false, false, "已取消 GitHub 登录"); }
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}

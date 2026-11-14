package com.velpro.checkin;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.webkit.CookieManager;
import java.net.CookieHandler;
import java.util.*;

/** User-triggered data sync. No timers, no Activity reference, no automatic UI launches. */
public class CheckinService extends Service {
    public static final String ACTION_RUN = "com.velpro.checkin.RUN";
    private static final String CHANNEL = "checkin";
    private static final int NOTICE = 25, RESULT_NOTICE = 26;
    private static final RunCoordinator JOB = new RunCoordinator();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder summary = new StringBuilder();
    private final List<String> githubUpdates = new ArrayList<>();
    private boolean failed25, failedAR, otherFailed, needsAuth;
    private volatile boolean destroyed;
    public static boolean isRunning() { return JOB.isRunning(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) {
            if (!JOB.isRunning()) stopSelf();
            return START_NOT_STICKY;
        }
        if (!JOB.acquire()) return START_NOT_STICKY;
        summary.setLength(0);
        githubUpdates.clear();
        failed25 = failedAR = otherFailed = needsAuth = false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "签到任务与结果", NotificationManager.IMPORTANCE_LOW));
        try {
            Notification n = notification("正在后台签到", "可以切换应用，任务继续执行", true);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTICE, n);
        } catch (RuntimeException e) {
            Store.put(this, "status", "后台任务启动失败，请重新打开应用重试");
            JOB.release(); stopSelf();
            return START_NOT_STICKY;
        }
        CookieHandler.setDefault(null);
        Store.put(this, "status", "正在后台签到，可以切换应用…");
        String raw = "";
        try { raw = CookieManager.getInstance().getCookie("https://github.com/login/oauth/authorize"); }
        catch (RuntimeException ignored) { }
        final String github = raw == null ? "" : raw;
        final String user = Store.get(this,"user"), password = Store.get(this,"pass"), ua = Store.get(this,"github_ua");
        final AccountStore.KV kv = Store.kv(this);
        final boolean githubReady = SettingsActivity.hasGitHub(this);
        new Thread(() -> {
            Net net = new Net();
            RunCoordinator.Listener sink = new RunCoordinator.Listener() {
                @Override public void status(String message) {
                    if (destroyed) return;
                    Store.put(CheckinService.this,"status", summary.toString() + message);
                    main.post(() -> { if (!destroyed) notifySafe(NOTICE, notification("正在后台签到",message,true)); });
                }
                @Override public void result(String site, Net.R r) {
                    if (destroyed) return;
                    summary.append(r.ok ? "✓ " : "✗ ").append(site).append("：").append(r.msg).append('\n');
                    AccountStore.recordResult(kv,r.siteId,r,System.currentTimeMillis());
                    if ("77625".equals(r.siteId)) failed25 = !r.ok;
                    else if ("agentrouter".equals(r.siteId)) failedAR = !r.ok;
                    else otherFailed |= !r.ok;
                    if (!Store.add(CheckinService.this,site,r.ok,r.msg)) summary.append("⚠ 领取记录保存失败，请检查存储空间\n");
                    if (r.needsAuth) {
                        needsAuth = true;
                        SiteRegistry.Site s=SiteRegistry.byId(r.siteId);
                        if (s != null && ("workbuddy".equals(s.kind) || "trae".equals(s.kind)))
                            AccountStore.markInvalid(kv,s.id,r.msg);
                        summary.append("⚠ 请到设置页重新登录 ").append(site).append('\n');
                    }
                    if ("agentrouter".equals(r.siteId)) {
                        needsAuth |= r.needsAuth;
                        if (r.needsAuth || r.loggedIn) Store.put(CheckinService.this,"ar_auth",r.needsAuth ? "1" : "0");
                        githubUpdates.addAll(r.githubUpdates);
                        if (!Store.put(CheckinService.this,"ar_cookie",net.agentCookie())) summary.append("⚠ 登录会话保存失败，下次会建立新会话\n");
                    }
                }
                @Override public void finished() { main.post(() -> finishCookies(0)); }
            };
            try { net.restoreAgentCookie(Store.get(this,"ar_cookie")); }
            catch (Exception e) {
                summary.append("⚠ 旧会话无法恢复，本次使用新会话\n");
            }
            net.setProgressListener(sink::status);
            synchronized (RunCoordinator.AUTH_LOCK) {
                // A balance refresh may have rotated tokens while this worker was waiting.
                // Load the persisted credentials only after the lock, not on the main thread.
                List<AccountStore.Account> loaded = AccountStore.load(kv,githubReady);
                List<AccountStore.Account> active = new ArrayList<>();
                for (AccountStore.Account a : loaded) {
                    // Disabled switches skip; invalid accounts still report why re-login is needed.
                    if (a.enabled || a.invalid) active.add(a);
                }
                RunCoordinator.Plan plan = RunCoordinator.Plan.from(active);
                JOB.execute(net,user,password,github,ua,plan,sink);
            }
        },"checkin-background").start();
        return START_NOT_STICKY; // system must not silently replay a interrupted login/grant
    }

    /** Preserve rotating GitHub cookies; callbacks finish before flush and service shutdown. */
    private void finishCookies(int index) {
        if (destroyed) { JOB.release(); return; }
        if (index < githubUpdates.size()) {
            try {
                CookieManager.getInstance().setCookie("https://github.com",githubUpdates.get(index),
                        accepted -> finishCookies(index + 1));
            } catch (RuntimeException e) {
                summary.append("⚠ GitHub 登录状态更新失败，必要时请重新授权\n");
                complete();
            }
            return;
        }
        try { CookieManager.getInstance().flush(); }
        catch (RuntimeException e) { summary.append("⚠ GitHub 登录状态持久化失败\n"); }
        complete();
    }
    private void complete() {
        String text = summary.length() == 0 ? "任务未完成，请查看记录后重试" : summary.toString().trim();
        if (needsAuth) text += "\n请到设置页重新登录失效账号。";
        Store.put(this,"status",text);
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifySafe(RESULT_NOTICE,notification(needsAuth ? "需要重新登录" : (failed25 || failedAR || otherFailed) ? "签到结束，请查看结果" : "签到完成",text,false));
        JOB.release();
        stopSelf();
    }
    private Notification notification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true).build();
    }
    private void notifySafe(int id, Notification n) {
        try { getSystemService(NotificationManager.class).notify(id,n); }
        catch (SecurityException ignored) { /* Android13 denied: results remain visible in the app. */ }
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (JOB.isRunning()) Store.put(this,"status","后台任务被系统中断；已写入的记录保留，请手动重试。");
        super.onDestroy();
    }
}

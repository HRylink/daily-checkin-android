package com.velpro.checkin;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Service-independent job logic tested with real network fixtures. No Activity is required. */
public class RunTest {
    static void check(boolean b,String m) { if (!b) throw new AssertionError(m); }
    static Object runner() {
        try { return Class.forName("com.velpro.checkin.RunCoordinator").getConstructor().newInstance(); }
        catch (Exception e) { throw new AssertionError("missing background RunCoordinator", e); }
    }
    static boolean call(Object r, String name) throws Exception { return (Boolean)r.getClass().getMethod(name).invoke(r); }
    static void execute(Object runner, Net client, Object listener) throws Exception {
        Class<?> lt = Class.forName("com.velpro.checkin.RunCoordinator$Listener");
        runner.getClass().getMethod("execute",Net.class,String.class,String.class,String.class,String.class,lt)
                .invoke(runner,client,"user","password","user_session=mobile","mobile-UA",listener);
    }
    static Object listener(Object runner, List<Net.R> records, CountDownLatch entered, CountDownLatch resume) throws Exception {
        Class<?> lt = Class.forName("com.velpro.checkin.RunCoordinator$Listener");
        return Proxy.newProxyInstance(lt.getClassLoader(),new Class<?>[]{lt}, (proxy, method, args) -> {
            if (method.getName().equals("status") && entered != null && entered.getCount() > 0) {
                entered.countDown();
                check(resume.await(5,TimeUnit.SECONDS), "worker never resumed");
            }
            if (method.getName().equals("result")) records.add((Net.R)args[1]);
            if (method.getName().equals("finished")) runner.getClass().getMethod("release").invoke(runner);
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        Object job = runner();
        try (NetTest.Fixture api = new NetTest.Fixture(); OAuthTest.GitHub gh = new OAuthTest.GitHub()) {
            check(call(job,"acquire"),"cannot start manual job");
            List<Net.R> records = new CopyOnWriteArrayList<>();
            CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
            Object sink = listener(job, records, entered, resume);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { execute(job,OAuthTest.create(api.base,gh.base),sink); } catch (Throwable e) { failure.set(e); } });
            worker.start(); check(entered.await(5,TimeUnit.SECONDS),"background job never started");
            check(call(job,"isRunning") && !call(job,"acquire"),"duplicate job accepted while UI could be away");
            // UI lifetime has no role; allow background thread to finish without an Activity callback.
            resume.countDown(); worker.join(10000);
            check(!worker.isAlive() && failure.get() == null,"background task stalled: " + failure.get());
            check(records.size()==2 && records.get(1).ok,"job did not save both sites independently");
            check(!call(job,"isRunning"),"busy flag not released");
            api.checked = false;
            records.clear(); check(call(job,"acquire"),"cannot start next round");
            int before = api.logouts;
            execute(job,OAuthTest.create(api.base,gh.base),listener(job,records,null,null));
            check(api.logouts==before+2 && records.size()==3,"unconfirmed grant must do exactly one fresh logout/login retry");
            check(!records.get(1).ok && !records.get(2).ok,"unconfirmed retry misreported grant");
            gh.mode="expired"; records.clear(); check(call(job,"acquire"),"cannot start expired-session round");
            before=api.logouts;
            execute(job,OAuthTest.create(api.base,gh.base),listener(job,records,null,null));
            check(api.logouts==before+1 && records.size()==2 && records.get(1).needsAuth,"auth challenge retried without user or record lost");
            gh.mode="ok"; api.checked=true; records.clear(); check(call(job,"acquire"),"cannot start failed-77625 round");
            Class<?> lt = Class.forName("com.velpro.checkin.RunCoordinator$Listener");
            job.getClass().getMethod("execute",Net.class,String.class,String.class,String.class,String.class,lt)
                    .invoke(job,OAuthTest.create(api.base,gh.base),"","","user_session=mobile","mobile-UA",listener(job,records,null,null));
            check(records.size()==2 && !records.get(0).ok && records.get(1).ok,"77625 missing credentials prevented AgentRouter");
            records.clear(); check(call(job,"acquire"),"cannot start unexpected-error round");
            Object failingSink = Proxy.newProxyInstance(lt.getClassLoader(),new Class<?>[]{lt},(p,m,a) -> {
                if (m.getName().equals("status") && a[0].toString().startsWith("AgentRouter")) throw new IllegalStateException("test sink failure");
                if (m.getName().equals("result")) records.add((Net.R)a[1]);
                if (m.getName().equals("finished")) job.getClass().getMethod("release").invoke(job);
                return null;
            });
            execute(job,OAuthTest.create(api.base,gh.base),failingSink);
            check(records.size()==2 && !records.get(1).ok && records.get(1).msg.contains("中断"),"unexpected error lost failed result / could report completion");
        }
        System.out.println("PASS RunTest: background independence, duplicate guard, bounded grant retry, auth challenge, failed-site isolation");
    }
}

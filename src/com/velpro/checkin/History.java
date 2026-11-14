package com.velpro.checkin;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Private JSONL: one entry per attempt, not a count of newly awarded daily grants. */
public class History {
    private final File file;
    public History(File file) { this.file = file; }
    public synchronized void add(String site, boolean ok, String msg) throws IOException {
        try {
            Date now = new Date();
            JSONObject o = new JSONObject();
            o.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now));
            o.put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(now));
            o.put("site", site); o.put("ok", ok); o.put("msg", msg);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(o.toString()); w.write('\n');
            }
        } catch (org.json.JSONException e) { throw new IOException("记录格式错误", e); }
    }
    /** Upgrade compatibility: only confirmed account credits, never the newly awarded amount. */
    public synchronized JSONObject latest77625Balance() throws IOException {
        JSONObject latest=new JSONObject();
        if (!file.exists()) return latest;
        java.util.regex.Pattern current=java.util.regex.Pattern.compile("当前\\s+([0-9]+(?:\\.[0-9]+)?)\\s*积分");
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))) {
            String line;
            while ((line=reader.readLine()) != null) try {
                JSONObject row=new JSONObject(line);
                if (!"77625".equals(row.optString("site")) || !row.optBoolean("ok")) continue;
                String value=row.isNull("credits") ? "" : row.opt("credits").toString();
                if (value.isEmpty()) {
                    java.util.regex.Matcher m=current.matcher(row.optString("msg"));
                    if (m.find()) value=m.group(1);
                }
                if (value.isEmpty()) continue;
                java.math.BigDecimal n=new java.math.BigDecimal(value);
                if (n.signum() < 0) continue;
                java.time.LocalDateTime time=java.time.LocalDateTime.parse(row.optString("date") + "T" + row.optString("time"));
                latest=new JSONObject(); latest.put("balance",n.stripTrailingZeros().toPlainString());
                latest.put("at",time.atZone(java.time.ZoneId.systemDefault()).toInstant().toString());
            } catch (Exception ignored) { /* incomplete or unconfirmed historical rows cannot replace a balance */ }
        }
        return latest;
    }
    public synchronized List<String> recent(int n) throws IOException {
        List<String> all = new ArrayList<>();
        if (!file.exists() || n <= 0) return all;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) try {
                JSONObject o = new JSONObject(line);
                all.add(o.optString("date") + " " + o.optString("time") + "  " + o.optString("site")
                        + "  " + (o.optBoolean("ok") ? "✓" : "✗") + "  " + o.optString("msg"));
            } catch (org.json.JSONException ignored) { /* interrupted write must not hide earlier records */ }
        }
        List<String> result = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && result.size() < n; i--) result.add(all.get(i));
        return result;
    }
}

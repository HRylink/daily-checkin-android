package com.velpro.checkin;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.File;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App-private history; remembered credentials encrypted with a non-exportable AndroidKeyStore key. */
public class Store {
    private static final String PREFS = "checkin", KEY = "checkin-password";
    private static final Set<String> SECRET_KEYS = new HashSet<>(Arrays.asList(
            "pass", "ar_cookie",
            "wb_access", "wb_refresh", "wb_expires", "wb_uid",
            "trae_token", "trae_refresh", "trae_device_id", "trae_machine_id",
            "trae_device_key", "trae_public_key", "trae_uid", "trae_expires",
            "workbuddy_cn_access", "workbuddy_cn_refresh", "workbuddy_cn_expires",
            "workbuddy_cn_uid"));
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(KEY)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey(KEY, null);
    }
    /** Any historical gcm:<iv>:<ct> value remains readable, regardless of its preference key. */
    public static String get(Context c, String name) {
        String value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, "");
        if (!value.startsWith("gcm:")) return value;
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !parts[0].equals("gcm")) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) { return ""; } // key removed: ask the user to sign in again
    }
    public static boolean put(Context c, String name, String value) {
        return store(c, name, value, SECRET_KEYS.contains(name) || AccountStore.isSecret(name));
    }
    /** Explicitly encrypt a credential under an arbitrary future key name. */
    public static boolean putSecret(Context c, String name, String value) { return store(c, name, value, true); }
    private static boolean store(Context c, String name, String value, boolean encrypt) {
        try {
            if (encrypt && !value.isEmpty()) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key());
                value = "gcm:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                        + Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
            }
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, value).commit();
        } catch (Exception e) { return false; }
    }
    /** Adapter for the pure-Java account inventory; every sensitive key is still encrypted here. */
    public static AccountStore.KV kv(final Context c) {
        return new AccountStore.KV() {
            @Override public String get(String key) { return Store.get(c, key); }
            @Override public boolean put(String key, String value) {
                return AccountStore.isSecret(key) ? Store.putSecret(c, key, value) : Store.put(c, key, value);
            }
        };
    }
    /** One-time best-effort cache fill; reads private history, never logs in to 77625. */
    public static void restoreBalance77625(Context c) {
        if (!get(c,"77625_balance").isEmpty()) return;
        try {
            org.json.JSONObject prior=history(c).latest77625Balance();
            if (!prior.optString("balance").isEmpty() && put(c,"77625_balance",prior.optString("balance")))
                put(c,"77625_balance_at",prior.optString("at"));
        } catch (Exception ignored) { }
    }
    private static History history(Context c) { return new History(new File(c.getFilesDir(), "history.jsonl")); }
    public static boolean add(Context c, String site, boolean ok, String msg) {
        try { history(c).add(site, ok, msg); return true; }
        catch (Exception e) { return false; }
    }
    public static String recentText(Context c, int n) {
        try {
            List<String> rows = history(c).recent(n);
            if (rows.isEmpty()) return "还没有领取记录。";
            return String.join("\n", rows);
        } catch (Exception e) { return "无法读取领取记录，请检查手机存储空间。"; }
    }
}

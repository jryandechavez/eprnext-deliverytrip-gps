package com.erpnext.gpstracker;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

final class Config {
    static final String PREFS = "gps_settings";
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String url(Context c) { return prefs(c).getString("url", "http://167.172.64.123/api/method/gps_tracker.api.location"); }
    static String key(Context c) { return prefs(c).getString("api_key", ""); }
    static String secret(Context c) { return prefs(c).getString("api_secret", ""); }
    static SharedPreferences authPrefs(Context c) {
        try {
            MasterKey key=new MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            return EncryptedSharedPreferences.create(c,"bluecore_secure_auth",key,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch(Exception e) { throw new IllegalStateException("Secure authentication storage unavailable",e); }
    }
    static String sessionId(Context c) { return authPrefs(c).getString("session_id", ""); }
    static String userEmail(Context c) { return authPrefs(c).getString("user_email", ""); }
    static String deviceId(Context c) { return prefs(c).getString("device_id", android.os.Build.MODEL); }
    static long intervalMs(Context c) { return Math.max(1, prefs(c).getInt("interval", 5)) * 60_000L; }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static String deliveryTrip(Context c) { return prefs(c).getString("delivery_trip", ""); }
    static String driverId(Context c) { return prefs(c).getString("driver_id", ""); }
    static String routePhase(Context c) { return prefs(c).getString("route_phase", "Delivery"); }
    static boolean tripStarted(Context c) { return prefs(c).getBoolean("trip_started", false); }
    static double warehouseLat(Context c) { return Double.longBitsToDouble(prefs(c).getLong("warehouse_lat",Double.doubleToLongBits(Double.NaN))); }
    static double warehouseLng(Context c) { return Double.longBitsToDouble(prefs(c).getLong("warehouse_lng",Double.doubleToLongBits(Double.NaN))); }
    static String status(Context c) { return prefs(c).getString("last_status", "Waiting for a GPS fix…"); }
    static void status(Context c, String value) {
        prefs(c).edit().putString("last_status", value).putLong("last_status_time", System.currentTimeMillis()).apply();
    }
}

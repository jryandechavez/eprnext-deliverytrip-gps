package com.erpnext.gpstracker;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    static final String PREFS = "gps_settings";
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String url(Context c) { return prefs(c).getString("url", "http://167.172.64.123/api/method/gps_tracker.api.location"); }
    static String key(Context c) { return prefs(c).getString("api_key", ""); }
    static String secret(Context c) { return prefs(c).getString("api_secret", ""); }
    static String deviceId(Context c) { return prefs(c).getString("device_id", android.os.Build.MODEL); }
    static long intervalMs(Context c) { return Math.max(1, prefs(c).getInt("interval", 5)) * 60_000L; }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
}

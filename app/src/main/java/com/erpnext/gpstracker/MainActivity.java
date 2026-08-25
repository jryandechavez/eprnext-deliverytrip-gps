package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {
    private EditText url, key, secret, deviceId, interval;
    private CheckBox startOnBoot;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main);
        url=findViewById(R.id.url); key=findViewById(R.id.apiKey); secret=findViewById(R.id.apiSecret);
        deviceId=findViewById(R.id.deviceId); interval=findViewById(R.id.interval);
        startOnBoot=findViewById(R.id.startOnBoot); status=findViewById(R.id.status);
        load();
        findViewById(R.id.start).setOnClickListener(v -> start(false));
        findViewById(R.id.sendNow).setOnClickListener(v -> start(true));
        findViewById(R.id.stop).setOnClickListener(v -> stop());
    }

    private void load() {
        SharedPreferences p=Config.prefs(this);
        url.setText(Config.url(this)); key.setText(Config.key(this)); secret.setText(Config.secret(this));
        deviceId.setText(p.getString("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        interval.setText(String.valueOf(p.getInt("interval",5))); startOnBoot.setChecked(p.getBoolean("boot",true));
        status.setText(Config.enabled(this) ? "Tracking is enabled. See the persistent notification for the latest result." : "Tracking is stopped.");
    }

    private boolean save() {
        String endpoint=url.getText().toString().trim();
        if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) { toast("Enter a full http:// or https:// URL"); return false; }
        int mins; try { mins=Integer.parseInt(interval.getText().toString()); } catch(Exception e) { mins=0; }
        if (mins < 1) { toast("Interval must be at least 1 minute"); return false; }
        Config.prefs(this).edit().putString("url",endpoint).putString("api_key",key.getText().toString().trim())
            .putString("api_secret",secret.getText().toString()).putString("device_id",deviceId.getText().toString().trim())
            .putInt("interval",mins).putBoolean("boot",startOnBoot.isChecked()).apply();
        return true;
    }

    private void start(boolean now) {
        if (!save()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, 10); return;
        }
        Config.prefs(this).edit().putBoolean("enabled",true).apply();
        Intent i=new Intent(this,LocationService.class).setAction(now ? LocationService.ACTION_NOW : LocationService.ACTION_START);
        if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i);
        status.setText("Tracking started. Waiting for a GPS fix…");
    }
    private void stop() { Config.prefs(this).edit().putBoolean("enabled",false).apply(); stopService(new Intent(this,LocationService.class)); status.setText("Tracking is stopped."); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) { super.onRequestPermissionsResult(r,p,g); if(r==10 && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) start(false); }
}

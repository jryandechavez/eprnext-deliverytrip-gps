package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private EditText url, key, secret, deviceId, interval;
    private CheckBox startOnBoot;
    private TextView status, setupStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main);
        url=findViewById(R.id.url); key=findViewById(R.id.apiKey); secret=findViewById(R.id.apiSecret);
        deviceId=findViewById(R.id.deviceId); interval=findViewById(R.id.interval);
        startOnBoot=findViewById(R.id.startOnBoot); status=findViewById(R.id.status); setupStatus=findViewById(R.id.setupStatus);
        showVersion();
        UploadScheduler.ensurePeriodic(this); UploadScheduler.whenOnline(this);
        load();
        findViewById(R.id.start).setOnClickListener(v -> start(false));
        findViewById(R.id.sendNow).setOnClickListener(v -> start(true));
        findViewById(R.id.stop).setOnClickListener(v -> stop());
        findViewById(R.id.completeSetup).setOnClickListener(v -> completeSetup());
    }

    private void load() {
        SharedPreferences p=Config.prefs(this);
        url.setText(Config.url(this)); key.setText(Config.key(this)); secret.setText(Config.secret(this));
        deviceId.setText(p.getString("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        interval.setText(String.valueOf(p.getInt("interval",5))); startOnBoot.setChecked(p.getBoolean("boot",true));
        refreshStatus();
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
        boolean wasEnabled=Config.enabled(this);
        if (!save()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, 10); return;
        }
        Config.prefs(this).edit().putBoolean("enabled",true).apply();
        Intent i=new Intent(this,LocationService.class).setAction(now ? LocationService.ACTION_NOW : LocationService.ACTION_START);
        if(now) Config.status(this,"Requesting current location…");
        else if(!wasEnabled) Config.status(this,"Waiting for a GPS fix…");
        if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i);
        refreshStatus();
    }
    private void stop() { Config.prefs(this).edit().putBoolean("enabled",false).apply(); Config.status(this,"Tracking is stopped."); stopService(new Intent(this,LocationService.class)); refreshStatus(); }
    private void refreshStatus() { status.setText(Config.enabled(this) ? Config.status(this) : "Tracking is stopped."); }
    @Override protected void onResume() { super.onResume(); Config.prefs(this).registerOnSharedPreferenceChangeListener(this); refreshStatus(); refreshSetup(); }
    @Override protected void onPause() { Config.prefs(this).unregisterOnSharedPreferenceChangeListener(this); super.onPause(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String keyName) { if("last_status".equals(keyName) || "enabled".equals(keyName)) runOnUiThread(this::refreshStatus); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private boolean granted(String permission) { return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED; }
    private boolean gpsEnabled() { LocationManager manager=(LocationManager)getSystemService(LOCATION_SERVICE); return manager!=null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER); }
    private boolean batteryUnrestricted() { PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE); return manager!=null && manager.isIgnoringBatteryOptimizations(getPackageName()); }
    private boolean backgroundGranted() { return Build.VERSION.SDK_INT<29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION); }
    private boolean notificationsGranted() { return Build.VERSION.SDK_INT<33 || granted(Manifest.permission.POST_NOTIFICATIONS); }
    private void refreshSetup() {
        boolean precise=granted(Manifest.permission.ACCESS_FINE_LOCATION), background=backgroundGranted(), notifications=notificationsGranted(), gps=gpsEnabled(), battery=batteryUnrestricted();
        setupStatus.setText((precise?"✓":"•")+" Precise location\n"+(background?"✓":"•")+" Background location (Allow all the time)\n"+(notifications?"✓":"•")+" Tracking notification\n"+(gps?"✓":"•")+" GPS enabled\n"+(battery?"✓":"•")+" Battery optimization disabled\n• OEM Autostart: verify in App settings");
        Button button=findViewById(R.id.completeSetup);
        button.setText(precise&&background&&notifications&&gps&&battery ? "Review Autostart Settings" : "Complete Required Setup");
    }
    private void completeSetup() {
        if(!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},20); return; }
        if(!notificationsGranted()) { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21); return; }
        if(!backgroundGranted()) {
            if(Build.VERSION.SDK_INT==29) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},22);
            else openAppSettings();
            toast("Choose Permissions → Location → Allow all the time"); return;
        }
        if(!gpsEnabled()) { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return; }
        if(!batteryUnrestricted()) {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:"+getPackageName()))); }
            catch(Exception e) { openAppSettings(); }
            return;
        }
        openAppSettings(); toast("Verify Autostart is enabled and Battery is set to No restrictions");
    }
    private void openAppSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); }
    private void showVersion() {
        try {
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            long build=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
            ((TextView)findViewById(R.id.versionInfo)).setText("Bluecore GPS  v"+info.versionName+"  •  Build "+build);
        } catch(Exception e) { ((TextView)findViewById(R.id.versionInfo)).setText("Bluecore GPS"); }
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g); refreshSetup();
        if(r==10 && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) start(false);
        else if((r==20||r==21||r==22) && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) completeSetup();
    }
}

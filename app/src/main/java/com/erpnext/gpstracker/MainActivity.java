package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private EditText url, key, secret, deviceId, interval, deliveryTrip;
    private Spinner routePhase;
    private Spinner deliveryStop;
    private final ArrayList<String> stopIds=new ArrayList<>();
    private CheckBox startOnBoot;
    private TextView status, setupStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main);
        url=findViewById(R.id.url); key=findViewById(R.id.apiKey); secret=findViewById(R.id.apiSecret);
        deviceId=findViewById(R.id.deviceId); interval=findViewById(R.id.interval);
        deliveryTrip=findViewById(R.id.deliveryTrip); routePhase=findViewById(R.id.routePhase);
        deliveryStop=findViewById(R.id.deliveryStop);
        startOnBoot=findViewById(R.id.startOnBoot); status=findViewById(R.id.status); setupStatus=findViewById(R.id.setupStatus);
        showVersion();
        UploadScheduler.ensurePeriodic(this); UploadScheduler.whenOnline(this);
        load();
        findViewById(R.id.start).setOnClickListener(v -> start(false));
        findViewById(R.id.sendNow).setOnClickListener(v -> start(true));
        findViewById(R.id.stop).setOnClickListener(v -> stop());
        findViewById(R.id.completeSetup).setOnClickListener(v -> completeSetup());
        findViewById(R.id.openTripMap).setOnClickListener(v -> openTripMap());
        findViewById(R.id.loadTrip).setOnClickListener(v -> loadTrip());
        findViewById(R.id.tripStarted).setOnClickListener(v -> queueEvent("Trip Started",false));
        findViewById(R.id.deliveryStarted).setOnClickListener(v -> queueEvent("Delivery Started",true));
        findViewById(R.id.deliveryCompleted).setOnClickListener(v -> queueEvent("Delivery Completed",true));
        findViewById(R.id.tripCompleted).setOnClickListener(v -> queueEvent("Trip Completed",false));
        findViewById(R.id.returnStarted).setOnClickListener(v -> {routePhase.setSelection(1);save();queueEvent("Return Started",false);});
        findViewById(R.id.returnedWarehouse).setOnClickListener(v -> queueEvent("Returned to Warehouse",false));
    }

    private void load() {
        SharedPreferences p=Config.prefs(this);
        url.setText(Config.url(this)); key.setText(Config.key(this)); secret.setText(Config.secret(this));
        deviceId.setText(p.getString("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        interval.setText(String.valueOf(p.getInt("interval",5))); startOnBoot.setChecked(p.getBoolean("boot",true));
        deliveryTrip.setText(p.getString("delivery_trip",""));
        routePhase.setSelection("Return".equals(p.getString("route_phase","Delivery"))?1:0);
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
        String trip=deliveryTrip.getText().toString().trim().replace("BLUECORE-TRIP:","");
        Config.prefs(this).edit().putString("delivery_trip",trip).putString("route_phase",routePhase.getSelectedItem().toString()).apply();
        return true;
    }
    private void openTripMap() {
        if(!save())return;
        String trip=Config.deliveryTrip(this);
        if(trip.isEmpty()){toast("Enter or scan a Delivery Trip first");return;}
        String endpoint=Config.url(this); int marker=endpoint.indexOf("/api/method/");
        String base=marker>0?endpoint.substring(0,marker):endpoint;
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(base+"/app/delivery-trip-route?delivery_trip="+Uri.encode(trip))));
    }
    private String baseUrl(){String e=Config.url(this);int i=e.indexOf("/api/method/");return i>0?e.substring(0,i):e;}
    private void loadTrip(){
        if(!save()||Config.deliveryTrip(this).isEmpty()){toast("Enter or scan a Delivery Trip first");return;}
        new Thread(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(baseUrl()+"/api/method/gps_tracker.api.delivery_trip_route?delivery_trip="+URLEncoder.encode(Config.deliveryTrip(this),"UTF-8")).openConnection();c.setRequestProperty("Authorization","token "+Config.key(this)+":"+Config.secret(this));JSONObject root=new JSONObject(read(c.getInputStream())).getJSONObject("message");JSONArray stops=root.getJSONArray("stops");ArrayList<String> labels=new ArrayList<>(),ids=new ArrayList<>();for(int i=0;i<stops.length();i++){JSONObject s=stops.getJSONObject(i);ids.add(s.getString("name"));labels.add((i+1)+". "+s.optString("customer")+" · "+s.optString("delivery_note"));}runOnUiThread(()->{stopIds.clear();stopIds.addAll(ids);deliveryStop.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));toast(labels.size()+" delivery stops loaded");});}catch(Exception e){runOnUiThread(()->toast("Unable to load trip: "+e.getMessage()));}}).start();
    }
    private void queueEvent(String type,boolean needsStop){
        if(!save()||Config.deliveryTrip(this).isEmpty()){toast("Select a Delivery Trip first");return;}
        if(needsStop&&(deliveryStop.getSelectedItemPosition()<0||stopIds.isEmpty())){toast("Load and select a delivery stop first");return;}
        if(!granted(Manifest.permission.ACCESS_FINE_LOCATION)){toast("Precise location permission is required");return;}
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);Location l=a==null?b:(b==null||a.getTime()>b.getTime()?a:b);if(l==null){toast("Waiting for a GPS fix");return;}
        String stop=needsStop?stopIds.get(deliveryStop.getSelectedItemPosition()):"";
        String at=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).format(new Date());
        String payload="{\"event_id\":\""+UUID.randomUUID()+"\",\"delivery_trip\":"+json(Config.deliveryTrip(this))+",\"delivery_stop\":"+json(stop)+",\"device_id\":"+json(Config.deviceId(this))+",\"event_type\":"+json(type)+",\"latitude\":"+l.getLatitude()+",\"longitude\":"+l.getLongitude()+",\"accuracy\":"+l.getAccuracy()+",\"recorded_at\":"+json(at)+"}";
        new LocationQueue(this).enqueue(payload,System.currentTimeMillis(),"gps_tracker.api.delivery_event");
        Intent i=new Intent(this,LocationService.class).setAction(LocationService.ACTION_NOW);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);toast(type+" saved; it will upload automatically");
    }
    private static String json(String s){return JSONObject.quote(s==null?"":s);}
    private static String read(InputStream in)throws IOException{BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String s;while((s=r.readLine())!=null)b.append(s);return b.toString();}

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

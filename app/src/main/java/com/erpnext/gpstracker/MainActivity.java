package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.webkit.WebView;
import android.webkit.WebSettings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private EditText url, loginEmail, loginPassword, deviceId, interval, deliveryTrip, driverId;
    private Spinner routePhase;
    private LinearLayout deliveryList;
    private TextView deliverySummary;
    private Button showAllButton;
    private WebView routeMapPreview;
    private final ArrayList<JSONObject> deliveryRows=new ArrayList<>();
    private boolean showAll=false;
    private CheckBox startOnBoot;
    private TextView status, setupStatus, loginStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_main);
        url=findViewById(R.id.url); loginEmail=findViewById(R.id.loginEmail); loginPassword=findViewById(R.id.loginPassword); loginStatus=findViewById(R.id.loginStatus);
        deviceId=findViewById(R.id.deviceId); interval=findViewById(R.id.interval);
        deliveryTrip=findViewById(R.id.deliveryTrip); routePhase=findViewById(R.id.routePhase);
        driverId=findViewById(R.id.driverId);
        deliveryList=findViewById(R.id.deliveryList); deliverySummary=findViewById(R.id.deliverySummary); showAllButton=findViewById(R.id.showAllDeliveries);
        routeMapPreview=findViewById(R.id.routeMapPreview);routeMapPreview.getSettings().setJavaScriptEnabled(true);routeMapPreview.getSettings().setDomStorageEnabled(true);routeMapPreview.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        startOnBoot=findViewById(R.id.startOnBoot); status=findViewById(R.id.status); setupStatus=findViewById(R.id.setupStatus);
        showVersion();
        UploadScheduler.ensurePeriodic(this); UploadScheduler.whenOnline(this);
        load();
        findViewById(R.id.completeSetup).setOnClickListener(v -> completeSetup());
        findViewById(R.id.openTripMap).setOnClickListener(v -> openTripMap());
        findViewById(R.id.refreshRouteMap).setOnClickListener(v -> loadTrip());
        findViewById(R.id.loadTrip).setOnClickListener(v -> loadTrip());
        findViewById(R.id.scanTrip).setOnClickListener(v -> scanTrip());
        findViewById(R.id.searchTrip).setOnClickListener(v -> searchTrips());
        showAllButton.setOnClickListener(v->{showAll=!showAll;showAllButton.setText(showAll?"Show Ongoing":"Show All");renderDeliveries();});
        findViewById(R.id.signIn).setOnClickListener(v -> signIn());
        findViewById(R.id.signOut).setOnClickListener(v -> signOut());
        findViewById(R.id.tripStarted).setOnClickListener(v -> confirmStart());
        findViewById(R.id.tripCompleted).setOnClickListener(v -> confirmEnd());
        findViewById(R.id.returnStarted).setOnClickListener(v -> {routePhase.setSelection(1);save();queueEvent("Return Started","",null);});
        findViewById(R.id.returnedWarehouse).setOnClickListener(v -> queueEvent("Returned to Warehouse","",null));
        if(Config.enabled(this)&&granted(Manifest.permission.ACCESS_FINE_LOCATION))startTrackingService();
    }

    private void load() {
        SharedPreferences p=Config.prefs(this);
        url.setText(Config.url(this)); loginEmail.setText(Config.userEmail(this)); updateLoginStatus();
        deviceId.setText(p.getString("device_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        interval.setText(String.valueOf(p.getInt("interval",5))); startOnBoot.setChecked(p.getBoolean("boot",true));
        deliveryTrip.setText(p.getString("delivery_trip",""));
        driverId.setText(p.getString("driver_id",""));
        routePhase.setSelection("Return".equals(p.getString("route_phase","Delivery"))?1:0);
        refreshStatus();
    }

    private boolean save() {
        String endpoint=url.getText().toString().trim();
        if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) { toast("Enter a full http:// or https:// URL"); return false; }
        int mins; try { mins=Integer.parseInt(interval.getText().toString()); } catch(Exception e) { mins=0; }
        if (mins < 1) { toast("Interval must be at least 1 minute"); return false; }
        Config.prefs(this).edit().putString("url",endpoint).putString("device_id",deviceId.getText().toString().trim())
            .putInt("interval",mins).putBoolean("boot",startOnBoot.isChecked()).apply();
        String trip=deliveryTrip.getText().toString().trim().replace("BLUECORE-TRIP:","");
        String previous=Config.deliveryTrip(this);SharedPreferences.Editor tripEdit=Config.prefs(this).edit().putString("delivery_trip",trip).putString("route_phase",routePhase.getSelectedItem().toString());if(!trip.equals(previous))tripEdit.putBoolean("trip_started",false);tripEdit.apply();
        Config.prefs(this).edit().putString("driver_id",driverId.getText().toString().trim()).apply();
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
    private void updateLoginStatus(){String email=Config.userEmail(this);loginStatus.setText(Config.sessionId(this).isEmpty()?"Not signed in":"Signed in as "+email);}
    private void signIn(){
        if(!save())return;String email=loginEmail.getText().toString().trim(),password=loginPassword.getText().toString();
        if(email.isEmpty()||password.isEmpty()){toast("Enter your ERPNext email and password");return;}
        findViewById(R.id.signIn).setEnabled(false);loginStatus.setText("Signing in securely…");
        new Thread(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(baseUrl()+"/api/method/login").openConnection();c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");String form="usr="+URLEncoder.encode(email,"UTF-8")+"&pwd="+URLEncoder.encode(password,"UTF-8");try(OutputStream out=c.getOutputStream()){out.write(form.getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();String sid="";Map<String,List<String>> headers=c.getHeaderFields();for(Map.Entry<String,List<String>> h:headers.entrySet())if(h.getKey()!=null&&"Set-Cookie".equalsIgnoreCase(h.getKey()))for(String cookie:h.getValue())if(cookie.startsWith("sid=")){int end=cookie.indexOf(';');sid=cookie.substring(4,end>4?end:cookie.length());break;}if(code<200||code>=300||sid.isEmpty())throw new IOException("Login failed (HTTP "+code+")");Config.authPrefs(this).edit().putString("session_id",sid).putString("user_email",email).apply();Config.prefs(this).edit().remove("api_key").remove("api_secret").putBoolean("enabled",true).apply();runOnUiThread(()->{loginPassword.setText("");updateLoginStatus();if(granted(Manifest.permission.ACCESS_FINE_LOCATION))startTrackingService();else completeSetup();toast("Signed in. GPS tracking is automatic.");});}catch(Exception e){runOnUiThread(()->{loginPassword.setText("");loginStatus.setText("Sign-in failed");toast("Unable to sign in: check email, password, and server");});}finally{if(c!=null)c.disconnect();runOnUiThread(()->findViewById(R.id.signIn).setEnabled(true));}}).start();
    }
    private void signOut(){Config.authPrefs(this).edit().clear().apply();loginPassword.setText("");loginEmail.setText("");updateLoginStatus();toast("Signed out");}
    private void scanTrip(){new IntentIntegrator(this).setPrompt("Scan the Delivery Trip QR code").setBeepEnabled(false).setOrientationLocked(false).initiateScan();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        IntentResult result=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);
        if(result!=null){if(result.getContents()!=null){String value=result.getContents().trim().replace("BLUECORE-TRIP:","");deliveryTrip.setText(value);save();loadTrip();}return;}
        super.onActivityResult(requestCode,resultCode,data);
    }
    private void searchTrips(){
        if(!save())return; String query=deliveryTrip.getText().toString().trim().replace("BLUECORE-TRIP:","");
        new Thread(()->{try{String u=baseUrl()+"/api/method/gps_tracker.api.available_delivery_trips?device_id="+URLEncoder.encode(Config.deviceId(this),"UTF-8")+"&driver="+URLEncoder.encode(Config.driverId(this),"UTF-8")+"&search="+URLEncoder.encode(query,"UTF-8");HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();ErpAuth.apply(this,c);JSONArray rows=new JSONObject(read(c.getInputStream())).getJSONArray("message");ArrayList<String> names=new ArrayList<>(),labels=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String name=row.getString("name");names.add(name);labels.add(name+"\n"+row.optString("driver_name")+" · "+row.optString("status"));}runOnUiThread(()->showTripResults(names,labels));}catch(Exception e){runOnUiThread(()->toast("Unable to search trips: "+e.getMessage()));}}).start();
    }
    private void showTripResults(ArrayList<String> names,ArrayList<String> labels){
        if(names.isEmpty()){toast("No available Delivery Trips found");return;}
        new AlertDialog.Builder(this).setTitle("Select Delivery Trip").setItems(labels.toArray(new String[0]),(dialog,which)->{deliveryTrip.setText(names.get(which));save();loadTrip();}).setNegativeButton("Cancel",null).show();
    }
    private void loadTrip(){
        if(!save()||Config.deliveryTrip(this).isEmpty()){toast("Enter or scan a Delivery Trip first");return;}
        new Thread(()->{try{String u=baseUrl()+"/api/method/gps_tracker.api.delivery_trip_route?delivery_trip="+URLEncoder.encode(Config.deliveryTrip(this),"UTF-8")+"&driver="+URLEncoder.encode(Config.driverId(this),"UTF-8")+"&device_id="+URLEncoder.encode(Config.deviceId(this),"UTF-8");HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();ErpAuth.apply(this,c);JSONObject root=new JSONObject(read(c.getInputStream())).getJSONObject("message");JSONArray stops=root.getJSONArray("stops");ArrayList<JSONObject> rows=new ArrayList<>();for(int i=0;i<stops.length();i++)rows.add(stops.getJSONObject(i));JSONObject warehouse=root.optJSONObject("warehouse");runOnUiThread(()->{deliveryRows.clear();deliveryRows.addAll(rows);if(warehouse!=null&&!warehouse.isNull("latitude")&&!warehouse.isNull("longitude"))Config.prefs(this).edit().putLong("warehouse_lat",Double.doubleToRawLongBits(warehouse.optDouble("latitude"))).putLong("warehouse_lng",Double.doubleToRawLongBits(warehouse.optDouble("longitude"))).apply();renderDeliveries();renderInlineMap(root);toast(rows.size()+" deliveries loaded");});}catch(Exception e){runOnUiThread(()->toast("Unable to load trip: "+e.getMessage()));}}).start();
    }
    private void renderInlineMap(JSONObject data){
        try {
            JSONArray source=data.optJSONArray("stops"), filtered=new JSONArray();
            if(source!=null)for(int i=0;i<source.length();i++){JSONObject p=source.getJSONObject(i);if(Math.abs(p.optDouble("latitude"))>.0001||Math.abs(p.optDouble("longitude"))>.0001)filtered.put(p);}
            data.put("stops",filtered);
            JSONObject warehouse=data.optJSONObject("warehouse");if(warehouse!=null&&Math.abs(warehouse.optDouble("latitude"))<=.0001&&Math.abs(warehouse.optDouble("longitude"))<=.0001)data.put("warehouse",JSONObject.NULL);
        } catch(Exception ignored) {}
        String payload=data.toString().replace("</","<\\/");
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'><link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'><style>html,body,#map{height:100%;margin:0}.leaflet-control-attribution{font-size:8px}.pin{background:#2563eb;color:white;border:2px solid white;border-radius:50%;width:24px;height:24px;line-height:20px;text-align:center;font-weight:bold;box-shadow:0 1px 4px #555}</style></head><body><div id='map'></div><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><script>const d="+payload+";const m=L.map('map',{zoomControl:true});L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);const valid=p=>p&&isFinite(+p.latitude)&&isFinite(+p.longitude);const planned=[d.warehouse,...d.stops].filter(valid),delivery=d.locations.filter(p=>p.route_phase!=='Return'&&valid(p)),ret=d.locations.filter(p=>p.route_phase==='Return'&&valid(p)),all=[];if(valid(d.warehouse)){const p=[+d.warehouse.latitude,+d.warehouse.longitude];all.push(p);L.marker(p).addTo(m).bindPopup('Warehouse')}d.stops.filter(valid).forEach((s,i)=>{const p=[+s.latitude,+s.longitude];all.push(p);L.marker(p,{icon:L.divIcon({className:'',html:'<div class=pin>'+(i+1)+'</div>',iconSize:[24,24],iconAnchor:[12,12]})}).addTo(m).bindPopup((s.customer||'')+'<br>'+(s.delivery_note||''))});async function drawPlan(){if(planned.length<2)return;let line=planned.map(p=>[p.latitude,p.longitude]);try{const coords=planned.map(p=>p.longitude+','+p.latitude).join(';');const r=await fetch('https://router.project-osrm.org/route/v1/driving/'+coords+'?overview=full&geometries=geojson');const j=await r.json();line=j.routes[0].geometry.coordinates.map(c=>[c[1],c[0]])}catch(e){}L.polyline(line,{color:'#2563eb',weight:4,dashArray:'7 7'}).addTo(m)}drawPlan();if(delivery.length){const p=delivery.map(x=>[x.latitude,x.longitude]);all.push(...p);L.polyline(p,{color:'#16a34a',weight:5}).addTo(m);L.circleMarker(p[p.length-1],{radius:7,color:'#16a34a',fillOpacity:1}).addTo(m).bindPopup('Current position')}if(ret.length){const p=ret.map(x=>[x.latitude,x.longitude]);all.push(...p);L.polyline(p,{color:'#7c3aed',weight:5}).addTo(m)}if(all.length)m.fitBounds(all,{padding:[22,22],maxZoom:16});else m.setView([14.6,121],6);</script></body></html>";
        routeMapPreview.loadDataWithBaseURL("https://bluecore.local/",html,"text/html","UTF-8",null);
    }
    private void renderDeliveries(){deliveryList.removeAllViews();int ongoing=0,completed=0;for(JSONObject row:deliveryRows){String state=row.optString("delivery_status","Pending");boolean done="Completed".equals(state)||"Skipped".equals(state);if(done)completed++;else ongoing++;if(!showAll&&done)continue;TextView card=new TextView(this);card.setText(row.optString("customer")+"\n"+row.optString("customer_address")+"\n"+row.optString("delivery_note")+"  •  "+state);card.setTextColor(getColor(R.color.bc_text));card.setTextSize(15);card.setPadding(18,16,18,16);card.setBackgroundResource(R.drawable.bg_status);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,10);card.setLayoutParams(lp);if(!done)card.setOnClickListener(v->confirmDelivery(row));deliveryList.addView(card);}deliverySummary.setText(ongoing+" ongoing · "+completed+" completed");}
    private void confirmDelivery(JSONObject row){new AlertDialog.Builder(this).setTitle("Complete Delivery?").setMessage(row.optString("customer")+"\n"+row.optString("customer_address")+"\nDelivery Note: "+row.optString("delivery_note")+"\n\nConfirm the items were delivered successfully.").setNegativeButton("Cancel",null).setPositiveButton("Confirm Delivery",(d,w)->queueEvent("Delivery Completed",row.optString("name"),row)).show();}
    private void confirmStart(){new AlertDialog.Builder(this).setTitle("Start Deliveries?").setMessage("Begin "+Config.deliveryTrip(this)+" with "+deliveryRows.size()+" delivery stops?").setNegativeButton("Cancel",null).setPositiveButton("Start",(d,w)->{Config.prefs(this).edit().putBoolean("trip_started",true).apply();start(false);queueEvent("Trip Started","",null);}).show();}
    private void confirmEnd(){new AlertDialog.Builder(this).setTitle("End Deliveries?").setMessage("End the delivery portion of this trip? GPS tracking will continue for the return to warehouse.").setNegativeButton("Cancel",null).setPositiveButton("End Deliveries",(d,w)->{queueEvent("Trip Completed","",null);Config.prefs(this).edit().putString("route_phase","Return").apply();routePhase.setSelection(1);}).show();}
    private void queueEvent(String type,String stop,JSONObject row){
        if(!save()||Config.deliveryTrip(this).isEmpty()){toast("Select a Delivery Trip first");return;}
        if(!granted(Manifest.permission.ACCESS_FINE_LOCATION)){toast("Precise location permission is required");return;}
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);Location l=a==null?b:(b==null||a.getTime()>b.getTime()?a:b);if(l==null){toast("Waiting for a GPS fix");return;}
        String at=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).format(new Date());
        String payload="{\"event_id\":\""+UUID.randomUUID()+"\",\"delivery_trip\":"+json(Config.deliveryTrip(this))+",\"delivery_stop\":"+json(stop)+",\"driver\":"+json(Config.driverId(this))+",\"device_id\":"+json(Config.deviceId(this))+",\"event_type\":"+json(type)+",\"latitude\":"+l.getLatitude()+",\"longitude\":"+l.getLongitude()+",\"accuracy\":"+l.getAccuracy()+",\"recorded_at\":"+json(at)+"}";
        new LocationQueue(this).enqueue(payload,System.currentTimeMillis(),"gps_tracker.api.delivery_event");
        if(row!=null){try{row.put("delivery_status","Completed");}catch(Exception ignored){}renderDeliveries();}
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
    private void startTrackingService(){Config.prefs(this).edit().putBoolean("enabled",true).apply();Intent intent=new Intent(this,LocationService.class).setAction(LocationService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);refreshStatus();}
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

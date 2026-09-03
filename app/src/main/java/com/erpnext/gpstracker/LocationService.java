package com.erpnext.gpstracker;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class LocationService extends Service implements LocationListener {
    static final String ACTION_START="start", ACTION_NOW="now";
    private static final String CHANNEL="gps_tracking"; private static final int NOTIFY=71;
    private LocationManager manager; private LocationQueue queue; private long lastRecorded=0; private boolean sending=false; private int outsideWarehouseReadings=0;
    private final Handler retryHandler=new Handler(Looper.getMainLooper());
    private final Runnable retryTask=new Runnable(){@Override public void run(){checkMidnightStop();drainQueue();if(Config.enabled(LocationService.this))retryHandler.postDelayed(this,60_000L);}};

    @Override public void onCreate() { super.onCreate(); createChannel(); startForeground(NOTIFY,note("Waiting for location…")); manager=(LocationManager)getSystemService(LOCATION_SERVICE); queue=new LocationQueue(this); UploadScheduler.ensurePeriodic(this); UploadScheduler.whenOnline(this); retryHandler.post(retryTask); }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (!Config.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            long ms=Config.intervalMs(this);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,ms,0,this);
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,ms,0,this);
            Location best=bestLast(); if(best!=null && (ACTION_NOW.equals(intent==null?null:intent.getAction()) || System.currentTimeMillis()-lastRecorded>=ms)) record(best);
        } catch(SecurityException e) { update("Location permission is missing"); }
        drainQueue();
        return START_STICKY;
    }
    private Location bestLast() {
        try { Location a=manager.getLastKnownLocation(LocationManager.GPS_PROVIDER), b=manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); return a==null?b:(b==null||a.getTime()>b.getTime()?a:b); }
        catch(SecurityException e){ return null; }
    }
    @Override public void onLocationChanged(Location l) { if(System.currentTimeMillis()-lastRecorded>=Config.intervalMs(this)) record(l); }
    private synchronized void record(Location l) {
        checkMissedStart(l);
        lastRecorded=System.currentTimeMillis();
        String payload="{\"report_id\":"+q(UUID.randomUUID().toString())+",\"device_id\":"+q(Config.deviceId(this))+",\"delivery_trip\":"+q(Config.deliveryTrip(this))+",\"route_phase\":"+q(Config.routePhase(this))+",\"latitude\":"+l.getLatitude()+",\"longitude\":"+l.getLongitude()+",\"accuracy\":"+l.getAccuracy()+",\"altitude\":"+(l.hasAltitude()?l.getAltitude():"null")+",\"speed\":"+(l.hasSpeed()?l.getSpeed():"null")+",\"bearing\":"+(l.hasBearing()?l.getBearing():"null")+",\"recorded_at\":"+q(iso(l.getTime()))+"}";
        queue.enqueue(payload,l.getTime());
        UploadScheduler.whenOnline(this);
        update("Location saved locally. "+queue.count()+" queued for upload.\n"+coords(l));
        checkWarehouseReturn(l);
        drainQueue();
    }
    private void checkMissedStart(Location location){
        if(Config.tripStarted(this)||Config.deliveryTrip(this).isEmpty()||location.getAccuracy()>50)return;
        double lat=Config.warehouseLat(this),lng=Config.warehouseLng(this);if(Double.isNaN(lat)||Double.isNaN(lng))return;
        float[] result=new float[1];Location.distanceBetween(lat,lng,location.getLatitude(),location.getLongitude(),result);
        outsideWarehouseReadings=result[0]>=200?outsideWarehouseReadings+1:0;
        if(outsideWarehouseReadings==3)update("Action required: vehicle is over 200 m from the warehouse. Open Tic & Terry Delivery and start deliveries.");
    }
    private void checkWarehouseReturn(Location location){
        if(!"returning".equals(Config.prefs(this).getString("trip_workflow",""))||location.getAccuracy()>75)return;
        double lat=Config.warehouseLat(this),lng=Config.warehouseLng(this);if(Double.isNaN(lat)||Double.isNaN(lng))return;
        float[] result=new float[1];Location.distanceBetween(lat,lng,location.getLatitude(),location.getLongitude(),result);
        if(result[0]<=150){queueReturnComplete(location);stopAutomaticTracking("Returned to warehouse (within 150 m). GPS tracking completed.","returned");}
    }
    private void checkMidnightStop(){
        if(!"returning".equals(Config.prefs(this).getString("trip_workflow","")))return;
        String started=Config.prefs(this).getString("return_tracking_date",""),today=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        if(!started.isEmpty()&&!today.equals(started))stopAutomaticTracking("12:00 AM reached. GPS tracking completed.","tracking_ended");
    }
    private void queueReturnComplete(Location l){
        String payload="{\"event_id\":"+q(UUID.randomUUID().toString())+",\"delivery_trip\":"+q(Config.deliveryTrip(this))+",\"delivery_stop\":\"\",\"driver\":"+q(Config.driverId(this))+",\"device_id\":"+q(Config.deviceId(this))+",\"event_type\":\"Returned to Warehouse\",\"latitude\":"+l.getLatitude()+",\"longitude\":"+l.getLongitude()+",\"accuracy\":"+l.getAccuracy()+",\"recorded_at\":"+q(iso(System.currentTimeMillis()))+"}";
        queue.enqueue(payload,System.currentTimeMillis(),"gps_tracker.api.delivery_event");UploadScheduler.whenOnline(this);
    }
    private void stopAutomaticTracking(String message,String workflow){Config.prefs(this).edit().putBoolean("enabled",false).putString("trip_workflow",workflow).apply();update(message);stopForeground(true);stopSelf();}
    private synchronized void drainQueue() {
        if(sending||queue==null)return;
        LocationQueue.Item item=queue.oldest();
        if(item==null)return;
        sending=true;
        new Thread(() -> {
            HttpURLConnection c=null;
            boolean success=false;
            try {
                String configured=Config.url(this); int marker=configured.indexOf("/api/method/"); String base=marker>0?configured.substring(0,marker):configured;
                c=(HttpURLConnection)new URL(base+"/api/method/"+item.method).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json");
                ErpAuth.apply(this,c);
                try(OutputStream os=c.getOutputStream()){os.write(item.payload.getBytes(StandardCharsets.UTF_8));}
                int code=c.getResponseCode(); String response=read(code>=400?c.getErrorStream():c.getInputStream());
                if(code>=200&&code<300) {
                    queue.remove(item.id); success=true;
                    update("Sent successfully (HTTP "+code+"). "+queue.count()+" queued.");
                } else {
                    String error="Server error HTTP "+code+"\n"+shorten(response); queue.failed(item.id,error); update(error+"\n"+queue.count()+" saved locally.");
                }
            } catch(Exception e){ String error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();queue.failed(item.id,error);update("Offline: "+queue.count()+" locations saved locally.\n"+error); }
            finally { if(c!=null)c.disconnect(); synchronized(LocationService.this){sending=false;} if(success)drainQueue(); }
        }).start();
    }
    private String read(InputStream in)throws IOException { if(in==null)return ""; BufferedReader r=new BufferedReader(new InputStreamReader(in)); StringBuilder b=new StringBuilder(); String s; while((s=r.readLine())!=null)b.append(s); return b.toString(); }
    private String shorten(String s){return s==null?"":s.substring(0,Math.min(180,s.length()));}
    private String coords(Location l){return String.format(Locale.US,"%.6f, %.6f (±%.0fm)",l.getLatitude(),l.getLongitude(),l.getAccuracy());}
    private static String q(String s){return "\""+(s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"))+"\"";}
    private static String iso(long t){return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).format(new Date(t));}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"GPS tracking",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String s){
        Intent i=new Intent(this,MainActivity.class); PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26 ? new Notification.Builder(this,CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Tic & Terry Delivery").setContentText(s).setStyle(new Notification.BigTextStyle().bigText(s)).setContentIntent(p).setOngoing(true).build();
    }
    private void update(String s){Config.status(this,s);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY,note(s));}
    @Override public void onDestroy(){retryHandler.removeCallbacks(retryTask);if(manager!=null)manager.removeUpdates(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onStatusChanged(String p,int s,Bundle b){} @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){update("Location provider is disabled");}
}

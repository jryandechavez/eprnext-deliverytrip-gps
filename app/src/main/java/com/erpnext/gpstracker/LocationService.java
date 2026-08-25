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
    private LocationManager manager; private long lastSent=0; private boolean sending=false;

    @Override public void onCreate() { super.onCreate(); createChannel(); startForeground(NOTIFY,note("Waiting for location…")); manager=(LocationManager)getSystemService(LOCATION_SERVICE); }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (!Config.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            long ms=Config.intervalMs(this);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,ms,0,this);
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,ms,0,this);
            Location best=bestLast(); if(best!=null && (ACTION_NOW.equals(intent==null?null:intent.getAction()) || System.currentTimeMillis()-lastSent>=ms)) send(best);
        } catch(SecurityException e) { update("Location permission is missing"); }
        return START_STICKY;
    }
    private Location bestLast() {
        try { Location a=manager.getLastKnownLocation(LocationManager.GPS_PROVIDER), b=manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); return a==null?b:(b==null||a.getTime()>b.getTime()?a:b); }
        catch(SecurityException e){ return null; }
    }
    @Override public void onLocationChanged(Location l) { if(System.currentTimeMillis()-lastSent>=Config.intervalMs(this)) send(l); }
    private synchronized void send(Location l) {
        if(sending)return; sending=true; lastSent=System.currentTimeMillis();
        new Thread(() -> {
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL(Config.url(this)).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json");
                if(!Config.key(this).isEmpty()) c.setRequestProperty("Authorization","token "+Config.key(this)+":"+Config.secret(this));
                String body="{\"device_id\":"+q(Config.deviceId(this))+",\"latitude\":"+l.getLatitude()+",\"longitude\":"+l.getLongitude()+",\"accuracy\":"+l.getAccuracy()+",\"altitude\":"+(l.hasAltitude()?l.getAltitude():"null")+",\"speed\":"+(l.hasSpeed()?l.getSpeed():"null")+",\"bearing\":"+(l.hasBearing()?l.getBearing():"null")+",\"recorded_at\":"+q(iso(l.getTime()))+"}";
                try(OutputStream os=c.getOutputStream()){os.write(body.getBytes(StandardCharsets.UTF_8));}
                int code=c.getResponseCode(); String response=read(code>=400?c.getErrorStream():c.getInputStream());
                update(code>=200&&code<300 ? "Sent successfully (HTTP "+code+")\n"+coords(l) : "Server error HTTP "+code+"\n"+shorten(response));
            } catch(Exception e){ update("Send failed: "+e.getMessage()); } finally { if(c!=null)c.disconnect(); sending=false; }
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
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Bluecore GPS").setContentText(s).setStyle(new Notification.BigTextStyle().bigText(s)).setContentIntent(p).setOngoing(true).build();
    }
    private void update(String s){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY,note(s));}
    @Override public void onDestroy(){if(manager!=null)manager.removeUpdates(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onStatusChanged(String p,int s,Bundle b){} @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){update("Location provider is disabled");}
}

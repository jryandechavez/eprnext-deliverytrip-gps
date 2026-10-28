package com.erpnext.gpstracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;

final class MobileDataCounter {
    private static final String PREFS="mobile_data_counter", TOTAL="total", RX="rx", TX="tx", READY="ready", MOBILE="mobile";

    static synchronized void sample(Context context){
        long rx=TrafficStats.getUidRxBytes(context.getApplicationInfo().uid),tx=TrafficStats.getUidTxBytes(context.getApplicationInfo().uid);
        if(rx==TrafficStats.UNSUPPORTED||tx==TrafficStats.UNSUPPORTED)return;
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);boolean ready=p.getBoolean(READY,false),wasMobile=p.getBoolean(MOBILE,false);long total=p.getLong(TOTAL,0);
        if(ready&&wasMobile){long delta=Math.max(0,rx-p.getLong(RX,rx))+Math.max(0,tx-p.getLong(TX,tx));total+=delta;}
        p.edit().putBoolean(READY,true).putBoolean(MOBILE,isMobile(context)).putLong(RX,rx).putLong(TX,tx).putLong(TOTAL,total).apply();
    }

    static synchronized void reset(Context context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();sample(context);}
    static long bytes(Context context){sample(context);return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong(TOTAL,0);}
    static String display(Context context){long bytes=bytes(context);if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(java.util.Locale.US,"%.1f KB",bytes/1024d);return String.format(java.util.Locale.US,"%.2f MB",bytes/(1024d*1024d));}
    private static boolean isMobile(Context context){ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);Network network=manager==null?null:manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);}
}

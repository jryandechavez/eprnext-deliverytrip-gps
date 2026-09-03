package com.erpnext.gpstracker;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
final class IntentStarter {
    static void startRadio(Context c){Intent i=new Intent(c,PttReceiverService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
}

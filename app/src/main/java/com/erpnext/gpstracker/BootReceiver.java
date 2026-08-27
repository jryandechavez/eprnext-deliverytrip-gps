package com.erpnext.gpstracker;
import android.content.*;
import android.os.Build;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        UploadScheduler.ensurePeriodic(c); UploadScheduler.whenOnline(c);
        if (Config.enabled(c) && Config.prefs(c).getBoolean("boot",true)) {
            Intent service=new Intent(c,LocationService.class).setAction(LocationService.ACTION_START);
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(service); else c.startService(service);
        }
    }
}

package com.erpnext.gpstracker;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

final class UploadScheduler {
    private static Constraints online() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }
    static void whenOnline(Context context) {
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(OfflineUploadWorker.class)
            .setConstraints(online()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("bluecore-upload-when-online",ExistingWorkPolicy.REPLACE,request);
    }
    static void ensurePeriodic(Context context) {
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(OfflineUploadWorker.class,15,TimeUnit.MINUTES)
            .setConstraints(online()).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("bluecore-upload-safety-net",ExistingPeriodicWorkPolicy.KEEP,request);
    }
}

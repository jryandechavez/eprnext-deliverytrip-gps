package com.erpnext.gpstracker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class OfflineUploadWorker extends Worker {
    public OfflineUploadWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork() {
        Context context=getApplicationContext(); LocationQueue queue=new LocationQueue(context);
        try {
            for(int sent=0;sent<100;sent++) {
                LocationQueue.Item item=queue.oldest();
                if(item==null) { Config.status(context,"All saved locations uploaded successfully. 0 queued."); return Result.success(); }
                String error=upload(context,item.payload);
                if(error!=null) { queue.failed(item.id,error); Config.status(context,"Automatic upload pending: "+queue.count()+" saved locally.\n"+error); return Result.retry(); }
                queue.remove(item.id); Config.status(context,"Automatic upload active: "+queue.count()+" queued.");
            }
            UploadScheduler.whenOnline(context); return Result.success();
        } finally { queue.close(); }
    }

    private String upload(Context context,String payload) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(Config.url(context)).openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json"); connection.setRequestProperty("Accept","application/json");
            if(!Config.key(context).isEmpty()) connection.setRequestProperty("Authorization","token "+Config.key(context)+":"+Config.secret(context));
            try(OutputStream output=connection.getOutputStream()){output.write(payload.getBytes(StandardCharsets.UTF_8));}
            int code=connection.getResponseCode();
            if(code>=200&&code<300)return null;
            return "Server error HTTP "+code+": "+read(connection.getErrorStream());
        } catch(Exception error) { return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); }
        finally { if(connection!=null)connection.disconnect(); }
    }
    private String read(InputStream input)throws IOException {
        if(input==null)return ""; BufferedReader reader=new BufferedReader(new InputStreamReader(input)); StringBuilder value=new StringBuilder(); String line;
        while((line=reader.readLine())!=null&&value.length()<180)value.append(line); return value.substring(0,Math.min(180,value.length()));
    }
}

package com.erpnext.gpstracker;

import android.content.Context;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class ErpAuth {
    private static final long REFRESH_INTERVAL_MS=60L*60L*1000L;

    static void apply(Context context,HttpURLConnection connection) throws IOException {
        String email=Config.userEmail(context), password=Config.userPassword(context);
        if(!email.isEmpty()&&!password.isEmpty()&&(Config.sessionId(context).isEmpty()||System.currentTimeMillis()-Config.sessionRefreshedAt(context)>REFRESH_INTERVAL_MS)) {
            refreshSession(context,email,password);
        }
        String session=Config.sessionId(context);
        if(!session.isEmpty()) connection.setRequestProperty("Cookie","sid="+session);
        else if(!Config.key(context).isEmpty()) connection.setRequestProperty("Authorization","token "+Config.key(context)+":"+Config.secret(context));
    }

    static String refreshSession(Context context,String email,String password) throws IOException {
        String endpoint=Config.url(context);int marker=endpoint.indexOf("/api/method/");String base=marker>0?endpoint.substring(0,marker):endpoint;
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(base+"/api/method/login").openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            String form="usr="+URLEncoder.encode(email,"UTF-8")+"&pwd="+URLEncoder.encode(password,"UTF-8");
            try(OutputStream out=c.getOutputStream()){out.write(form.getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();String sid="";
            for(Map.Entry<String,List<String>> h:c.getHeaderFields().entrySet()) if(h.getKey()!=null&&"Set-Cookie".equalsIgnoreCase(h.getKey())) for(String cookie:h.getValue()) if(cookie.startsWith("sid=")){int end=cookie.indexOf(';');sid=cookie.substring(4,end>4?end:cookie.length());break;}
            if(code<200||code>=300||sid.isEmpty()) throw new IOException("ERPNext rejected the saved login (HTTP "+code+")");
            Config.authPrefs(context).edit().putString("session_id",sid).putString("user_email",email).putString("user_password",password).putLong("session_refreshed_at",System.currentTimeMillis()).apply();
            return sid;
        } finally {if(c!=null)c.disconnect();}
    }
}

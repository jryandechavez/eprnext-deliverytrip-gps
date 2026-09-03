package com.erpnext.gpstracker;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.util.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class PttReceiverService extends Service {
    private static final String CHANNEL="fleet_radio"; private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean running; private String cursor="";
    @Override public void onCreate(){super.onCreate();createChannel();startForeground(2202,notification("Fleet radio listening"));running=true;handler.post(this::poll);}
    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){running=false;handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Fleet radio",NotificationManager.IMPORTANCE_LOW);c.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){Intent open=new Intent(this,MainActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));return new Notification.Builder(this,Build.VERSION.SDK_INT>=26?CHANNEL:null).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Tic & Terry Fleet Radio").setContentText(text).setOngoing(true).setContentIntent(p).build();}
    private void poll(){if(!running)return;if(!Config.sessionId(this).isEmpty()||!Config.key(this).isEmpty())new Thread(()->{try{JSONObject result=request(new JSONObject().put("after",cursor).put("device_id",Config.deviceId(this)));cursor=result.optString("cursor",cursor);JSONArray rows=result.optJSONArray("messages");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject m=rows.getJSONObject(i);try{play(m.getString("audio"),m.optString("sender"));}catch(Exception e){Log.e("BluecorePTT","Background playback failed",e);}}}catch(Exception e){Log.e("BluecorePTT","Background polling failed",e);}finally{handler.postDelayed(this::poll,750);}}).start();else handler.postDelayed(this::poll,750);}
    private String api(){String endpoint=Config.url(this);int i=endpoint.indexOf("/api/method/");return (i>0?endpoint.substring(0,i):endpoint)+"/api/method/gps_tracker.api.ptt_poll";}
    private JSONObject request(JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(api()).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Content-Type","application/json");ErpAuth.apply(this,c);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String text=read(in);if(code<200||code>=300)throw new IOException("HTTP "+code);JSONObject root=new JSONObject(text);return root.optJSONObject("message")==null?root:root.getJSONObject("message");}
    private void play(String encoded,String sender)throws Exception{byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);File file=File.createTempFile("ptt-bg-",".amr",getCacheDir());try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);int old=a.getStreamVolume(AudioManager.STREAM_MUSIC);AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();a.requestAudioFocus(change->{},AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);a.setMode(AudioManager.MODE_NORMAL);a.setSpeakerphoneOn(true);a.setStreamVolume(AudioManager.STREAM_MUSIC,a.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0);MediaPlayer p=new MediaPlayer();p.setAudioAttributes(attrs);for(AudioDeviceInfo d:a.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){p.setPreferredDevice(d);break;}p.setVolume(1,1);p.setDataSource(file.getAbsolutePath());p.setOnCompletionListener(x->{x.release();file.delete();a.setStreamVolume(AudioManager.STREAM_MUSIC,old,0);a.abandonAudioFocus(null);});p.prepare();p.start();getSystemService(NotificationManager.class).notify(2202,notification("Receiving from "+sender+" · SPEAKER MAX"));}
    private static String read(InputStream in)throws IOException{BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String s;while((s=r.readLine())!=null)b.append(s);return b.toString();}
}

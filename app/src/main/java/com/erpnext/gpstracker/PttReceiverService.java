package com.erpnext.gpstracker;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.net.ConnectivityManager;
import android.os.*;
import android.util.Log;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;

public class PttReceiverService extends Service {
    static final String ACTION_START_TX="com.erpnext.gpstracker.PTT_START";
    static final String ACTION_STOP_TX="com.erpnext.gpstracker.PTT_STOP";
    static final String ACTION_STATUS="com.erpnext.gpstracker.PTT_STATUS";
    static final String EXTRA_STATUS="status";
    private static final String CHANNEL="fleet_radio", TAG="BluecorePTT";
    private static final int NOTIFY=2202, SAMPLE_RATE=8000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final OkHttpClient client=new OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private volatile boolean running, ready, wantsTransmit, transmitting;
    private WebSocket socket; private AudioRecord recorder; private AudioTrack player;
    private PowerManager.WakeLock radioWakeLock;
    private final Runnable usageTask=new Runnable(){@Override public void run(){if(!running)return;MobileDataCounter.sample(PttReceiverService.this);handler.postDelayed(this,5000);}};
    private final Runnable reconnectTask=()->{if(!running)return;ready=false;WebSocket old=socket;socket=null;if(old!=null)old.cancel();connect();};
    private final BroadcastReceiver networkReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){handler.removeCallbacks(reconnectTask);handler.postDelayed(reconnectTask,750);}};

    @Override public void onCreate(){super.onCreate();MobileDataCounter.sample(this);createChannel();startForeground(NOTIFY,note("Connecting to live fleet radio"));PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);radioWakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PLAguila:LiveRadio");radioWakeLock.setReferenceCounted(false);radioWakeLock.acquire();registerReceiver(networkReceiver,new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));running=true;handler.post(usageTask);connect();}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&ACTION_START_TX.equals(i.getAction())){if(!online()){wantsTransmit=false;update("No internet connection · enable Wi-Fi or mobile data");return START_STICKY;}wantsTransmit=true;if(ready)requestTransmit();else{update("Radio reconnecting · keep holding");handler.removeCallbacks(reconnectTask);handler.post(reconnectTask);}}else if(i!=null&&ACTION_STOP_TX.equals(i.getAction())){wantsTransmit=false;stopCapture();sendJson("tx_end");}if(!ready&&socket==null&&!Config.sessionId(this).isEmpty())connect();return START_STICKY;}
    @Override public void onDestroy(){MobileDataCounter.sample(this);running=false;handler.removeCallbacksAndMessages(null);try{unregisterReceiver(networkReceiver);}catch(Exception ignored){}stopCapture();stopPlayback();if(socket!=null)socket.close(1000,"Service stopped");client.dispatcher().executorService().shutdown();if(radioWakeLock!=null&&radioWakeLock.isHeld())radioWakeLock.release();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    private void connect(){
        if(!running)return;String sid=Config.sessionId(this);
        if(sid.isEmpty()){update("Sign in to join live fleet radio");return;}
        Request request=new Request.Builder().url(relayUrl()).build();
        socket=client.newWebSocket(request,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){try{ws.send(new JSONObject().put("type","auth").put("sid",Config.sessionId(PttReceiverService.this)).put("deviceId",Config.deviceId(PttReceiverService.this)).toString());}catch(Exception e){ws.close(4002,"Invalid auth");}}
            @Override public void onMessage(WebSocket ws,String text){handleControl(text);}
            @Override public void onMessage(WebSocket ws,ByteString bytes){AudioTrack p=player;if(p!=null){byte[] pcm=bytes.toByteArray();boostVoice(pcm);p.write(pcm,0,pcm.length);}}
            @Override public void onClosed(WebSocket ws,int code,String reason){disconnected(ws,reason);}
            @Override public void onFailure(WebSocket ws,Throwable error,Response response){Log.e(TAG,"WebSocket failed",error);disconnected(ws,error.getMessage());}
        });
    }
    private String relayUrl(){String endpoint=Config.url(this);int marker=endpoint.indexOf("/api/method/");String base=marker>0?endpoint.substring(0,marker):endpoint;if(base.startsWith("https://"))base="wss://"+base.substring(8);else if(base.startsWith("http://"))base="ws://"+base.substring(7);return base+"/ptt";}
    private void disconnected(WebSocket ws,String reason){if(socket!=ws)return;ready=false;socket=null;stopCapture();stopPlayback();update("Radio reconnecting…");if(running)handler.postDelayed(this::connect,2000);}
    private void handleControl(String text){try{JSONObject m=new JSONObject(text);String type=m.optString("type");if("ready".equals(type)){ready=true;update("Live fleet radio connected");if(wantsTransmit)requestTransmit();}else if("tx_granted".equals(type)){if(wantsTransmit)startCapture();}else if("busy".equals(type)){wantsTransmit=false;update("Radio busy · another device is talking");}else if("tx_start".equals(type)){startPlayback(m.optString("deviceId","device"));}else if("tx_end".equals(type)){stopPlayback();update("Live fleet radio connected");}}catch(Exception e){Log.e(TAG,"Bad radio control message",e);}}
    private void requestTransmit(){sendJson("tx_start");update("Waiting for radio channel…");}
    private void sendJson(String type){WebSocket ws=socket;if(ws!=null)try{ws.send(new JSONObject().put("type",type).toString());}catch(Exception ignored){}}

    private void startCapture(){
        if(transmitting||ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){return;}
        stopPlayback();int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);int size=Math.max(min,2048);
        recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);recorder.startRecording();transmitting=true;update("TRANSMITTING LIVE");
        new Thread(()->{byte[] frame=new byte[640];while(transmitting){int n=recorder.read(frame,0,frame.length);WebSocket ws=socket;if(n>0&&ws!=null)ws.send(ByteString.of(frame,0,n));}},"ptt-microphone").start();
    }
    private void stopCapture(){transmitting=false;AudioRecord r=recorder;recorder=null;if(r!=null){try{r.stop();}catch(Exception ignored){}r.release();}}
    private void startPlayback(String sender){
        stopCapture();stopPlayback();AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);a.setMode(AudioManager.MODE_NORMAL);a.setSpeakerphoneOn(true);a.setStreamVolume(AudioManager.STREAM_MUSIC,a.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0);a.requestAudioFocus(change->{},AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);player=new AudioTrack(AudioManager.STREAM_MUSIC,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,4096),AudioTrack.MODE_STREAM);player.setStereoVolume(1f,1f);player.play();update("Receiving LIVE from "+sender+" · SPEAKER MAX");
    }
    private void stopPlayback(){AudioTrack p=player;player=null;if(p!=null){try{p.stop();}catch(Exception ignored){}p.release();}}
    private static void boostVoice(byte[] pcm){for(int i=0;i+1<pcm.length;i+=2){int sample=(short)((pcm[i]&0xff)|(pcm[i+1]<<8));int boosted=sample*3;if(boosted>32767)boosted=32767;else if(boosted<-32768)boosted=-32768;pcm[i]=(byte)boosted;pcm[i+1]=(byte)(boosted>>8);}}
    private boolean online(){ConnectivityManager c=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);android.net.NetworkInfo n=c.getActiveNetworkInfo();return n!=null&&n.isConnected();}
    private void update(String text){getSystemService(NotificationManager.class).notify(NOTIFY,note(text));sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS,text));}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Fleet radio",NotificationManager.IMPORTANCE_LOW);c.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification note(String text){Intent open=new Intent(this,MainActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("PL Aguila Live Radio").setContentText(text).setOngoing(true).setContentIntent(p).build();}
}

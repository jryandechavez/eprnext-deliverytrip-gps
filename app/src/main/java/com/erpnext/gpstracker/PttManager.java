package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class PttManager {
    private final Activity activity; private final Button button; private final TextView status;
    private final Handler handler=new Handler(Looper.getMainLooper()); private MediaRecorder recorder;
    private File recording; private boolean polling; private String cursor="";
    PttManager(Activity activity,Button button,TextView status){this.activity=activity;this.button=button;this.status=status;
        button.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){startTransmit();return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){stopTransmit();return true;}return false;});}
    void start(){IntentStarter.startRadio(activity);}
    void stop(){if(recorder!=null)stopTransmit();}
    private String api(String method){String endpoint=Config.url(activity);int i=endpoint.indexOf("/api/method/");return (i>0?endpoint.substring(0,i):endpoint)+"/api/method/gps_tracker.api."+method;}
    private void startTransmit(){
        if(Config.sessionId(activity).isEmpty()&&Config.key(activity).isEmpty()){status.setText("Sign in to use Push to Talk");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},30);return;}
        try{recording=File.createTempFile("ptt-",".amr",activity.getCacheDir());recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);recorder.setOutputFile(recording.getAbsolutePath());recorder.prepare();recorder.start();button.setText("Talking… release to send");status.setText("Transmitting to all devices");}catch(Exception e){recorder=null;status.setText("Microphone unavailable: "+e.getMessage());}
    }
    private void stopTransmit(){if(recorder==null)return;try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;button.setText("Hold to Talk");File audio=recording;status.setText("Sending voice message…");new Thread(()->send(audio)).start();}
    private void send(File audio){try{byte[] bytes=readBytes(audio);if(bytes.length<100)throw new IOException("Hold the button longer before releasing");if(bytes.length>300000)throw new IOException("Message is too long");JSONObject body=new JSONObject().put("device_id",Config.deviceId(activity)).put("audio",Base64.encodeToString(bytes,Base64.NO_WRAP));request("ptt_send",body);activity.runOnUiThread(()->status.setText("Sent · listening to fleet radio"));}catch(Exception e){activity.runOnUiThread(()->status.setText("Send failed: "+e.getMessage()));}finally{audio.delete();}}
    private void poll(){if(!polling)return;if((!Config.sessionId(activity).isEmpty()||!Config.key(activity).isEmpty())&&recorder==null)new Thread(()->{try{JSONObject body=new JSONObject().put("after",cursor).put("device_id",Config.deviceId(activity));JSONObject result=request("ptt_poll",body);cursor=result.optString("cursor",cursor);JSONArray messages=result.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);try{play(m.getString("audio"),m.optString("sender"));}catch(Exception error){Log.e("BluecorePTT","Unable to play received audio",error);}}}catch(Exception error){Log.e("BluecorePTT","Radio polling failed",error);}finally{handler.postDelayed(this::poll,3000);}}).start();else handler.postDelayed(this::poll,3000);}
    private void play(String encoded,String sender)throws Exception{byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);File file=File.createTempFile("ptt-rx-",".amr",activity.getCacheDir());try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}activity.runOnUiThread(()->status.setText("Receiving from "+sender+" · SPEAKER MAX"));AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);int oldMedia=audio.getStreamVolume(AudioManager.STREAM_MUSIC),oldCall=audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL),oldMode=audio.getMode();boolean oldSpeaker=audio.isSpeakerphoneOn();AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();AudioFocusRequest focus=Build.VERSION.SDK_INT>=26?new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{}).build():null;if(Build.VERSION.SDK_INT>=26)audio.requestAudioFocus(focus);else audio.requestAudioFocus(change->{},AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);audio.setMode(AudioManager.MODE_NORMAL);audio.setSpeakerphoneOn(true);audio.setStreamVolume(AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0);audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL,audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),0);MediaPlayer player=new MediaPlayer();player.setAudioAttributes(attrs);for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){player.setPreferredDevice(device);break;}player.setVolume(1f,1f);player.setDataSource(file.getAbsolutePath());player.setOnCompletionListener(p->{p.release();file.delete();audio.setStreamVolume(AudioManager.STREAM_MUSIC,oldMedia,0);audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL,oldCall,0);audio.setSpeakerphoneOn(oldSpeaker);audio.setMode(oldMode);if(Build.VERSION.SDK_INT>=26)audio.abandonAudioFocusRequest(focus);else audio.abandonAudioFocus(null);activity.runOnUiThread(()->status.setText("Listening to fleet radio"));});player.prepare();player.start();}
    private JSONObject request(String method,JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(api(method)).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Content-Type","application/json");ErpAuth.apply(activity,c);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String text=read(in);if(code<200||code>=300)throw new IOException("HTTP "+code);JSONObject root=new JSONObject(text);return root.optJSONObject("message")==null?root:root.getJSONObject("message");}
    private static byte[] readBytes(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static String read(InputStream in)throws IOException{BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String s;while((s=r.readLine())!=null)b.append(s);return b.toString();}
}

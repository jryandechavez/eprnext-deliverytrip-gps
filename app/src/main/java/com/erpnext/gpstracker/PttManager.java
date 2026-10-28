package com.erpnext.gpstracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.CountDownTimer;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

final class PttManager {
    private final Activity activity; private final Button button; private final TextView status;
    private final ProgressBar progress; private boolean transmitting,registered; private CountDownTimer timer;
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String message=i.getStringExtra(PttReceiverService.EXTRA_STATUS);if(message==null)return;status.setText(message);if("TRANSMITTING LIVE".equals(message)&&transmitting&&timer==null)startTimer();if(message.startsWith("No internet")||message.startsWith("Sign in")){transmitting=false;button.setText("Hold to Talk");progress.setProgress(0);}}};
    PttManager(Activity activity,Button button,TextView status){this.activity=activity;this.button=button;this.status=status;
        progress=activity.findViewById(R.id.pttProgress);
        button.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){startTransmit();return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){stopTransmit();return true;}return false;});}
    void start(){if(!registered){IntentFilter filter=new IntentFilter(PttReceiverService.ACTION_STATUS);if(android.os.Build.VERSION.SDK_INT>=33)activity.registerReceiver(statusReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else activity.registerReceiver(statusReceiver,filter);registered=true;}IntentStarter.startRadio(activity);}
    void stop(){if(transmitting)stopTransmit();if(registered){try{activity.unregisterReceiver(statusReceiver);}catch(Exception ignored){}registered=false;}}
    private void startTransmit(){
        if(Config.sessionId(activity).isEmpty()&&Config.key(activity).isEmpty()){status.setText("Sign in to use Push to Talk");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},30);return;}
        transmitting=true;progress.setProgress(0);send(PttReceiverService.ACTION_START_TX);button.setText("Connecting… keep holding");status.setText("Checking live radio connection…");
    }
    private void startTimer(){button.setText("Talking… release to stop");timer=new CountDownTimer(60000,100){public void onTick(long left){int elapsed=(int)((60000-left)/100);progress.setProgress(elapsed);status.setText("Transmitting live · "+(elapsed/10)+"s / 60s");}public void onFinish(){progress.setProgress(600);stopTransmit(true);}}.start();}
    private void stopTransmit(){stopTransmit(false);}
    private void stopTransmit(boolean limitReached){if(!transmitting)return;transmitting=false;if(timer!=null){timer.cancel();timer=null;}send(PttReceiverService.ACTION_STOP_TX);button.setText("Hold to Talk");status.setText(limitReached?"60 second talk limit reached · release to talk again":"Listening to live fleet radio");if(!limitReached)progress.setProgress(0);}
    private void send(String action){Intent i=new Intent(activity,PttReceiverService.class).setAction(action);if(android.os.Build.VERSION.SDK_INT>=26)activity.startForegroundService(i);else activity.startService(i);}
}

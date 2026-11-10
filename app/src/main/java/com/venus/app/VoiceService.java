package com.venus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

/** Keeps the app alive in the background so the microphone keeps listening. */
public class VoiceService extends Service {
    private static final String CH = "venus_listen";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel c = new NotificationChannel(CH, "Venus listening", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        int pf = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pf);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VoiceService.class).setAction("stop"), pf);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Venus is listening")
                .setContentText("Say your wake name to give a command")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else
            startForeground(1, n);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}

package com.ttangja.purpleplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 재생하는 동안 떠 있는 지킴이: 15초마다 접근성 권한을 확인하고 꺼졌으면 다시 켠다. */
public class Watchdog extends Service {
    private static final String CH = "guard";
    private static final long EVERY = 15_000;
    private Handler h;
    private int miss;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, Watchdog.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, Watchdog.class)); } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "재생 지킴이", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            Notification n = nb.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("퍼플매크로 재생 중")
                    .setContentText(Guard.canWrite(this) ? "접근성 권한이 꺼지면 자동으로 다시 켜요" : "자동 복구를 쓰려면 앱에서 ADB 안내를 확인하세요")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            h.postDelayed(this::check, EVERY);
        }
        return START_STICKY;
    }

    private void check() {
        try {
            if (!Guard.wantPlaying(this)) { stopSelf(); return; }
            if (!Guard.isListed(this)) {
                miss = 0;
                Guard.ensure(this, false);
            } else if (PlayerService.I == null) {
                // 목록에는 있는데 연결이 안 된 상태가 45초 이어지면 뺐다가 다시 넣는다
                if (++miss >= 3) { miss = 0; Guard.ensure(this, true); }
            } else {
                miss = 0;
                if (!PlayerService.I.isPlaying()) PlayerService.I.resumeIfWanted();
            }
        } catch (Exception ignored) {}
        if (h != null) h.postDelayed(this::check, EVERY);
    }

    @Override
    public void onDestroy() {
        if (h != null) h.removeCallbacksAndMessages(null);
        h = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

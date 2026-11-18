package com.ttangja.purpleplayer;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

/** 접근성 권한 자동 복구 (ADB로 WRITE_SECURE_SETTINGS를 한 번 받아 두면 동작) + 15분 알람 + 부팅 시 확인 */
public class Guard extends BroadcastReceiver {
    static final String ADB_CMD = "adb shell pm grant com.ttangja.purpleplayer android.permission.WRITE_SECURE_SETTINGS";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pp", Context.MODE_PRIVATE);
    }

    static boolean wantPlaying(Context c) {
        return prefs(c).getBoolean("playing", false);
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static String comp(Context c) {
        return new ComponentName(c, PlayerService.class).flattenToString();
    }

    private static String compShort(Context c) {
        return new ComponentName(c, PlayerService.class).flattenToShortString();
    }

    static boolean isListed(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            for (String p : s.split(":")) {
                if (p.equalsIgnoreCase(comp(c)) || p.equalsIgnoreCase(compShort(c))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 목록에 없으면 다시 넣는다. force면 뺐다가 다시 넣어 강제로 다시 연결한다. */
    static boolean ensure(Context c, boolean force) {
        if (!canWrite(c)) return false;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            StringBuilder rest = new StringBuilder();
            boolean had = false;
            for (String p : s.split(":")) {
                if (p.isEmpty()) continue;
                if (p.equalsIgnoreCase(comp(c)) || p.equalsIgnoreCase(compShort(c))) { had = true; continue; }
                if (rest.length() > 0) rest.append(':');
                rest.append(p);
            }
            if (had && !force) return false;
            if (had) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.toString());
                SystemClock.sleep(600);
            }
            String now = rest.length() > 0 ? rest + ":" + comp(c) : comp(c);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, now);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            SharedPreferences p = prefs(c);
            p.edit().putInt("recoverCount", p.getInt("recoverCount", 0) + 1)
                    .putLong("recoverAt", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, Guard.class).setAction("com.ttangja.purpleplayer.CHECK");
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void arm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                    AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi(c));
        } catch (Exception ignored) {}
        Watchdog.start(c);
    }

    static void disarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi(c));
        } catch (Exception ignored) {}
        Watchdog.stop(c);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (!wantPlaying(c)) return;
            if (!isListed(c)) ensure(c, false);
            Watchdog.start(c);
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) arm(c);
        } catch (Exception ignored) {}
    }
}

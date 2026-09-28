package com.artimoshka.cvetochek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

public class PlayerService extends Service {
    public interface ActionSink { void onAction(String action); }
    public static volatile ActionSink sink;

    private static final String CH = "cvet_player";
    private static final int NID = 7;

    public static void show(Context ctx, String title, String artist, boolean playing) {
        Intent i = new Intent(ctx, PlayerService.class);
        i.setAction("com.artimoshka.cvetochek.SHOW");
        i.putExtra("show", true);
        i.putExtra("title", title);
        i.putExtra("artist", artist);
        i.putExtra("playing", playing);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void hide(Context ctx) {
        try { ctx.stopService(new Intent(ctx, PlayerService.class)); } catch (Exception ignored) {}
    }

    private PowerManager.WakeLock wl;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cvetochek:player");
            wl.setReferenceCounted(false);
            wl.acquire();
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getStringExtra("act");
            if (act != null) {
                ActionSink s = sink;
                if (s != null) { try { s.onAction(act); } catch (Exception ignored) {} }
                if ("stop".equals(act)) { stopSelf(); return START_NOT_STICKY; }
                return START_NOT_STICKY;
            }
            if (intent.getBooleanExtra("show", false)) {
                Notification n = buildNotif(
                    intent.getStringExtra("title"),
                    intent.getStringExtra("artist"),
                    intent.getBooleanExtra("playing", true));
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NID, n);
                    }
                } catch (Exception ignored) {}
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH) != null) return;
            NotificationChannel c = new NotificationChannel(CH, "Плеер", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Управление воспроизведением");
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        } catch (Exception ignored) {}
    }

    private PendingIntent pi(String act) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction("com.artimoshka.cvetochek." + act);
        i.putExtra("act", act);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 1000 + act.hashCode(), i, flags);
    }

    private Notification buildNotif(String title, String artist, boolean playing) {
        ensureChannel();
        int icon = android.R.drawable.ic_media_play;
        try { if (getApplicationInfo().icon != 0) icon = getApplicationInfo().icon; } catch (Exception ignored) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(icon)
            .setContentTitle(title == null || title.isEmpty() ? "Цветочек" : title)
            .setContentText(artist == null ? "" : artist)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "prev", pi("prev"))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "play", pi("toggle"))
            .addAction(android.R.drawable.ic_media_next, "next", pi("next"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "stop", pi("stop"));
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                b.setContentIntent(PendingIntent.getActivity(this, 200, launch, flags));
            }
        } catch (Exception ignored) {}
        return b.build();
    }
}

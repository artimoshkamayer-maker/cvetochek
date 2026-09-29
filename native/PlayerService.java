package com.artimoshka.cvetochek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;

public class PlayerService extends Service {
    public interface ActionSink { void onEvent(String action, long posMs); }
    public static volatile ActionSink sink;

    private static final String CH = "cvet_player";
    private static final int NID = 7;

    private PowerManager.WakeLock wl;

    private String lastTitle = "Цветочек";
    private String lastArtist = "";
    private boolean lastPlaying = true;
    private long lastPos = 0;
    private long lastDur = 0;
    private boolean lastFav = false;
    private String lastAcc = "#0b84ff";
    private String lastLetter = "♪";

    public static void show(Context ctx, String title, String artist, boolean playing,
                            long posMs, long durMs, boolean fav, String acc, String letter) {
        Intent i = new Intent(ctx, PlayerService.class);
        i.setAction("com.artimoshka.cvetochek.SHOW");
        i.putExtra("show", true);
        i.putExtra("title", title);
        i.putExtra("artist", artist);
        i.putExtra("playing", playing);
        i.putExtra("pos", posMs);
        i.putExtra("dur", durMs);
        i.putExtra("fav", fav);
        i.putExtra("acc", acc);
        i.putExtra("letter", letter);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void hide(Context ctx) {
        try { ctx.stopService(new Intent(ctx, PlayerService.class)); } catch (Exception ignored) {}
    }

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

    private void fire(String action, long posMs) {
        ActionSink s = sink;
        if (s != null) { try { s.onEvent(action, posMs); } catch (Exception ignored) {} }
        if ("stop".equals(action)) { try { stopSelf(); } catch (Exception ignored) {} }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getStringExtra("act");
            if (act != null) {
                fire(act, -1);
                return START_NOT_STICKY;
            }
            if (intent.getBooleanExtra("show", false)) {
                lastTitle = strExtra(intent, "title", "Цветочек");
                lastArtist = strExtra(intent, "artist", "");
                lastPlaying = intent.getBooleanExtra("playing", true);
                lastPos = Math.max(0, intent.getLongExtra("pos", 0));
                lastDur = Math.max(0, intent.getLongExtra("dur", 0));
                lastFav = intent.getBooleanExtra("fav", false);
                lastAcc = strExtra(intent, "acc", "#0b84ff");
                lastLetter = strExtra(intent, "letter", "♪");
                try {
                    Notification n = buildCustom();
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

    private String strExtra(Intent intent, String key, String def) {
        try {
            String v = intent.getStringExtra(key);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
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

    private int immutFlags() {
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return fl;
    }

    private PendingIntent pi(String act) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction("com.artimoshka.cvetochek." + act);
        i.putExtra("act", act);
        return PendingIntent.getService(this, 1000 + act.hashCode(), i, immutFlags());
    }

    private PendingIntent openPI() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) return PendingIntent.getActivity(this, 200, launch, immutFlags());
        } catch (Exception ignored) {}
        return null;
    }

    private int appIcon() {
        try { if (getApplicationInfo().icon != 0) return getApplicationInfo().icon; } catch (Exception ignored) {}
        return android.R.drawable.ic_media_play;
    }

    private int favIcon(boolean fav) {
        try {
            int id = getResources().getIdentifier(fav ? "ic_fav_on" : "ic_fav_off", "drawable", getPackageName());
            if (id != 0) return id;
        } catch (Exception ignored) {}
        return fav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off;
    }

    private Bitmap letterArt(String letter, String acc) {
        int S = 144;
        Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        int c1 = 0xFF0B84FF, c2 = 0xFF101018;
        try { c1 = Color.parseColor(acc); } catch (Exception ignored) {}
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, S, S, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, S, S, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(84);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlign(Paint.Align.CENTER);
        String L = (letter == null || letter.isEmpty()) ? "♪" : letter.substring(0, 1);
        c.drawText(L, S / 2f, S / 2f - (t.descent() + t.ascent()) / 2f, t);
        return bm;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void fillViews(RemoteViews v) {
        v.setTextViewText(R.id.title, lastTitle == null || lastTitle.isEmpty() ? "Цветочек" : lastTitle);
        v.setTextViewText(R.id.sub, lastArtist == null ? "" : lastArtist);
        try { v.setImageViewBitmap(R.id.art, letterArt(lastLetter, lastAcc)); } catch (Exception ignored) {}
        v.setImageViewResource(R.id.btnFav, favIcon(lastFav));
        v.setImageViewResource(R.id.btnPrev, android.R.drawable.ic_media_previous);
        v.setImageViewResource(R.id.btnPlay, lastPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        v.setImageViewResource(R.id.btnNext, android.R.drawable.ic_media_next);
        v.setImageViewResource(R.id.btnStop, android.R.drawable.ic_menu_close_clear_cancel);
        int max = 1000;
        int p = lastDur > 0 ? (int) Math.min(max, Math.max(0, lastPos * max / lastDur)) : 0;
        v.setProgressBar(R.id.prog, max, p, false);
        v.setTextViewText(R.id.tCur, fmt(lastPos));
        v.setTextViewText(R.id.tDur, fmt(lastDur));
        v.setOnClickPendingIntent(R.id.btnFav, pi("fav"));
        v.setOnClickPendingIntent(R.id.btnPrev, pi("prev"));
        v.setOnClickPendingIntent(R.id.btnPlay, pi("toggle"));
        v.setOnClickPendingIntent(R.id.btnNext, pi("next"));
        v.setOnClickPendingIntent(R.id.btnStop, pi("stop"));
    }

    private Notification buildCustom() {
        ensureChannel();
        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notif_player);
        fillViews(small);
        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_player);
        fillViews(big);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(appIcon())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        PendingIntent open = openPI();
        if (open != null) b.setContentIntent(open);
        return b.build();
    }
}

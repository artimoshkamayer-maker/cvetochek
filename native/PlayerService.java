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
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

public class PlayerService extends Service {
    public interface ActionSink { void onEvent(String action, long posMs); }
    public static volatile ActionSink sink;

    private static final String CH = "cvet_player";
    private static final int NID = 7;

    private MediaSessionCompat session;
    private PowerManager.WakeLock wl;

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
        try {
            session = new MediaSessionCompat(this, "cvetochek");
            session.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { fire("toggle", -1); }
                @Override public void onPause() { fire("toggle", -1); }
                @Override public void onSkipToNext() { fire("next", -1); }
                @Override public void onSkipToPrevious() { fire("prev", -1); }
                @Override public void onStop() { fire("stop", -1); }
                @Override public void onSeekTo(long pos) { fire("seekto", pos); }
            });
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver.class);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
            session.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 0, mb, fl));
            session.setActive(true);
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
                Notification n = buildFull(
                    intent.getStringExtra("title"),
                    intent.getStringExtra("artist"),
                    intent.getBooleanExtra("playing", true),
                    intent.getLongExtra("pos", 0),
                    intent.getLongExtra("dur", 0),
                    intent.getBooleanExtra("fav", false),
                    intent.getStringExtra("acc"),
                    intent.getStringExtra("letter"));
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
        try { if (session != null) session.release(); } catch (Exception ignored) {}
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
        int S = 256;
        Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        int c1 = 0xFF0B84FF, c2 = 0xFF101018;
        try { c1 = Color.parseColor(acc); } catch (Exception ignored) {}
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, S, S, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, S, S, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(150);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlign(Paint.Align.CENTER);
        String L = (letter == null || letter.isEmpty()) ? "♪" : letter.substring(0, 1);
        c.drawText(L, S / 2f, S / 2f - (t.descent() + t.ascent()) / 2f, t);
        return bm;
    }

    private Notification buildFull(String title, String artist, boolean playing,
                                   long posMs, long durMs, boolean fav, String acc, String letter) {
        ensureChannel();
        if (title == null || title.isEmpty()) title = "Цветочек";
        if (artist == null) artist = "";
        try {
            if (session != null) {
                long actions = PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_STOP;
                int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
                session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, Math.max(0, posMs), 1.0f)
                    .build());
                MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, letterArt(letter, acc));
                if (durMs > 0) md.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs);
                session.setMetadata(md.build());
            }
        } catch (Exception ignored) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(appIcon())
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(favIcon(fav), "fav", pi("fav"))
            .addAction(android.R.drawable.ic_media_previous, "prev", pi("prev"))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "play", pi("toggle"))
            .addAction(android.R.drawable.ic_media_next, "next", pi("next"))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session == null ? null : session.getSessionToken())
                .setShowActionsInCompactView(1, 2, 3)
                .setShowCancelButton(true)
                .setCancelButtonIntent(pi("stop")));
        PendingIntent open = openPI();
        if (open != null) b.setContentIntent(open);
        return b.build();
    }
}

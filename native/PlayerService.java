package com.artimoshka.cvetochek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class PlayerService extends Service {
    public interface ActionSink { void onEvent(String action, long posMs); }
    public static volatile ActionSink sink;

    static final String CH = "cvetochek_player";
    static final int NID = 7;
    static final String ACT = "cvet_act";
    // audio engine backend (ExoPlayer): plays even with WebView gone
    static Context appCtx;
    static ExoPlayer exo;
    static Equalizer sysEq;
    static int eqSid = 0;
    static Handler loop;
    static void loopTickRun() {
        try {
            if (exo != null && exo.isPlaying()) {
                try { savePos(); } catch (Throwable t) {}
                refreshNotification();
                if (loop != null) loop.postDelayed(loopTick, 5000);
            }
        } catch (Throwable t) {}
    }
    static final Runnable loopTick = () -> loopTickRun();
    static class Track {
        String uri = "", title = "", artist = "";
        Track(String u, String t, String a) { uri = u == null ? "" : u; title = t == null ? "" : t; artist = a == null ? "" : a; }
    }
    static final List<Track> queue = new ArrayList<>();
    static int qIndex = -1;
    static int repMode = 0; // 0 off 1 all 2 one
    static Track cur = new Track("", "", "");
    static String lastAcc = "#e91e63";
    static boolean lastFav = false;
    static String lastLetter = "?";
    static volatile long lastJsCall = 0;
    static volatile boolean lastPlaying = false;
    static MediaSessionCompat.Token sessionToken = null;
    static MediaSessionCompat sessRef = null;
    static boolean eqOn = false;
    static final float[] eqBands = new float[10];
    static float eqBass = 0;
    static final float[] EQ_C = new float[]{31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    static void loadEQ() {
        try {
            android.content.SharedPreferences p = qprefs();
            if (p == null) return;
            eqOn = p.getBoolean("eqon", false);
            eqBass = Math.max(-12, Math.min(12, p.getFloat("eqbass", 0)));
            try {
                org.json.JSONArray ba = new org.json.JSONArray(p.getString("eqbands", "[]"));
                for (int i = 0; i < 10 && i < ba.length(); i++) eqBands[i] = Math.max(-12, Math.min(12, (float) ba.optDouble(i, 0)));
            } catch (Throwable t) {}
        } catch (Throwable t) {}
    }

    static void touchJs() { lastJsCall = SystemClock.elapsedRealtime(); }
    static boolean jsRecent() { return SystemClock.elapsedRealtime() - lastJsCall < 3000; }
    static boolean playable(String u) {
        if (u == null || u.isEmpty()) return false;
        if (u.startsWith("content://")) return true;
        if (u.startsWith("blob:") || u.startsWith("data:")) return false;
        if (u.startsWith("http://localhost") || u.startsWith("https://localhost")) return false;
        if (u.contains("127.0.0.1")) return false;
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file:///");
    }

    static synchronized void ensurePlayer(Context c) {
        if (c != null) { try { appCtx = c.getApplicationContext(); } catch (Throwable t) {} }
        if (exo != null || appCtx == null) return;
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
            exo = new ExoPlayer.Builder(appCtx).build();
            exo.setAudioAttributes(aa, true);
            exo.setHandleAudioBecomingNoisy(true);
            try { loadEQ(); applyEq(); } catch (Throwable t) {}
            exo.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean playing) {
                    try { lastPlaying = playing; } catch (Throwable t) {}
                    try { applyEq(); } catch (Throwable t) {}
                    try { updateSession(); } catch (Throwable t) {}
                    try { refreshNotification(); } catch (Throwable t) {}
                    try {
                        ActionSink s = sink;
                        if (s != null) s.onEvent("state", playing ? 1 : 0);
                    } catch (Throwable t) {}
                    try {
                        if (loop == null) loop = new Handler(Looper.getMainLooper());
                        loop.removeCallbacks(loopTick);
                        if (playing) loop.postDelayed(loopTick, 5000);
                    } catch (Throwable t) {}
                }
                @Override public void onAudioSessionIdChanged(int audioSessionId) {
                    try { applyEq(); } catch (Throwable t) {}
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        try { onTrackEnded(); } catch (Throwable t) {}
                    }
                }
                @Override public void onPlayerError(PlaybackException error) {
                    // skip broken/unplayable item
                    try {
                        boolean recent = jsRecent();
                        if (recent) {
                            exo.seekToDefaultPosition();
                            exo.setPlayWhenReady(false);
                            ActionSink s = sink;
                            if (s != null) s.onEvent("ended", -1);
                        } else {
                            stepQueue(1, true);
                        }
                    } catch (Throwable t) {}
                }
            });
        } catch (Throwable t) {}
    }

    static void onTrackEnded() {
        if (exo == null) return;
        if (repMode == 2) {
            exo.seekTo(0);
            exo.setPlayWhenReady(true);
            return;
        }
        if (jsRecent()) {
            exo.seekToDefaultPosition();
            exo.setPlayWhenReady(false);
            ActionSink s = sink;
            if (s != null) s.onEvent("ended", -1);
        } else {
            stepQueue(1, true);
        }
    }

    // dir: +1 next, -1 prev. auto=true when advancing by itself (ended/error/skipped)
    static synchronized void stepQueue(int dir, boolean auto) {
        if (exo == null || queue.isEmpty()) {
            if (exo != null) { exo.setPlayWhenReady(false); }
            return;
        }
        int n = queue.size();
        int idx = qIndex;
        if (dir > 0) {
            int nxt = idx + 1;
            // skip unplayable
            int guard = 0;
            while (nxt < n && !playable(queue.get(nxt).uri) && guard++ < n) nxt++;
            if (nxt >= n) {
                if (repMode == 1) {
                    nxt = 0;
                    guard = 0;
                    while (nxt < n && !playable(queue.get(nxt).uri) && guard++ < n) nxt++;
                    if (nxt >= n) { exo.setPlayWhenReady(false); return; }
                } else {
                    exo.setPlayWhenReady(false);
                    refreshNotification();
                    return;
                }
            }
            idx = nxt;
        } else {
            try {
                if (exo.getCurrentPosition() > 3000) { exo.seekTo(0); refreshNotification(); return; }
            } catch (Throwable t) {}
            int prv = idx - 1;
            int guard = 0;
            while (prv >= 0 && !playable(queue.get(prv).uri) && guard++ < n) prv--;
            if (prv < 0) { exo.seekTo(0); refreshNotification(); fireTrack(); return; }
            idx = prv;
        }
        playIndex(idx, true);
    }

    static synchronized void playIndex(int idx, boolean autoplay) {
        if (exo == null || idx < 0 || idx >= queue.size()) return;
        Track t = queue.get(idx);
        if (!playable(t.uri)) {
            // jump over in the same direction
            qIndex = idx;
            stepQueue(1, true);
            return;
        }
        qIndex = idx;
        cur = t;
        try {
            exo.setMediaItem(MediaItem.fromUri(t.uri));
            exo.prepare();
            exo.seekTo(0);
            exo.setPlayWhenReady(autoplay);
            applyEq();
            refreshNotification();
            fireTrack();
            try { saveQueue(); } catch (Throwable th2) {}
        } catch (Throwable th) {}
    }

    static void fireTrack() {
        try {
            ActionSink s = sink;
            if (s != null) s.onEvent("track", qIndex);
        } catch (Throwable t) {}
    }

    // ---- swipe-proof queue persistence (statics die with the process) ----
    static final String QPREF = "cvetq";
    static android.content.SharedPreferences qprefs() {
        try { return appCtx.getSharedPreferences(QPREF, Context.MODE_PRIVATE); } catch (Throwable t) { return null; }
    }
    static synchronized void saveQueue() {
        try {
            android.content.SharedPreferences p = qprefs();
            if (p == null) return;
            JSONArray qa = new JSONArray();
            int cap = Math.min(queue.size(), 200);
            for (int i = 0; i < cap; i++) {
                try { Track t = queue.get(i); JSONObject o = new JSONObject(); o.put("u", t.uri); o.put("t", t.title); o.put("a", t.artist); qa.put(o); } catch (Throwable t) {}
            }
            long pos = 0;
            try { if (exo != null) pos = Math.max(0, exo.getCurrentPosition()); } catch (Throwable t) {}
            p.edit().putString("q", qa.toString()).putInt("i", qIndex).putInt("r", repMode).putBoolean("p", lastPlaying).putLong("pos", pos).apply();
        } catch (Throwable t) {}
    }
    static void savePos() {
        try {
            android.content.SharedPreferences p = qprefs();
            if (p == null || exo == null) return;
            p.edit().putLong("pos", Math.max(0, exo.getCurrentPosition())).putBoolean("p", exo.isPlaying()).apply();
        } catch (Throwable t) {}
    }
    static long savedPos() {
        try { android.content.SharedPreferences p = qprefs(); return p == null ? 0 : Math.max(0, p.getLong("pos", 0)); } catch (Throwable t) { return 0; }
    }
    static synchronized boolean restoreQueue() {
        try {
            android.content.SharedPreferences p = qprefs();
            if (p == null) return false;
            String s = p.getString("q", "");
            if (s == null || s.isEmpty()) return false;
            JSONArray qa = new JSONArray(s);
            if (qa.length() == 0) return false;
            queue.clear();
            for (int i = 0; i < qa.length(); i++) {
                try { JSONObject o = qa.getJSONObject(i); queue.add(new Track(o.optString("u", ""), o.optString("t", ""), o.optString("a", ""))); } catch (Throwable t) {}
            }
            if (queue.isEmpty()) return false;
            qIndex = Math.max(0, Math.min(p.getInt("i", 0), queue.size() - 1));
            repMode = p.getInt("r", 0);
            lastPlaying = p.getBoolean("p", false);
            try { cur = queue.get(qIndex); } catch (Throwable t) {}
            return true;
        } catch (Throwable t) { return false; }
    }
    static synchronized void resumeRestored() {
        try {
            if (exo == null || queue.isEmpty() || !lastPlaying || exo.isPlaying()) return;
            int idx = Math.max(0, Math.min(qIndex, queue.size() - 1));
            Track t = queue.get(idx);
            if (!playable(t.uri)) { stepQueue(1, true); return; }
            qIndex = idx;
            cur = t;
            exo.setMediaItem(MediaItem.fromUri(t.uri));
            exo.prepare();
            try { long sp = savedPos(); if (sp > 0) exo.seekTo(sp); } catch (Throwable th) {}
            exo.setPlayWhenReady(true);
            applyEq();
        } catch (Throwable t) {}
    }

    public static org.json.JSONObject audioStateJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            long[] pd = audioPos();
            o.put("playing", pd[2] == 1);
            o.put("pos", pd[0] / 1000.0);
            o.put("dur", pd[1] / 1000.0);
            o.put("uri", cur == null || cur.uri == null ? "" : cur.uri);
            o.put("index", qIndex);
            o.put("count", queue.size());
            o.put("repeat", repMode);
            org.json.JSONArray qa = new org.json.JSONArray();
            int cap = Math.min(queue.size(), 200);
            for (int i = 0; i < cap; i++) { try { qa.put(queue.get(i).uri); } catch (Throwable t) {} }
            o.put("queue", qa);
            return o;
        } catch (Throwable t) { return new org.json.JSONObject(); }
    }

    // ---- public audio API (called from plugin bridge) ----
    public static synchronized void audioPlay(Context c, String uri, String title, String artist, String acc,
                                              boolean autoplay, List<Track> q, int idx, int rep) {
        ensurePlayer(c);
        if (exo == null) return;
        if (acc != null && !acc.isEmpty()) lastAcc = acc;
        if (q != null) { queue.clear(); queue.addAll(q); qIndex = idx; }
        repMode = rep;
        cur = new Track(uri, title, artist);
        if (qIndex >= 0 && qIndex < queue.size()) {
            Track qt = queue.get(qIndex);
            if (qt.uri.equals(uri)) cur = qt;
        }
        if (!playable(uri)) { try { applyEq(); } catch (Throwable t) {} return; }
        try { lastPlaying = autoplay; } catch (Throwable t) {}
        try {
            exo.setMediaItem(MediaItem.fromUri(uri));
            exo.prepare();
            exo.seekTo(0);
            exo.setPlayWhenReady(autoplay);
            applyEq();
            refreshNotification();
            try { saveQueue(); } catch (Throwable t) {}
        } catch (Throwable t) {}
    }
    // true when ExoPlayer actually owns playback (native/cached track loaded).
    // Web-only tracks (blob/IDB) play in the WebView — notification buttons must be
    // forwarded to JS via fire() instead of poking an idle ExoPlayer.
    static boolean hasMedia() {
        try { return exo != null && exo.getCurrentMediaItem() != null; } catch (Throwable t) { return false; }
    }
    public static void audioToggle() {
        ensurePlayer(appCtx != null ? appCtx : null);
        if (exo == null) return;
        try {
            if (exo.isPlaying()) exo.pause(); else exo.play();
            try { lastPlaying = exo.isPlaying(); } catch (Throwable t) {}
            try { refreshNotification(); } catch (Throwable t) {}
        } catch (Throwable t) {}
    }
    public static void audioPause() { if (exo != null) { try { exo.pause(); } catch (Throwable t) {} } try { lastPlaying = false; } catch (Throwable t) {} }
    public static void audioResume() { if (exo != null) { try { exo.play(); } catch (Throwable t) {} } try { if (exo != null) lastPlaying = exo.isPlaying(); } catch (Throwable t) {} }
    public static void audioSeek(int sec) {
        if (exo == null) return;
        try {
            long d = exo.getDuration();
            long ms = Math.max(0, sec * 1000L);
            if (d > 0) ms = Math.min(ms, d);
            exo.seekTo(ms);
            try { savePos(); } catch (Throwable t) {}
            refreshNotification();
        } catch (Throwable t) {}
    }
    public static void audioNext() {
        ensurePlayer(appCtx);
        if (exo == null) return;
        if (queue.isEmpty() || qIndex < 0) { touchEnd(); return; }
        stepQueue(1, false);
    }
    public static void audioPrev() {
        ensurePlayer(appCtx);
        if (exo == null) return;
        if (queue.isEmpty() || qIndex < 0) { try { exo.seekTo(0); } catch (Throwable t) {} return; }
        stepQueue(-1, false);
    }
    static void touchEnd() {
        try {
            ActionSink s = sink;
            if (s != null) s.onEvent("ended", -1);
        } catch (Throwable t) {}
    }
    public static void audioStop(Context c) {
        try { lastPlaying = false; } catch (Throwable t) {}
        try { if (exo != null) exo.pause(); } catch (Throwable t) {}
        try { savePos(); } catch (Throwable t) {}
        try { if (loop != null) loop.removeCallbacks(loopTick); } catch (Throwable t) {}
        try { hide(c); } catch (Throwable t) {}
    }
    public static long[] audioPos() {
        if (exo == null) return new long[]{0, 0, 0};
        try {
            long p = Math.max(0, exo.getCurrentPosition());
            long d = exo.getDuration();
            if (d < 0) d = 0;
            return new long[]{p, d, exo.isPlaying() ? 1 : 0};
        } catch (Throwable t) { return new long[]{0, 0, 0}; }
    }
    public static boolean isPlayingNow() {
        try { return exo != null && exo.isPlaying(); } catch (Throwable t) { return false; }
    }
    public static void setEQ(boolean on, float[] bands, float bass) {
        eqOn = on;
        if (bands != null) {
            for (int i = 0; i < 10 && i < bands.length; i++) eqBands[i] = Math.max(-12, Math.min(12, bands[i]));
        }
        eqBass = Math.max(-12, Math.min(12, bass));
        try {
            android.content.SharedPreferences p = qprefs();
            if (p != null) {
                org.json.JSONArray ba = new org.json.JSONArray();
                for (int i = 0; i < 10; i++) { try { ba.put(eqBands[i]); } catch (Throwable t) {} }
                p.edit().putBoolean("eqon", eqOn).putFloat("eqbass", eqBass).putString("eqbands", ba.toString()).apply();
            }
        } catch (Throwable t) {}
        try { applyEq(); } catch (Throwable t) {}
    }
    static float interpBand(float f) {
        if (f <= EQ_C[0]) return eqBands[0];
        for (int i = 1; i < 10; i++) {
            if (f <= EQ_C[i]) {
                double lf = Math.log(f / EQ_C[i - 1]) / Math.log(EQ_C[i] / EQ_C[i - 1]);
                return (float)(eqBands[i - 1] + lf * (eqBands[i] - eqBands[i - 1]));
            }
        }
        return eqBands[9];
    }
    static synchronized void applyEq() {
        if (exo == null) return;
        try { exo.setVolume(eqOn ? (float)Math.pow(10, -Math.max(0, Math.min(12, eqBass)) / 20.0) : 1f); } catch (Throwable t) {}
        int sid;
        try { sid = exo.getAudioSessionId(); } catch (Throwable t) { return; }
        if (sid == 0 || sid == C.AUDIO_SESSION_ID_UNSET) return;
        try {
            if (sysEq == null || eqSid != sid) {
                if (sysEq != null) { try { sysEq.release(); } catch (Throwable t) {} sysEq = null; }
                sysEq = new Equalizer(0, sid);
                eqSid = sid;
            }
            sysEq.setEnabled(false);
            short nb = sysEq.getNumberOfBands();
            short[] range = sysEq.getBandLevelRange();
            short lo = range[0], hi = range[1];
            for (short i = 0; i < nb; i++) {
                float cf;
                try { cf = sysEq.getCenterFreq(i) / 1000f; } catch (Throwable t) { cf = 1000f; }
                float v = interpBand(cf) + (cf <= 150 ? eqBass * 0.6f : 0);
                v = Math.max(-12, Math.min(12, v));
                short lvl = (short)(v * 100);
                if (lvl < lo) lvl = lo;
                if (lvl > hi) lvl = hi;
                try { sysEq.setBandLevel(i, lvl); } catch (Throwable t) {}
            }
            sysEq.setEnabled(eqOn);
        } catch (Throwable t) {}
    }

    // ---- service / notification ----
    private MediaSessionCompat session;
    private PowerManager.WakeLock wl;

    static void fire(String act, long posMs) {
        try {
            ActionSink s = sink;
            if (s != null) s.onEvent(act, posMs);
        } catch (Throwable t) {}
    }

    public static void show(Context c, String title, String artist, boolean playing, long posMs, long durMs,
                            String acc, int fav, String letter, String artUri, String pkg) {
        Context ctx = c.getApplicationContext();
        appCtx = ctx;
        try { lastPlaying = playing; } catch (Throwable t) {}
        if (acc != null && !acc.isEmpty()) lastAcc = acc;
        lastFav = fav == 1;
        lastLetter = (letter == null || letter.isEmpty()) ? "?" : letter;
        if (title != null && !title.isEmpty()) {
            if (!title.equals(cur.title) || (artist != null && !artist.equals(cur.artist))) {
                cur = new Track(cur.uri, title, artist == null ? "" : artist);
            }
            if (lastLetter.equals("?")) {
                try { lastLetter = title.trim().substring(0, 1).toUpperCase(); } catch (Throwable t) { lastLetter = "?"; }
            }
        }
        try { ensurePlayer(ctx); } catch (Throwable t) {}
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "Player", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Music controls");
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg == null || pkg.isEmpty() ? ctx.getPackageName() : pkg);
            if (i == null) i = new Intent(ctx, Class.forName(ctx.getPackageName() + ".MainActivity"));
            i.setAction(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent ci = PendingIntent.getActivity(ctx, 100, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = buildFull(ctx, ci, title, artist, playing, posMs, durMs, lastAcc, lastFav ? 1 : 0, lastLetter, artUri);
            try { nm.notify(NID, n); } catch (Throwable t) {}
            try {
                Intent fs = new Intent(ctx, PlayerService.class);
                fs.putExtra("n_title", title); fs.putExtra("n_artist", artist);
                fs.putExtra("n_playing", playing); fs.putExtra("n_pos", posMs); fs.putExtra("n_dur", durMs);
                fs.putExtra("n_acc", lastAcc); fs.putExtra("n_fav", lastFav ? 1 : 0);
                fs.putExtra("n_letter", lastLetter); fs.putExtra("n_art", artUri == null ? "" : artUri);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(fs);
                else ctx.startService(fs);
            } catch (Throwable t) {}
        } catch (Throwable t) {}
    }

    public static void hide(Context c) {
        try { lastPlaying = false; } catch (Throwable t) {}
        try {
            Context ctx = c.getApplicationContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            try { nm.cancel(NID); } catch (Throwable t) {}
            try { ctx.stopService(new Intent(ctx, PlayerService.class)); } catch (Throwable t) {}
        } catch (Throwable t) {}
    }

    static void refreshNotification() {
        if (appCtx == null) return;
        try {
            long[] pd = audioPos();
            boolean playing = pd[2] == 1;
            NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent i = appCtx.getPackageManager().getLaunchIntentForPackage(appCtx.getPackageName());
            if (i == null) i = new Intent(appCtx, Class.forName(appCtx.getPackageName() + ".MainActivity"));
            i.setAction(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent ci = PendingIntent.getActivity(appCtx, 100, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = buildFull(appCtx, ci, cur.title, cur.artist, playing, pd[0], pd[1], lastAcc, lastFav ? 1 : 0, lastLetter, "");
            nm.notify(NID, n);
            updateSession();
        } catch (Throwable t) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { ensurePlayer(this); } catch (Throwable t) {}
        try { if (queue.isEmpty()) restoreQueue(); } catch (Throwable t) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "Player", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Music controls");
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {}
        session = new MediaSessionCompat(this, "cvetochek");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (!hasMedia()) { fire("toggle", -1); return; } audioResume(); afterNative("state"); }
            @Override public void onPause() { if (!hasMedia()) { fire("toggle", -1); return; } audioPause(); afterNative("state"); }
            @Override public void onSkipToNext() { if (!hasMedia()) { fire("next", -1); return; } audioNext(); }
            @Override public void onSkipToPrevious() { if (!hasMedia()) { fire("prev", -1); return; } audioPrev(); }
            @Override public void onStop() { audioStop(PlayerService.this); fire("stop", -1); }
            @Override public void onSeekTo(long pos) { if (!hasMedia()) { fire("seekto", pos); return; } audioSeek((int)(pos / 1000)); }
        });
        session.setActive(true);
        try { sessionToken = session.getSessionToken(); } catch (Throwable t) {}
        try { sessRef = session; } catch (Throwable t) {}
        try {
            wl = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cvetochek:audio");
            wl.setReferenceCounted(false);
        } catch (Throwable t) {}
    }

    static void afterNative(String act) {
        try { refreshNotification(); } catch (Throwable t) {}
        if ("state".equals(act)) fire("state", isPlayingNow() ? 1 : 0);
    }

    static void updateSession() {
        try {
            if (sessRef == null) return;
            long[] pd = audioPos();
            boolean playing = pd[2] == 1;
            String title = "", artist = "";
            try { if (cur != null) { title = cur.title == null ? "" : cur.title; artist = cur.artist == null ? "" : cur.artist; } } catch (Throwable t) {}
            MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, pd[1]));
            sessRef.setMetadata(mb.build());
            int st = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            float sp = playing ? 1f : 0f;
            PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP)
                .setState(st, Math.max(0, pd[0]), sp);
            sessRef.setPlaybackState(pb.build());
        } catch (Throwable t) {}
    }

    private void syncSession(boolean playing, long posMs, long durMs, String title, String artist) {
        try {
            MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title == null ? "" : title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist == null ? "" : artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, durMs));
            session.setMetadata(mb.build());
            int st = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            float sp = playing ? 1f : 0f;
            PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP)
                .setState(st, Math.max(0, posMs), sp);
            session.setPlaybackState(pb.build());
        } catch (Throwable t) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { ensurePlayer(this); } catch (Throwable t) {}
        try { if (queue.isEmpty()) restoreQueue(); } catch (Throwable t) {}
        try { if (intent == null || intent.getStringExtra(ACT) == null) resumeRestored(); } catch (Throwable t) {}
        String title = null, artist = null, acc = null, letter = null, art = null;
        boolean playing = true; long pos = 0, dur = 0; int fav = 0;
        if (intent != null) {
            String act = intent.getStringExtra(ACT);
            if (act != null) {
                switch (act) {
                    case "toggle": if (!hasMedia()) { fire("toggle", -1); return START_STICKY; } audioToggle(); afterNative("state"); return START_STICKY;
                    case "next": if (!hasMedia()) { fire("next", -1); return START_STICKY; } audioNext(); return START_STICKY;
                    case "prev": if (!hasMedia()) { fire("prev", -1); return START_STICKY; } audioPrev(); return START_STICKY;
                    case "fav": fire("fav", -1); return START_STICKY;
                    case "seekto": fire("seekto", -1); return START_STICKY;
                    case "stop": audioStop(this); fire("stop", -1); return START_NOT_STICKY;
                }
            }
            title = intent.getStringExtra("n_title"); artist = intent.getStringExtra("n_artist");
            playing = intent.getBooleanExtra("n_playing", true);
            pos = intent.getLongExtra("n_pos", 0); dur = intent.getLongExtra("n_dur", 0);
            acc = intent.getStringExtra("n_acc"); fav = intent.getIntExtra("n_fav", 0);
            letter = intent.getStringExtra("n_letter"); art = intent.getStringExtra("n_art");
        }
        try {
            // prefer live engine state over the stale intent snapshot
            if (exo != null) {
                long[] pd = audioPos();
                playing = pd[2] == 1;
                pos = pd[0]; dur = pd[1];
            }
            if (title == null) title = cur.title;
            if (artist == null) artist = cur.artist;
            if (acc == null) acc = lastAcc;
            if (letter == null) letter = lastLetter;
            if (art == null) art = "";
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i == null) i = new Intent(this, Class.forName(getPackageName() + ".MainActivity"));
            i.setAction(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent ci = PendingIntent.getActivity(this, 100, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = buildFull(this, ci, title, artist, playing, pos, dur, acc, fav, letter, art);
            if (Build.VERSION.SDK_INT >= 29) {
                try { startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); }
                catch (Throwable t) { try { startForeground(NID, n); } catch (Throwable t2) {} }
            } else {
                try { startForeground(NID, n); } catch (Throwable t) {}
            }
            try {
                if (wl != null && !wl.isHeld()) wl.acquire(10 * 60 * 1000L);
            } catch (Throwable t) {}
            syncSession(playing, pos, dur, title, artist);
        } catch (Throwable t) {}
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            boolean playingNow = false;
            try { playingNow = (exo != null && exo.isPlaying()) || lastPlaying; } catch (Throwable t) {}
            if (playingNow) {
                Intent r = new Intent(getApplicationContext(), PlayerService.class);
                if (Build.VERSION.SDK_INT >= 26) getApplicationContext().startForegroundService(r);
                else getApplicationContext().startService(r);
            }
        } catch (Throwable t) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { if (loop != null) loop.removeCallbacks(loopTick); } catch (Throwable t) {}
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Throwable t) {}
        try { sessRef = null; } catch (Throwable t) {}
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static Notification buildFull(Context ctx, PendingIntent ci, String title, String artist, boolean playing,
                                  long posMs, long durMs, String acc, int fav, String letter, String artUri) {
        String tt = (title == null || title.isEmpty()) ? "—" : title;
        String ar = (artist == null || artist.isEmpty()) ? ctx.getPackageName() : artist;
        Bitmap art = loadArt(artUri, tt, ar, acc, letter);
        MediaStyle ms = new MediaStyle().setShowActionsInCompactView(1, 2, 3);
        try { if (sessionToken != null) ms.setMediaSession(sessionToken); } catch (Throwable t) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH)
            .setContentTitle(tt)
            .setContentText(ar)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(ci)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setDeleteIntent(pi(ctx, "stop"))
            .setStyle(ms);
        if (art != null) b.setLargeIcon(art);
        b.addAction(new NotificationCompat.Action.Builder(android.R.drawable.ic_media_previous, "prev", pi(ctx, "prev")).build());
        b.addAction(new NotificationCompat.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "tog", pi(ctx, "toggle")).build());
        b.addAction(new NotificationCompat.Action.Builder(android.R.drawable.ic_media_next, "next", pi(ctx, "next")).build());
        b.addAction(new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "stop", pi(ctx, "stop")).build());
        return b.build();
    }

    static PendingIntent pi(Context ctx, String act) {
        Intent i = new Intent(ctx, PlayerService.class);
        i.putExtra(ACT, act);
        int req = 1 + act.hashCode();
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        try {
            if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(ctx, req, i, fl);
        } catch (Throwable t) {}
        return PendingIntent.getService(ctx, req, i, fl);
    }

    static Bitmap loadArt(String artUri, String title, String artist, String acc, String letter) {
        try {
            if (appCtx != null && artUri != null && !artUri.isEmpty() && (artUri.startsWith("content://") || artUri.startsWith("file://"))) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try {
                    mmr.setDataSource(appCtx != null ? appCtx : null, Uri.parse(artUri));
                    byte[] emb = mmr.getEmbeddedPicture();
                    if (emb != null && emb.length > 0) {
                        Bitmap bm = BitmapFactory.decodeByteArray(emb, 0, emb.length);
                        if (bm != null) return bm;
                    }
                } catch (Throwable t) {} finally { try { mmr.release(); } catch (Throwable t) {} }
            }
        } catch (Throwable t) {}
        return letterArt(title, acc, letter);
    }

    static Bitmap letterArt(String title, String acc, String letter) {
        try {
            int S = 256;
            Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            int col = Color.parseColor("#e91e63");
            try { col = Color.parseColor(acc); } catch (Throwable t) {}
            c.drawColor(col);
            Paint dk = new Paint();
            dk.setColor(Color.argb(90, 0, 0, 0));
            c.drawRect(new Rect(0, S / 2, S, S), dk);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(150);
            p.setTextAlign(Paint.Align.CENTER);
            String L = (letter == null || letter.isEmpty()) ? "?" : letter.substring(0, 1).toUpperCase();
            c.drawText(L, S / 2f, S / 2f + 55, p);
            return bm;
        } catch (Throwable t) { return null; }
    }
}

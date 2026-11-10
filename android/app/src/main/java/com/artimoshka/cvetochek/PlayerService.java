package com.artimoshka.cvetochek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Фоновое воспроизведение: ExoPlayer + шторка + восстановление после свайпа.
 */
public class PlayerService extends Service {

    static PlayerCore core = null;

    private static final String CH = "cvet.playback";
    private static final int NOTIF_ID = 7;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CH, "Воспроизведение",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Плеер Цветочек");
                nm.createNotificationChannel(ch);
            }
        } catch (Exception ignored) {}
        if (core == null) core = new PlayerCore(this);
        core.restore();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null) {
                String a = intent.getAction();
                if ("CMD".equals(a)) {
                    handleCmd(intent.getStringExtra("cmd"));
                } else if ("RESTORE".equals(a)) {
                    if (core != null) core.restore();
                }
            }
        } catch (Exception ignored) {}
        return START_STICKY;
    }

    private void handleCmd(String cmd) {
        if (cmd == null || core == null) return;
        try {
            boolean js = MusicScannerPlugin.active && MusicScannerPlugin.uiAlive;
            switch (cmd) {
                case "toggle":
                    if (js) MusicScannerPlugin.sendEvent("toggle", null);
                    else { core.toggle(); }
                    break;
                case "next":
                    if (js) MusicScannerPlugin.sendEvent("next", null);
                    else { core.next(); }
                    break;
                case "prev":
                    if (js) MusicScannerPlugin.sendEvent("prev", null);
                    else { core.prev(); }
                    break;
                case "fav":
                    MusicScannerPlugin.sendEvent("fav", null);
                    break;
                case "stop":
                    core.stopAll();
                    MusicScannerPlugin.sendEvent("stop", null);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Свайп: если играло — перезапустить сервис, очередь подхватится из cvetq
        try {
            if (core != null && core.isPlaying()) {
                Intent i = new Intent(this, PlayerService.class);
                i.setAction("RESTORE");
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        try {
            if (core != null) core.release();
        } catch (Exception ignored) {}
        core = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================= PlayerCore =================

    static class PlayerCore {
        final Service svc;
        ExoPlayer player;
        final List<String> uris = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<String> artists = new ArrayList<>();
        int repeat = 0;
        String acc = "#0b84ff";
        String lastTitle = "\u2014";
        String lastArtist = "";
        String lastLetter = "\u266A";
        boolean fg = false;
        MediaSessionCompat session;
        Equalizer eq = null;
        int eqSession = 0;
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable persistRun = new Runnable() {
            @Override public void run() {
                try { persist(); } catch (Exception ignored) {}
                try { h.postDelayed(this, 5000); } catch (Exception ignored) {}
            }
        };

        PlayerCore(Service svc) {
            this.svc = svc;
            try {
                player = new ExoPlayer.Builder(svc)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(), true)
                        .setHandleAudioBecomingNoisy(true)
                        .build();
                player.addListener(new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(MediaItem item, int reason) {
                        try {
                            syncMetaFromQueue();
                            updateNotif();
                            persist();
                            MusicScannerPlugin.sendEvent("track", player.getCurrentMediaItemIndex());
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onIsPlayingChanged(boolean p) {
                        try {
                            updateNotif();
                            persist();
                            MusicScannerPlugin.sendEvent("state", p ? 1 : 0);
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onPlaybackStateChanged(int st) {
                        try {
                            if (st == Player.STATE_READY) applyEQ();
                            if (st == Player.STATE_ENDED) {
                                updateNotif();
                                persist();
                                MusicScannerPlugin.sendEvent("ended", null);
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        try {
                            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
                            else {
                                try { player.stop(); } catch (Exception ignored) {}
                                updateNotif();
                            }
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
            try {
                session = new MediaSessionCompat(svc, "cvetochek");
                session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
                session.setCallback(new MediaSessionCompat.Callback() {
                    @Override public void onPlay() { resumeLocal(); }
                    @Override public void onPause() { pauseLocal(); }
                    @Override public void onSkipToNext() { nextLocal(); }
                    @Override public void onSkipToPrevious() { prevLocal(); }
                    @Override public void onSeekTo(long pos) { seekLocal(pos); }
                    @Override public void onStop() {
                        try { stopAll(); } catch (Exception ignored) {}
                        MusicScannerPlugin.sendEvent("stop", null);
                    }
                });
                session.setActive(true);
            } catch (Exception ignored) {}
            try { h.postDelayed(persistRun, 5000); } catch (Exception ignored) {}
        }

        // ----- очередь -----

        int repMode(int r) {
            if (r == 2) return Player.REPEAT_MODE_ONE;
            if (r == 1) return Player.REPEAT_MODE_ALL;
            return Player.REPEAT_MODE_OFF;
        }

        void setQueue(List<String> u, List<String> t, List<String> a,
                      int index, int rep, boolean autoplay,
                      String title, String artist, String accCol) {
            try {
                uris.clear(); titles.clear(); artists.clear();
                if (u != null) {
                    for (int i = 0; i < u.size(); i++) {
                        String uu = u.get(i);
                        if (uu == null || uu.isEmpty()) continue;
                        uris.add(uu);
                        titles.add(i < t.size() ? t.get(i) : title);
                        artists.add(i < a.size() ? a.get(i) : artist);
                    }
                }
                repeat = rep;
                acc = accCol != null ? accCol : acc;
                if (uris.isEmpty()) return;
                int idx = Math.max(0, Math.min(index, uris.size() - 1));
                List<MediaItem> items = new ArrayList<>();
                for (int i = 0; i < uris.size(); i++) {
                    items.add(new MediaItem.Builder()
                            .setUri(uris.get(i))
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle(titles.get(i))
                                    .setArtist(artists.get(i))
                                    .build())
                            .build());
                }
                player.setRepeatMode(repMode(repeat));
                player.setMediaItems(items, idx, C.TIME_UNSET);
                player.setPlayWhenReady(autoplay);
                player.prepare();
                syncMetaFromQueue();
                ensureForeground();
                updateNotif();
                applyEQ();
                persist();
            } catch (Exception ignored) {}
        }

        void syncMetaFromQueue() {
            try {
                int i = player.getCurrentMediaItemIndex();
                if (i >= 0 && i < titles.size()) {
                    lastTitle = titles.get(i);
                    lastArtist = artists.get(i);
                    lastLetter = lastTitle.isEmpty() ? "\u266A" : lastTitle.substring(0, 1);
                }
            } catch (Exception ignored) {}
        }

        // ----- транспорт -----

        boolean toggle() {
            try {
                boolean p = player.isPlaying();
                player.setPlayWhenReady(!p);
                return !p;
            } catch (Exception e) {
                return false;
            }
        }

        void pause() { try { player.setPlayWhenReady(false); } catch (Exception ignored) {} }
        void resume() { try { player.setPlayWhenReady(true); } catch (Exception ignored) {} }

        void next() {
            try {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem();
            } catch (Exception ignored) {}
        }

        void prev() {
            try {
                if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            } catch (Exception ignored) {}
        }

        void seekToSec(int sec) {
            try { player.seekTo(sec * 1000L); } catch (Exception ignored) {}
        }

        boolean isPlaying() {
            try { return player.isPlaying(); } catch (Exception e) { return false; }
        }

        double posSec() {
            try {
                long p = player.getCurrentPosition();
                return p < 0 ? 0 : p / 1000.0;
            } catch (Exception e) {
                return 0;
            }
        }

        double durSec() {
            try {
                long d = player.getDuration();
                return (d < 0 || d == C.TIME_UNSET) ? 0 : d / 1000.0;
            } catch (Exception e) {
                return 0;
            }
        }

        String currentUri() {
            try {
                int i = player.getCurrentMediaItemIndex();
                if (i >= 0 && i < uris.size()) return uris.get(i);
            } catch (Exception ignored) {}
            return null;
        }

        int currentIndex() {
            try { return Math.max(0, player.getCurrentMediaItemIndex()); }
            catch (Exception e) { return 0; }
        }

        void stopAll() {
            try { player.setPlayWhenReady(false); } catch (Exception ignored) {}
            try { player.stop(); } catch (Exception ignored) {}
            try {
                svc.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {}
            fg = false;
            try { persist(); } catch (Exception ignored) {}
        }

        // локальные действия (когда JS мёртв): делаем сами + шлём события
        void resumeLocal() {
            try {
                resume();
                MusicScannerPlugin.sendEvent("state", 1);
            } catch (Exception ignored) {}
        }
        void pauseLocal() {
            try {
                pause();
                MusicScannerPlugin.sendEvent("state", 0);
            } catch (Exception ignored) {}
        }
        void nextLocal() {
            try {
                next();
                MusicScannerPlugin.sendEvent("track", currentIndex());
            } catch (Exception ignored) {}
        }
        void prevLocal() {
            try {
                prev();
                MusicScannerPlugin.sendEvent("track", currentIndex());
            } catch (Exception ignored) {}
        }
        void seekLocal(long posMs) {
            try {
                player.seekTo(Math.max(0, posMs));
                updateNotif();
            } catch (Exception ignored) {}
        }

        // ----- sync из веба -----

        void onSync(String title, String artist, String accCol, String letter,
                    Boolean playing, double pos, double dur) {
            try {
                lastTitle = title;
                lastArtist = artist;
                if (accCol != null) acc = accCol;
                if (letter != null && !letter.isEmpty()) lastLetter = letter;
                updateNotif();
            } catch (Exception ignored) {}
        }

        // ----- EQ -----

        void applyEQ() {
            if (player == null) return;
            boolean on = false;
            float bass = 0;
            try {
                SharedPreferences p = svc.getSharedPreferences("cveq", Context.MODE_PRIVATE);
                on = p.getBoolean("on", false);
                bass = p.getFloat("bass", 0);
            } catch (Exception ignored) {}
            bass = Math.max(-12, Math.min(12, bass));
            try {
                int sid = player.getAudioSessionId();
                if (sid != 0 && sid != C.AUDIO_SESSION_ID_UNSET) {
                    if (eq == null || eqSession != sid) {
                        if (eq != null) {
                            try { eq.release(); } catch (Exception ignored) {}
                        }
                        eq = new Equalizer(0, sid);
                        eqSession = sid;
                        eq.setEnabled(true);
                    }
                    short bands = eq.getNumberOfBands();
                    short[] range = eq.getBandLevelRange();
                    for (short b = 0; b < bands; b++) {
                        int fc;
                        try { fc = eq.getCenterFreq(b) / 1000; } catch (Exception e) { fc = 1000; }
                        short lvl = 0;
                        if (on && fc < 250) {
                            // бас x0.6, кламп ±12 (в нативе нет лимитера — душим мягко)
                            float mb = bass * 60f;
                            mb = Math.max(range[0], Math.min(range[1], mb));
                            lvl = (short) Math.round(mb);
                        }
                        try { eq.setBandLevel(b, lvl); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            try {
                // антихрип: предусиление гасится громкостью
                float vol = on ? (float) Math.pow(10.0, -Math.max(0, bass) / 20.0) : 1f;
                player.setVolume(Math.max(0.05f, Math.min(1f, vol)));
            } catch (Exception ignored) {}
        }

        // ----- персист cvetq -----

        void persist() {
            try {
                JSONObject o = new JSONObject();
                JSONArray arr = new JSONArray();
                for (String u : uris) arr.put(u);
                o.put("uris", arr);
                o.put("index", currentIndex());
                o.put("repeat", repeat);
                o.put("pos", (int) posSec());
                o.put("playing", isPlaying());
                svc.getSharedPreferences("cvetq", Context.MODE_PRIVATE)
                        .edit().putString("q", o.toString()).apply();
            } catch (Exception ignored) {}
        }

        void restore() {
            try {
                if (!uris.isEmpty() && player.getMediaItemCount() > 0) return;
                SharedPreferences p = svc.getSharedPreferences("cvetq", Context.MODE_PRIVATE);
                String s = p.getString("q", null);
                if (s == null) return;
                JSONObject o = new JSONObject(s);
                JSONArray arr = o.optJSONArray("uris");
                if (arr == null || arr.length() == 0) return;
                List<String> u = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) u.add(arr.optString(i));
                if (u.isEmpty()) return;
                int idx = Math.max(0, Math.min(o.optInt("index", 0), u.size() - 1));
                int pos = Math.max(0, o.optInt("pos", 0));
                boolean playing = o.optBoolean("playing", false);
                int rep = o.optInt("repeat", 0);
                uris.clear(); titles.clear(); artists.clear();
                uris.addAll(u);
                for (String uu : u) {
                    String nm = uu.substring(uu.lastIndexOf('/') + 1);
                    titles.add(nm.isEmpty() ? "\u2014" : nm);
                    artists.add("");
                }
                repeat = rep;
                List<MediaItem> items = new ArrayList<>();
                for (String uu : uris) items.add(new MediaItem.Builder().setUri(uu).build());
                player.setRepeatMode(repMode(repeat));
                player.setMediaItems(items, idx, pos * 1000L);
                player.setPlayWhenReady(playing);
                player.prepare();
                syncMetaFromQueue();
                ensureForeground();
                updateNotif();
                applyEQ();
            } catch (Exception ignored) {}
        }

        void release() {
            try { h.removeCallbacks(persistRun); } catch (Exception ignored) {}
            try { persist(); } catch (Exception ignored) {}
            try {
                if (eq != null) { eq.release(); eq = null; }
            } catch (Exception ignored) {}
            try {
                if (session != null) { session.setActive(false); session.release(); session = null; }
            } catch (Exception ignored) {}
            try {
                if (player != null) { player.release(); player = null; }
            } catch (Exception ignored) {}
        }

        // ----- шторка -----

        void ensureForeground() {
            if (fg) return;
            try {
                Notification n = buildNotif();
                if (Build.VERSION.SDK_INT >= 29) {
                    svc.startForeground(NOTIF_ID, n,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    svc.startForeground(NOTIF_ID, n);
                }
                fg = true;
            } catch (Exception ignored) {}
        }

        PendingIntent cmdIntent(String cmd, int code) {
            try {
                Intent i = new Intent(svc, PlayerService.class);
                i.setAction("CMD");
                i.putExtra("cmd", cmd);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
                return PendingIntent.getForegroundService(svc, code, i, fl);
            } catch (Exception e) {
                return null;
            }
        }

        static String fmt(double sec) {
            try {
                long s = Math.max(0, (long) sec);
                return (s / 60) + ":" + String.format("%02d", s % 60);
            } catch (Exception e) {
                return "0:00";
            }
        }

        Notification buildNotif() {
            boolean playing = isPlaying();
            double pos = posSec();
            double dur = durSec();
            Bitmap art = MusicScannerPlugin.makeArt(acc,
                    lastLetter != null ? lastLetter : (lastTitle.isEmpty() ? "\u266A" : lastTitle.substring(0, 1)));
            NotificationCompat.Builder b = new NotificationCompat.Builder(svc, CH)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(lastTitle)
                    .setContentText(lastArtist + (dur > 0 ? " \u2022 " + fmt(pos) + " / " + fmt(dur) : ""))
                    .setLargeIcon(art)
                    .setOngoing(playing)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setDeleteIntent(cmdIntent("stop", 90));
            try {
                Intent open = new Intent(svc, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int fl = 0;
                if (Build.VERSION.SDK_INT >= 23) fl = PendingIntent.FLAG_IMMUTABLE;
                b.setContentIntent(PendingIntent.getActivity(svc, 91, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | fl));
            } catch (Exception ignored) {}
            try {
                PendingIntent pPrev = cmdIntent("prev", 92);
                PendingIntent pTog = cmdIntent("toggle", 93);
                PendingIntent pNext = cmdIntent("next", 94);
                PendingIntent pFav = cmdIntent("fav", 95);
                PendingIntent pStop = cmdIntent("stop", 96);
                if (pPrev != null) b.addAction(android.R.drawable.ic_media_previous, "prev", pPrev);
                if (pTog != null) b.addAction(playing
                        ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        "toggle", pTog);
                if (pNext != null) b.addAction(android.R.drawable.ic_media_next, "next", pNext);
                if (pFav != null) b.addAction(android.R.drawable.btn_star_big_on, "fav", pFav);
                if (pStop != null) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "stop", pStop);
                b.setStyle(new MediaStyle()
                        .setMediaSession(session != null ? session.getSessionToken() : null)
                        .setShowActionsInCompactView(0, 1, 2));
            } catch (Exception ignored) {}
            // MediaSession — мета и состояние для шторки и BT-гарнитур
            try {
                if (session != null) {
                    MediaMetadataCompat md = new MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, lastTitle)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist)
                            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long) (dur * 1000))
                            .build();
                    session.setMetadata(md);
                    PlaybackStateCompat st = new PlaybackStateCompat.Builder()
                            .setActions(PlaybackStateCompat.ACTION_PLAY
                                    | PlaybackStateCompat.ACTION_PAUSE
                                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                                    | PlaybackStateCompat.ACTION_SEEK_TO
                                    | PlaybackStateCompat.ACTION_STOP)
                            .setState(playing ? PlaybackStateCompat.STATE_PLAYING
                                    : PlaybackStateCompat.STATE_PAUSED,
                                    (long) (pos * 1000), playing ? 1f : 0f)
                            .build();
                    session.setPlaybackState(st);
                }
            } catch (Exception ignored) {}
            return b.build();
        }

        void updateNotif() {
            try {
                if (!fg) {
                    // Шторка нужна только когда есть что играть
                    if (uris.isEmpty() && player.getMediaItemCount() == 0) return;
                    ensureForeground();
                    return;
                }
                NotificationManager nm =
                        (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, buildNotif());
            } catch (Exception ignored) {}
        }
    }
}

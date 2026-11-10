package com.artimoshka.cvetochek;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Мост JS <-> натив. Имя плагина в JS: Capacitor.Plugins.MusicScanner.
 */
@CapacitorPlugin(name = "MusicScanner")
public class MusicScannerPlugin extends Plugin {

    static MusicScannerPlugin instance = null;
    /** true, пока жив BridgeActivity (умирает при свайпе из шторки задач). */
    static boolean active = false;
    /** true, когда веб-плеер вызывал звуковые методы в этой жизни активити. */
    static boolean uiAlive = false;

    private final Map<String, RandomAccessFile> openFiles = new HashMap<>();

    static void sendEvent(String action, Integer pos) {
        try {
            if (instance == null) return;
            JSObject o = new JSObject();
            o.put("action", action);
            if (pos != null) o.put("pos", pos);
            instance.notifyListeners("playerAction", o);
        } catch (Exception ignored) {}
    }

    @Override
    public void load() {
        instance = this;
        active = true;
    }

    @Override
    protected void handleOnDestroy() {
        active = false;
        uiAlive = false;
        try {
            for (RandomAccessFile f : openFiles.values()) {
                try { f.close(); } catch (Exception ignored) {}
            }
            openFiles.clear();
        } catch (Exception ignored) {}
    }

    // ---------- helpers ----------

    private static String str(PluginCall c, String k, String def) {
        try {
            String v = c.getString(k);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOr(PluginCall c, String k, int def) {
        try {
            Integer v = c.getInt(k);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String curStr(Cursor c, int i, String def) {
        try {
            String v = c.getString(i);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private boolean hasAudioPerm() {
        try {
            Context ctx = getContext();
            if (Build.VERSION.SDK_INT >= 33) {
                return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureService() {
        try {
            Intent i = new Intent(getContext(), PlayerService.class);
            i.setAction("INIT");
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
            else getContext().startService(i);
            long t = System.currentTimeMillis();
            while (PlayerService.core == null && System.currentTimeMillis() - t < 3000) {
                try { Thread.sleep(50); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static Bitmap appIcon(Context ctx) {
        try {
            if (ctx == null) return null;
            int id = ctx.getResources().getIdentifier("ic_launcher", "mipmap", ctx.getPackageName());
            if (id == 0) return null;
            Bitmap b = android.graphics.BitmapFactory.decodeResource(ctx.getResources(), id);
            if (b == null) return null;
            try { return Bitmap.createScaledBitmap(b, 256, 256, true); }
            catch (Exception e) { return b; }
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap makeArt(String acc, String letter) {
        try {
            int col;
            try { col = Color.parseColor(acc); } catch (Exception e) { col = 0xFF0B84FF; }
            Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.drawColor(col);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setTextSize(150);
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            String L = (letter == null || letter.isEmpty()) ? "\u266A" : letter.substring(0, 1).toUpperCase();
            cv.drawText(L, 128, 178, p);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- plugin methods (имена должны совпадать с вызовами в index.html) ----------

    @PluginMethod
    public void scan(PluginCall call) {
        try {
            JSArray arr = new JSArray();
            if (hasAudioPerm()) {
                String[] proj = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE
                };
                try (Cursor c = getContext().getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                        MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                        MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                    if (c != null) {
                        int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int iAr = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                        int iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                        while (c.moveToNext()) {
                            long id = c.getLong(iId);
                            Uri u = ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            JSObject m = new JSObject();
                            m.put("id", id);
                            m.put("uri", u.toString());
                            m.put("title", curStr(c, iT, "Unknown"));
                            m.put("artist", curStr(c, iAr, "Unknown"));
                            m.put("album", curStr(c, iAl, ""));
                            long dur = 0;
                            try { dur = c.getLong(iD); } catch (Exception ignored) {}
                            m.put("durMs", dur);
                            m.put("dur", dur / 1000.0);
                            long size = 0;
                            try { size = c.getLong(iS); } catch (Exception ignored) {}
                            m.put("size", size);
                            arr.put(m);
                        }
                    }
                }
            }
            JSObject r = new JSObject();
            r.put("tracks", arr);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void requestNotifs(PluginCall call) {
        boolean granted = false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                granted = ContextCompat.checkSelfPermission(getContext(),
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                if (!granted) {
                    try {
                        ActivityCompat.requestPermissions(getActivity(),
                                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7701);
                    } catch (Exception ignored) {}
                }
            } else {
                granted = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
            }
        } catch (Exception ignored) {}
        JSObject r = new JSObject();
        r.put("granted", granted);
        call.resolve(r);
    }

    @PluginMethod
    public void audioPlay(PluginCall call) {
        try {
            String uri = str(call, "uri", null);
            if (uri == null || uri.isEmpty()) { call.reject("no uri"); return; }
            String title = str(call, "title", "\u2014");
            String artist = str(call, "artist", "");
            String acc = str(call, "acc", "#0b84ff");
            Boolean ap = null;
            try { ap = call.getBoolean("autoplay"); } catch (Exception ignored) {}
            boolean autoplay = ap == null || ap;
            int index = intOr(call, "index", 0);
            int repeat = intOr(call, "repeat", 0);
            List<String> uris = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            List<String> artists = new ArrayList<>();
            try {
                JSArray q = call.getArray("queue");
                if (q != null) {
                    for (int i = 0; i < q.length(); i++) {
                        try {
                            JSONObject o = q.getJSONObject(i);
                            uris.add(o.optString("uri", ""));
                            titles.add(o.optString("title", "\u2014"));
                            artists.add(o.optString("artist", ""));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            uiAlive = true;
            ensureService();
            if (PlayerService.core != null) {
                PlayerService.core.setQueue(uris, titles, artists, index, repeat, autoplay,
                        title, artist, acc);
            }
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void audioToggle(PluginCall call) {
        try {
            uiAlive = true;
            ensureService();
            boolean playing = false;
            if (PlayerService.core != null) playing = PlayerService.core.toggle();
            JSObject r = new JSObject();
            r.put("playing", playing);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void audioSeek(PluginCall call) {
        try {
            uiAlive = true;
            int sec = intOr(call, "sec", 0);
            if (PlayerService.core != null) PlayerService.core.seekToSec(Math.max(0, sec));
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void audioPause(PluginCall call) {
        try {
            if (PlayerService.core != null) PlayerService.core.pause();
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void audioPos(PluginCall call) {
        try {
            JSObject r = new JSObject();
            if (PlayerService.core != null) {
                r.put("playing", PlayerService.core.isPlaying());
                r.put("pos", PlayerService.core.posSec());
                r.put("dur", PlayerService.core.durSec());
            } else {
                r.put("playing", false);
                r.put("pos", 0);
                r.put("dur", 0);
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void audioState(PluginCall call) {
        try {
            JSObject r = new JSObject();
            if (PlayerService.core != null && !PlayerService.core.uris.isEmpty()) {
                r.put("uri", PlayerService.core.currentUri());
                JSArray q = new JSArray();
                for (String u : PlayerService.core.uris) q.put(u);
                r.put("queue", q);
                r.put("index", PlayerService.core.currentIndex());
                r.put("repeat", PlayerService.core.repeat);
                r.put("playing", PlayerService.core.isPlaying());
                r.put("pos", PlayerService.core.posSec());
                r.put("dur", PlayerService.core.durSec());
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void nativeEQ(PluginCall call) {
        try {
            uiAlive = true;
            Boolean on = null;
            try { on = call.getBoolean("on"); } catch (Exception ignored) {}
            double bass = 0;
            try {
                Object b = call.getData() != null ? call.getData().opt("bass") : null;
                if (b instanceof Number) bass = ((Number) b).doubleValue();
            } catch (Exception ignored) {}
            try {
                getContext().getSharedPreferences("cveq", Context.MODE_PRIVATE).edit()
                        .putBoolean("on", on != null && on)
                        .putFloat("bass", (float) Math.max(-12, Math.min(12, bass)))
                        .apply();
            } catch (Exception ignored) {}
            ensureService();
            if (PlayerService.core != null) PlayerService.core.applyEQ();
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void cacheOpen(PluginCall call) {
        try {
            String name = str(call, "name", "track.mp3").replaceAll("[^a-zA-Z0-9._-]", "_");
            File dir = new File(getContext().getCacheDir(), "audio");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, System.currentTimeMillis() + "_" + name);
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            openFiles.put(f.getAbsolutePath(), raf);
            JSObject r = new JSObject();
            r.put("path", f.getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void cacheChunk(PluginCall call) {
        try {
            String path = str(call, "path", null);
            String data = str(call, "data", null);
            if (path == null || data == null) { call.reject("no path/data"); return; }
            RandomAccessFile raf = openFiles.get(path);
            if (raf == null) {
                raf = new RandomAccessFile(path, "rw");
                raf.seek(raf.length());
                openFiles.put(path, raf);
            }
            raf.write(Base64.decode(data, Base64.DEFAULT));
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void cacheDone(PluginCall call) {
        try {
            String path = str(call, "path", null);
            if (path == null) { call.reject("no path"); return; }
            try {
                RandomAccessFile raf = openFiles.remove(path);
                if (raf != null) try { raf.close(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            trimCache(200L * 1024 * 1024);
            JSObject r = new JSObject();
            r.put("uri", Uri.fromFile(new File(path)).toString());
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    private void trimCache(long limit) {
        try {
            File dir = new File(getContext().getCacheDir(), "audio");
            File[] fs = dir.listFiles();
            if (fs == null || fs.length < 2) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= limit) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) {
                if (total <= limit) break;
                if (openFiles.containsKey(f.getAbsolutePath())) continue;
                total -= f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void playerSync(PluginCall call) {
        try {
            uiAlive = true;
            String title = str(call, "title", "\u2014");
            String artist = str(call, "artist", "");
            String acc = str(call, "acc", "#0b84ff");
            String letter = str(call, "letter", "\u266A");
            Boolean pl = null;
            try { pl = call.getBoolean("playing"); } catch (Exception ignored) {}
            double pos = 0, dur = 0;
            try {
                Object o = call.getData() != null ? call.getData().opt("pos") : null;
                if (o instanceof Number) pos = ((Number) o).doubleValue();
                Object d = call.getData() != null ? call.getData().opt("dur") : null;
                if (d instanceof Number) dur = ((Number) d).doubleValue();
            } catch (Exception ignored) {}
            ensureService();
            if (PlayerService.core != null) {
                PlayerService.core.onSync(title, artist, acc, letter, pl, pos, dur);
            }
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void playerStop(PluginCall call) {
        try {
            if (PlayerService.core != null) PlayerService.core.stopAll();
            try {
                getContext().stopService(new Intent(getContext(), PlayerService.class));
            } catch (Exception ignored) {}
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}

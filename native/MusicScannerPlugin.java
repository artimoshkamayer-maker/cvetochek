package com.artimoshka.cvetochek;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "MusicScanner",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "notif", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class MusicScannerPlugin extends Plugin {
    private static final int MAX_TRACKS = 4000;

    @PluginMethod
    public void scan(PluginCall call) {
        String alias = Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
        try {
            if (getPermissionState(alias) == PermissionState.GRANTED) {
                doScan(call);
            } else {
                requestPermissionForAlias(alias, call, "onPerm");
            }
        } catch (Exception e) {
            call.reject("permission error: " + e.getMessage());
        }
    }

    @PermissionCallback
    private void onPerm(PluginCall call) {
        doScan(call);
    }

    @PluginMethod
    public void notifState(PluginCall call) {
        boolean g = Build.VERSION.SDK_INT < 33
                || getPermissionState("notif") == PermissionState.GRANTED;
        JSObject r = new JSObject();
        r.put("granted", g);
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotifs(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        try {
            if (getPermissionState("notif") == PermissionState.GRANTED) {
                JSObject r = new JSObject();
                r.put("granted", true);
                call.resolve(r);
            } else {
                requestPermissionForAlias("notif", call, "onNotifPerm");
            }
        } catch (Exception e) {
            call.reject("notif permission error: " + e.getMessage());
        }
    }

    @PermissionCallback
    private void onNotifPerm(PluginCall call) {
        boolean g = getPermissionState("notif") == PermissionState.GRANTED;
        JSObject r = new JSObject();
        r.put("granted", g);
        call.resolve(r);
    }

    @Override
    public void load() {
        PlayerService.sink = new PlayerService.ActionSink() {
            @Override
            public void onEvent(String action, long posMs) {
                try {
                    JSObject d = new JSObject();
                    d.put("action", action);
                    d.put("pos", posMs / 1000.0);
                    notifyListeners("playerAction", d);
                } catch (Exception ignored) {}
            }
        };
    }

    @PluginMethod
    public void playerSync(PluginCall call) {
        try {
            JSObject d = call.getData();
            String title = d.optString("title", "Цветочек");
            String artist = d.optString("artist", "");
            boolean playing = d.optBoolean("playing", true);
            long posMs = (long) (d.optDouble("pos", 0) * 1000);
            long durMs = (long) (d.optDouble("dur", 0) * 1000);
            String acc = d.optString("acc", "#e91e63");
            int fav = d.optBoolean("fav", false) ? 1 : 0;
            String letter = d.optString("letter", "?");
            String art = d.optString("art", "");
            if (art.isEmpty()) art = d.optString("artUri", "");
            String pkg = d.optString("pkg", "");
            if (pkg.isEmpty()) pkg = getContext().getPackageName();
            PlayerService.show(getContext(), title, artist, playing, posMs, durMs, acc, fav, letter, art, pkg);
            call.resolve();
        } catch (Exception e) {
            call.reject("playerSync failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void audioPlay(PluginCall call) {
        try {
            PlayerService.touchJs();
            JSObject d = call.getData();
            String uri = d.optString("uri", "");
            String title = d.optString("title", "");
            String artist = d.optString("artist", "");
            String acc = d.optString("acc", "#e91e63");
            boolean autoplay = d.optBoolean("autoplay", true);
            int idx = d.optInt("index", -1);
            int rep = d.optInt("repeat", 0);
            java.util.List<PlayerService.Track> q = null;
            try {
                org.json.JSONArray qa = d.optJSONArray("queue");
                if (qa != null) {
                    q = new java.util.ArrayList<>();
                    for (int i = 0; i < qa.length(); i++) {
                        try {
                            org.json.JSONObject o = qa.getJSONObject(i);
                            q.add(new PlayerService.Track(o.optString("uri", ""), o.optString("title", ""), o.optString("artist", "")));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            PlayerService.audioPlay(getContext(), uri, title, artist, acc, autoplay, q, idx, rep);
            call.resolve();
        } catch (Exception e) {
            call.reject("audioPlay failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void audioToggle(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioToggle(); call.resolve(); }
        catch (Exception e) { call.reject("audioToggle failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioPause(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioPause(); call.resolve(); }
        catch (Exception e) { call.reject("audioPause failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioResume(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioResume(); call.resolve(); }
        catch (Exception e) { call.reject("audioResume failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioSeek(PluginCall call) {
        try {
            PlayerService.touchJs();
            int sec = (int) call.getData().optDouble("sec", 0);
            PlayerService.audioSeek(sec);
            call.resolve();
        } catch (Exception e) { call.reject("audioSeek failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioNext(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioNext(); call.resolve(); }
        catch (Exception e) { call.reject("audioNext failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioPrev(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioPrev(); call.resolve(); }
        catch (Exception e) { call.reject("audioPrev failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioStop(PluginCall call) {
        try { PlayerService.touchJs(); PlayerService.audioStop(getContext()); call.resolve(); }
        catch (Exception e) { call.reject("audioStop failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void audioPos(PluginCall call) {
        try {
            long[] pd = PlayerService.audioPos();
            JSObject r = new JSObject();
            r.put("pos", pd[0] / 1000.0);
            r.put("dur", pd[1] / 1000.0);
            r.put("playing", pd[2] == 1);
            call.resolve(r);
        } catch (Exception e) { call.reject("audioPos failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void nativeEQ(PluginCall call) {
        try {
            JSObject d = call.getData();
            boolean on = d.optBoolean("on", false);
            float[] bands = new float[10];
            try {
                org.json.JSONArray ba = d.optJSONArray("bands");
                if (ba != null) for (int i = 0; i < 10 && i < ba.length(); i++) bands[i] = (float) ba.getDouble(i);
            } catch (Exception ignored) {}
            float bass = (float) d.optDouble("bass", 0);
            PlayerService.setEQ(on, bands, bass);
            call.resolve();
        } catch (Exception e) { call.reject("nativeEQ failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void playerStop(PluginCall call) {
        try {
            PlayerService.hide(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("playerStop failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cacheOpen(PluginCall call) {
        try {
            String name = call.getData().optString("name", "track.mp3");
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (name.isEmpty()) name = "track.mp3";
            java.io.File dir = new java.io.File(getContext().getCacheDir(), "upload-audio");
            try { dir.mkdirs(); } catch (Exception ignored) {}
            trimCache(dir, 200L * 1024L * 1024L);
            java.io.File f = new java.io.File(dir, System.currentTimeMillis() + "-" + name);
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
                try { fos.close(); } catch (Exception ignored) {}
            } catch (Exception e) {
                call.reject("cache open failed: " + e.getMessage());
                return;
            }
            JSObject r = new JSObject();
            r.put("path", f.getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("cacheOpen failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cacheChunk(PluginCall call) {
        try {
            String path = call.getData().optString("path", "");
            String data = call.getData().optString("data", "");
            if (path.isEmpty() || data.isEmpty()) {
                call.reject("bad args");
                return;
            }
            byte[] raw;
            try {
                raw = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
            } catch (Exception e) {
                call.reject("base64 failed: " + e.getMessage());
                return;
            }
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(path, true);
                try { fos.write(raw); } finally { try { fos.close(); } catch (Exception ignored) {} }
            } catch (Exception e) {
                call.reject("cache write failed: " + e.getMessage());
                return;
            }
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("cacheChunk failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cacheDone(PluginCall call) {
        try {
            String path = call.getData().optString("path", "");
            java.io.File f = new java.io.File(path);
            if (path.isEmpty() || !f.exists()) {
                call.reject("file missing");
                return;
            }
            JSObject r = new JSObject();
            r.put("uri", android.net.Uri.fromFile(f).toString());
            r.put("size", f.length());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("cacheDone failed: " + e.getMessage());
        }
    }

    private void trimCache(java.io.File dir, long maxBytes) {
        try {
            java.io.File[] fs = dir.listFiles();
            if (fs == null || fs.length < 2) return;
            long total = 0;
            for (java.io.File f : fs) total += f.length();
            if (total <= maxBytes) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (java.io.File f : fs) {
                if (total <= maxBytes) break;
                try { total -= f.length(); f.delete(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void doScan(PluginCall call) {
        JSArray arr = new JSArray();
        Cursor c = null;
        try {
            ContentResolver cr = getContext().getContentResolver();
            Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
            c = cr.query(base, proj, sel, null, MediaStore.Audio.Media.TITLE + " ASC");
            if (c != null) {
                int iId = c.getColumnIndex(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                int iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
                while (c.moveToNext() && arr.length() < MAX_TRACKS) {
                    long id = c.getLong(iId);
                    JSObject o = new JSObject();
                    o.put("id", id);
                    o.put("uri", Uri.withAppendedPath(base, String.valueOf(id)).toString());
                    o.put("title", c.getString(iTitle));
                    o.put("artist", c.getString(iArtist));
                    o.put("album", iAlbum >= 0 ? c.getString(iAlbum) : "");
                    o.put("durMs", iDur >= 0 ? c.getLong(iDur) : 0);
                    o.put("name", c.getString(iName));
                    o.put("size", iSize >= 0 ? c.getLong(iSize) : 0);
                    arr.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("tracks", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("scan failed: " + e.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }
    }

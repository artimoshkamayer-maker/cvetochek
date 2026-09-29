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
        @Permission(alias = "video", strings = { Manifest.permission.READ_MEDIA_VIDEO }),
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
            boolean fav = d.optBoolean("fav", false);
            String acc = d.optString("acc", "#0b84ff");
            String letter = d.optString("letter", "♪");
            PlayerService.show(getContext(), title, artist, playing, posMs, durMs, fav, acc, letter);
            call.resolve();
        } catch (Exception e) {
            call.reject("playerSync failed: " + e.getMessage());
        }
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
    @PluginMethod
    public void scanVideos(PluginCall call) {
        String alias = Build.VERSION.SDK_INT >= 33 ? "video" : "storage";
        try {
            if (getPermissionState(alias) == PermissionState.GRANTED) {
                doScanVideos(call);
            } else {
                requestPermissionForAlias(alias, call, "onVideoPerm");
            }
        } catch (Exception e) {
            call.reject("permission error: " + e.getMessage());
        }
    }

    @PermissionCallback
    private void onVideoPerm(PluginCall call) {
        doScanVideos(call);
    }

    private void doScanVideos(PluginCall call) {
        JSArray arr = new JSArray();
        Cursor c = null;
        try {
            ContentResolver cr = getContext().getContentResolver();
            Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] proj = new String[] {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE
            };
            String sel = MediaStore.Video.Media.SIZE + " > ?";
            String[] args = new String[] { "100000" };
            c = cr.query(base, proj, sel, args, MediaStore.Video.Media.DISPLAY_NAME + " ASC");
            if (c != null) {
                int iId = c.getColumnIndex(MediaStore.Video.Media._ID);
                int iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                int iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE);
                while (c.moveToNext() && arr.length() < MAX_TRACKS) {
                    long id = c.getLong(iId);
                    JSObject o = new JSObject();
                    o.put("id", id);
                    o.put("uri", Uri.withAppendedPath(base, String.valueOf(id)).toString());
                    o.put("name", c.getString(iName));
                    o.put("size", iSize >= 0 ? c.getLong(iSize) : 0);
                    arr.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("videos", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("scanVideos failed: " + e.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }
}

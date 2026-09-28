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
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicScannerPlugin extends Plugin {
    private static final int MAX_TRACKS = 4000;

    @PluginMethod
    public void scan(PluginCall call) {
        String alias = Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
        try {
            if (getPermissionState(alias) == PackageManager.PERMISSION_GRANTED) {
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

package com.artimoshka.cvetochek;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicScannerPlugin.class);
        super.onCreate(savedInstanceState);
        // Доступ просится 1 раз при старте, дальше библиотека собирается сама
        try {
            java.util.List<String> ps = new java.util.ArrayList<>();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ps.add(android.Manifest.permission.READ_MEDIA_AUDIO);
                ps.add(android.Manifest.permission.POST_NOTIFICATIONS);
            } else {
                ps.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            androidx.core.app.ActivityCompat.requestPermissions(this, ps.toArray(new String[0]), 7001);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        // Кнопка Назад сворачивает, а не убивает
        try {
            moveTaskToBack(true);
        } catch (Exception e) {
            super.onBackPressed();
        }
    }
}

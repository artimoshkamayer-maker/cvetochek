# Цветочек — сборка АПК (2.9)

Готовый проект Capacitor: веб-плеер (`web-src/`) + нативный плагин
`MusicScanner` (скан музыки, фоновая служба, шторка, кэш загрузок).
`versionCode 29 / versionName 2.9` — в `android/app/build.gradle`.

## Вариант А — сборка в облаке (без установки программ, ~10 минут)

1. Создай пустой репозиторий на GitHub.
2. Залей туда ВСЁ содержимое этой папки `src/apk/` как корень репозитория
   (файл `cvetochek-apk-2.9.zip` из чата — это та же папка одним архивом).
   Файл `github-workflow-build-apk.yml` положи как `.github/workflows/build-apk.yml`.
3. Открой вкладку Actions → `build-apk` → `Run workflow` (на пуше тоже соберётся сам).
4. Дождись зелёной галочки → скачай артефакт `cvetochek-apk` → внутри `app-debug.apk`.
5. Перекинь на телефон, разреши «установку из неизвестных источников», ставь.
   При первом запуске разреши музыку и уведомления.

## Обновление плеера (новая версия index.html)

1. Положи свежий `index.html` в `web-src/index.html` (картинки — в `web-src/img/`).
2. Запушь — workflow сам пересоберёт АПК.

## Вариант Б — локально в Android Studio

1. Поставь Node 20+, запусти `npm install`, потом `node tools/sync-www.mjs`.
2. Открой папку `android/` в Android Studio, нажми Run ▶️.

## Вариант В — сборка прямо на телефоне через Termux

Termux ставь с F-Droid (версия из Play Market мёртвая), потом `termux-setup-storage`.

Лёгкий путь (рекомендую): в Termux только залить проект на GitHub и забрать готовый АПК:

```
pkg install -y git gh
gh auth login
cd ~/cvetochek   # распакованный zip
git init -b main && git add . && git commit -m init
gh repo create cvetochek --public --source=. --push
```

Actions соберёт сам. Забрать АПК:

```
gh run list
gh run download <номер_запуска> -n cvetochek-apk
```

Полный путь (всё собирается на телефоне, нужно ~4 ГБ свободно и 15–30 минут):

```
pkg update && pkg install -y openjdk-17 nodejs git unzip wget
wget https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
unzip gradle-8.10.2-bin.zip -d ~/gradle
export PATH=$HOME/gradle/gradle-8.10.2/bin:$PATH
mkdir -p ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/tools.zip
unzip /tmp/tools.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk ANDROID_SDK_ROOT=~/android-sdk
export PATH=~/android-sdk/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"
cd ~/cvetochek && npm install && node tools/sync-www.mjs
sed -i 's/-Xmx4g/-Xmx1536m/' android/gradle.properties
gradle assembleDebug --no-daemon -p android
cp android/app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
```

## Что внутри натива

- `PlayerService` — Foreground Service (mediaPlayback): ExoPlayer, очередь,
  восстановление после свайпа (`cvetq` в SharedPreferences), шторка с кнопками
  ⏮ ▶/⏸ ⏭ ⭐ ✕, MediaSession, кэш загрузок (лимит 200 МБ), нативный EQ баса.
- `MusicScannerPlugin` — мост JS↔натив: `scan`, `audioPlay/Toggle/Seek/Pause/Pos/State`,
  `nativeEQ`, `cacheOpen/Chunk/Done`, `playerSync/Stop`, `requestNotifs`, события `playerAction`.
- Кнопка Назад сворачивает приложение, а не убивает (`moveTaskToBack`).

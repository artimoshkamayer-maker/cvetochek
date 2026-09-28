# Цветочек 1.7 — сборка APK

Пакет: com.artimoshka.cvetochek. Приложение полностью офлайн: все картинки внутри, демо-треки удалены.
Музыка выбирается с устройства и НЕ копируется в приложение — вес APK не растёт (как Audify).

Есть два пути. Путь А (через GitHub) — самый простой, ничего устанавливать не надо.

## Как это работает с музыкой (важно)

- Android (APK): при первом запуске приложение попросит доступ к музыке — разреши один раз.
  Дальше библиотека собирается сама при каждом запуске, ничего добавлять не надо, вес приложения не растёт
  (файлы не копируются, читаются с устройства как в Audify).
- Компьютер (браузер Chrome/Edge): Настройки → «Музыка +» — выбери папку один раз,
  дальше она подхватывается сама. Загруженная папка показывается под кнопкой с крестиком ✕ для отмены.
  Если браузер спросит разрешение — нажми ту же кнопку ещё раз.

## Путь А. Сборка в облаке через GitHub (рекомендуется)

1. Зарегистрируйся на github.com (если нет аккаунта).
2. Создай новый репозиторий: кнопка New, имя например cvetochek, можно Private. Галочки README/license не важны.
3. Распакуй этот zip у себя на компьютере.
4. На странице репозитория нажми «uploading an existing file» и перетащи ВСЕ файлы и папки из распакованного архива (включая папку .github). Нажми Commit changes.
5. Открой вкладку Actions. Если GitHub попросит включить workflows для репозитория — включи.
6. Слева выбери «Build APK», справа кнопка Run workflow, подтверди. Сборка стартует автоматически и при каждом push в main.
7. Подожди 5–10 минут, пока кружок станет зелёным. Открой этот запуск, внизу скачай артефакт cvetochek-1.7 — внутри app-debug.apk.
8. Перекинь APK на телефон и открой его для установки. Если телефон спросит — разреши установку из неизвестных источников для этого файла.

Обновления позже: замени файлы в www/, закоммить в репозиторий — новый APK соберётся сам.
Версия выставляется автоматически (versionCode 17, versionName 1.7) шагом Set version в workflow.

## Путь Б. Сборка локально через Android Studio

Что установить (один раз): Node.js LTS с nodejs.org, Java 17, Android Studio (доустановит Android SDK), USB-кабель.

1. Распакуй zip, открой папку в терминале.
2. Выполни: npm install
3. Выполни: npx cap add android
   (папка android/ создастся автоматически, название и пакет подставятся из capacitor.config.json)
4. Версия: открой android/app/build.gradle и выставь versionCode 17 и versionName "1.7".
   При обновлениях увеличивай versionCode (17, 18...) — иначе телефон не даст обновиться.
5. Иконка: в Android Studio открой папку android/, правый клик на app/src/main/res -> New -> Image Asset ->
   Launcher Icons, Foreground Layer -> Path -> assets/icon-1024.png -> Next -> Finish.
   (Либо терминалом: npm i -D @capacitor/assets, затем npx capacitor-assets generate --android)
6. Нативный сканер музыки (шаг из GitHub-сборки — вручную): скопируй native/MusicScannerPlugin.java в
   android/app/src/main/java/com/artimoshka/cvetochek/, в MainActivity добавь регистрацию плагина:
   в onCreate до super.onCreate вызови registerPlugin(MusicScannerPlugin.class);
   в android/app/src/main/AndroidManifest.xml добавь разрешения READ_MEDIA_AUDIO
   и READ_EXTERNAL_STORAGE (maxSdkVersion 32).
6. Сборка: меню Build -> Build App Bundle(s) / APK(s) -> Build APK(s).
   Либо терминалом: cd android, затем ./gradlew assembleDebug (на Windows: gradlew.bat assembleDebug).
   Готовый файл: android/app/build/outputs/apk/debug/app-debug.apk (debug-подписи достаточно для себя).
7. Установка: скопируй APK на телефон или adb install -r app-debug.apk.

## Если захочешь в Play Маркет (необязательно)

- Нужен keystore: в Android Studio Build -> Generate Signed Bundle / APK.
- Нужен аккаунт разработчика Google Play (25 долларов).
- Дальше собираешь release-сборку (assembleRelease), а не debug.

## Частые проблемы

- gradlew: Permission denied -> выполни: chmod +x gradlew
- Android Studio ругается на Java -> в настройках Gradle выбери JDK 17.
- Белый экран после запуска -> проверь, что делал npx cap sync android после замены www/.
- GitHub Actions красный -> открой лог упавшего шага и пришли его сюда, разберём.

# Установка и сборка Aurora (Windows и Android)

Это инструкция для основной ветки `main` репозитория `Loc323u63/Aurora_chat`. Здесь описана сборка Android-приложения и Windows-версии. Инструкции для Linux в этом документе намеренно не приводятся.

> Важно: APK, собранный из исходников этого форка, и APK из официального релиза исходного проекта могут иметь разные ключи подписи. Android не установит один поверх другого как обычное обновление. Перед удалением уже установленной версии сделайте резервную копию данных.

## 1. Что выбрать

- **Нужно просто установить приложение на Android:** скачайте APK из раздела Releases того проекта, откуда вы хотите получить готовую сборку. Не считайте ссылку на релизы исходного проекта сборкой именно этого форка.
- **Нужно проверить изменения этого форка:** соберите debug APK по инструкции ниже.
- **Нужно приложение для Windows:** используйте раздел 5.

У этого форка может не быть готового APK в Releases. Если раздел Releases пуст, соберите APK самостоятельно.

## 2. Подготовка Android-сборки

Установите:

1. **Android Studio** — актуальную стабильную версию с Android SDK.
2. **JDK 21**. Укажите его в Android Studio: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**. Также можно задать переменную окружения `JAVA_HOME`.
3. В **SDK Manager → SDK Platforms** установите **Android API 36**.
4. В **SDK Manager → SDK Tools** установите:
   - Android SDK Build-Tools;
   - Android SDK Platform-Tools;
   - Android SDK Command-line Tools;
   - **NDK (Side by side) 27.0.12077973**;
   - **CMake 3.22.1**.

Версии NDK и CMake должны совпадать с указанными: они заданы непосредственно в Gradle-конфигурации проекта. Для сборки Android-приложения нужен 64-битный компьютер. Поддерживаемые ABI в APK: `arm64-v8a` и `x86_64`.

## 3. Скачать исходники

Откройте PowerShell или терминал и выполните:

```powershell
git clone https://github.com/Loc323u63/Aurora_chat.git
cd Aurora_chat
git switch main
```

Если Git не установлен, установите Git for Windows. Альтернативно можно скачать ZIP через **Code → Download ZIP**, но для последующего обновления исходников удобнее Git.

Откройте в Android Studio именно папку `Aurora_chat` — ту, где находятся `settings.gradle.kts` и `gradlew.bat`. Дождитесь окончания Gradle Sync. При первом запуске Gradle скачает нужную версию Gradle и зависимости, поэтому потребуется интернет и свободное место на диске.

Если Android Studio не находит SDK, откройте **File → Project Structure → SDK Location** и укажите папку Android SDK. При необходимости создайте в корне проекта `local.properties`:

```properties
sdk.dir=C:/Users/USERNAME/AppData/Local/Android/Sdk
```

Замените путь на фактический путь к SDK на вашем компьютере. Файл `local.properties` локальный; не добавляйте его в Git.

## 4. Собрать и установить Android APK

### Вариант A — через Android Studio

1. Откройте проект и дождитесь Gradle Sync.
2. Выберите конфигурацию приложения `app`.
3. Откройте **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. После успешной сборки нажмите **Locate** в уведомлении Android Studio.

### Вариант B — через PowerShell

В корне проекта:

```powershell
.\gradlew.bat :app:assembleDebug
```

Готовый APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Чтобы установить его на подключённый телефон:

1. Включите на телефоне **Для разработчиков → Отладка по USB**.
2. Подключите телефон кабелем и подтвердите RSA-ключ компьютера на экране телефона.
3. Убедитесь, что устройство видно:

```powershell
.\gradlew.bat :app:installDebug
```

Либо установите APK вручную: скопируйте `app-debug.apk` на телефон и откройте его. Android может попросить разрешить установку приложений из выбранного файлового менеджера.

Минимальная версия Android, заданная проектом, — **Android 8.0 (API 26)**. Для телефона обычно подходит ABI `arm64-v8a`.

### Проверить код

Запустите основные проверки:

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Или одной командой:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug
```

Если нужна также проверка общей desktop-части на Windows:

```powershell
.\gradlew.bat :desktop:test
```

Успешная сборка APK не заменяет проверку на реальном устройстве. После установки проверьте запуск, выдачу разрешения на музыку, сканирование библиотеки, воспроизведение, паузу, перемотку и вывод звука.

## 5. Собрать Windows desktop

Desktop-сборка поддерживается для Windows x64. Потребуются:

- JDK 21;
- Visual Studio 2022 или новее либо Visual Studio Build Tools;
- workload **Desktop development with C++** (MSVC и Windows SDK);
- CMake, доступный из командной строки;
- Git.

Скачайте репозиторий и откройте PowerShell в его корне. Запустить приложение из исходников:

```powershell
.\gradlew.bat :desktop:run
```

Создать установщики MSI и EXE для текущей Windows-системы:

```powershell
.\gradlew.bat :desktop:packageDistributionForCurrentOS
```

Результаты находятся в:

```text
desktop\build\compose\binaries\main\
```

Gradle собирает нативную часть через CMake и MSVC. Если сборка сообщает, что `cmake` или C++ compiler не найден, проверьте установку компонентов Visual Studio и переменную `PATH`, затем перезапустите терминал.

## 6. Release-сборка Android и подпись

Для личной проверки обычно достаточно debug APK. Для распространения release APK нужен собственный ключ подписи. Не публикуйте keystore, пароли и приватные ключи в GitHub.

Проект читает настройки подписи из локального `keystore.properties` или переменных окружения:

- `storeFile` / `AURORA_KEYSTORE_FILE` — путь к keystore;
- `storePassword` / `AURORA_KEYSTORE_PASSWORD` — пароль хранилища;
- `keyAlias` / `AURORA_KEY_ALIAS` — alias ключа (по умолчанию `aurora`);
- `keyPassword` / `AURORA_KEY_PASSWORD` — пароль ключа (по умолчанию пароль хранилища).

Файл `keystore.properties` и сам keystore должны оставаться только на вашем компьютере и быть исключены из коммитов. Без настроенного ключа Gradle может собрать release-вариант без подписи, но такой APK не годится для обычной установки/обновления как подписанный релиз.

Команда:

```powershell
.\gradlew.bat :app:assembleRelease
```

Если вы не владеете ключом подписи уже установленной версии, не удаляйте её до резервного копирования: APK с другой подписью не сможет обновить приложение с сохранением данных.

## 7. Обновить исходники

Если репозиторий был клонирован через Git:

```powershell
git switch main
git pull --ff-only origin main
```

После обновления откройте проект в Android Studio и дождитесь Gradle Sync, затем повторите нужную команду сборки.

## 8. Частые проблемы

| Ошибка | Что проверить |
| --- | --- |
| Gradle сообщает о неподходящей Java | Выберите JDK 21 в настройках Gradle Android Studio и проверьте `JAVA_HOME`. |
| Не найден Android SDK | Проверьте SDK Location и `local.properties`. |
| Не найден NDK или CMake | Установите точные версии из SDK Manager: NDK 27.0.12077973 и CMake 3.22.1. |
| Ошибка при скачивании зависимостей | Проверьте интернет, доступ к Google Maven, Maven Central и Gradle Plugin Portal; повторите Sync. |
| Телефон не отображается | Проверьте USB-кабель с передачей данных, USB debugging, подтверждение RSA и `adb devices`. |
| APK не устанавливается поверх старого | Скорее всего, отличается подпись. Сделайте резервную копию; для установки сборки с другой подписью может потребоваться удалить прежнюю версию. |
| Windows desktop не собирается | Проверьте Visual Studio C++ workload, MSVC, Windows SDK и CMake. |

## 9. Где что находится в проекте

- `app/` — Android-приложение и Android UI.
- `core/` — общий Kotlin-код, источники данных, репозитории и Genius lyrics.
- `desktop/` — Windows desktop-приложение.
- `extension-sdk/` — SDK расширений.
- `examples/extension/` — пример расширения.
- `README.md` — основное описание проекта.
- `README_GENIUS_RU.md` — отдельная техническая документация интеграции Genius.

## 10. Важное о Genius Lyrics

Интеграция Genius в этом форке использует API для поиска и публичные страницы песен для извлечения текста. Для API нужен собственный Genius access token. Учитывайте ограничения и условия Genius: не пытайтесь обходить CAPTCHA, блокировки, 403 или 429. Подробности, устройство локального хранилища, статусы импорта и ограничения описаны в [README_GENIUS_RU.md](README_GENIUS_RU.md).

---

Инструкция сверена с Gradle-конфигурацией ветки `main`. Команды приведены для Windows PowerShell. Сборка и тесты в рамках подготовки этого документа не запускались; если Gradle выдаст ошибку, ориентируйтесь на полный текст ошибки, а не только на последнюю строку.

# mRemoteDroid

Менеджер RDP-подключений для Android, вдохновлённый [mRemoteNG](https://github.com/mRemoteNG/mRemoteNG).

Приложение — это **менеджер подключений**: оно хранит дерево папок и серверов, шифрует учётные данные и открывает сеанс во **внешнем** RDP-клиенте (Microsoft Remote Desktop или aFreeRDP). Сам RDP-протокол приложение не реализует.

## Возможности (v0.1)

- 🗂️ **Дерево папок и подключений** — вложенные папки, разворачивание/сворачивание, как панель Connections в mRemoteNG.
- 🔐 **Шифрование учёток** — пароли хранятся в зашифрованном виде (AES-256/GCM, ключ в аппаратном Android Keystore, не покидает устройство).
- 📥 **Импорт `confCons.xml`** — чтение файла подключений mRemoteNG, включая расшифровку паролей (AES-GCM + PBKDF2-HMAC-SHA1, формат mRemoteNG ≥ 1.76; пароль файла по умолчанию `mR3m`). После импорта пароли сразу перешифровываются ключом устройства.
- 🚀 **Запуск внешнего клиента** — двумя способами:
  - `.rdp`-файл + `ACTION_VIEW` (понимают и Microsoft RD Client, и aFreeRDP; Android покажет выбор клиента). Пароль в `.rdp` не пишется (MS-клиент принимает только машинно-хешированный), поэтому он копируется в буфер обмена для вставки.
  - `rdp://user:pass@host:port` — ссылку напрямую понимает aFreeRDP (с паролем).

VNC/SSH сохраняются в модели (чтобы импорт из mRemoteNG не терял данные), но сеанс для них пока не открывается.

## Стек

- Kotlin + Jetpack Compose (Material 3)
- Room (хранение дерева), DataStore, Navigation Compose
- Android Keystore для шифрования паролей
- `minSdk 26`, `targetSdk/compileSdk 35`

## Архитектура

```
app/src/main/java/com/mremotedroid/app/
├── MRemoteApp.kt              // Application + service locator (repository)
├── MainActivity.kt            // Compose + Navigation (tree <-> edit)
├── data/
│   ├── model/Protocol.kt      // RDP/VNC/SSH, маппинг из mRemoteNG
│   ├── db/                    // Room: NodeEntity, NodeDao, AppDatabase
│   ├── crypto/CredentialCrypto.kt   // AES-GCM через Android Keystore
│   ├── repo/ConnectionRepository.kt // CRUD + сплющивание дерева в строки
│   └── importer/MRemoteNgImporter.kt// парсинг + расшифровка confCons.xml
├── launch/RdpLauncher.kt      // .rdp файл и rdp:// запуск внешнего клиента
└── ui/
    ├── theme/Theme.kt
    ├── tree/                  // ConnectionTreeScreen + TreeViewModel
    └── edit/                  // EditConnectionScreen + EditViewModel
```

Дерево хранится одной таблицей `nodes` с самоссылкой `parentId`; `ConnectionRepository.flatten()` превращает его в список видимых строк с учётом свёрнутых папок.

## Сборка

Проект рассчитан на **Android Studio** (Ladybug или новее).

1. `File → Open` → выберите папку `mRemoteDroid`.
2. Дождитесь Gradle Sync — Android Studio подтянет зависимости и при необходимости **сгенерирует `gradle/wrapper/gradle-wrapper.jar`** (его нет в репозитории, т.к. это бинарник).
3. `Run` на эмуляторе или устройстве.

Сборка из командной строки (после того как wrapper jar создан):

```bash
./gradlew assembleDebug
```

> Если `./gradlew` жалуется на отсутствие `gradle-wrapper.jar`, выполните один раз `gradle wrapper --gradle-version 8.11.1` (нужен установленный Gradle) либо просто откройте проект в Android Studio.

## Проверка импорта

На ПК в mRemoteNG: `Tools → Options` покажет параметры шифрования. Файл по умолчанию — `%AppData%\mRemoteNG\confCons.xml`. Скопируйте его на телефон и в приложении: меню (⋮) → «Импорт confCons.xml». Если файл защищён собственным паролем — введите его вместо `mR3m`.

## Дальнейшие шаги (не в v1)

- Встроенный RDP через FreeRDP (NDK) — полноценный сеанс внутри приложения.
- Перетаскивание узлов, сортировка, поиск.
- Экспорт обратно в `confCons.xml`.
- Биометрическая разблокировка хранилища паролей.
- VNC/SSH-сеансы.

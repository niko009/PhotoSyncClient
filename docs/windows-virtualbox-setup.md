# PhotoSync: Google-вход и хранение фотографий в Windows

Зафиксировано: 2026-08-31. Обновлено: 2026-09-02.

Статус на 2026-09-02: Google-вход, объединение устройств одного аккаунта,
семейные приглашения и ACL папок реализованы. Сервер развёрнут на
`https://photosync.bacus.dev`. Windows-папка подключена в Ubuntu через VirtualBox
Shared Folders: ресурс VirtualBox называется `server`, точка монтирования Ubuntu —
`/mnt/server`. `compose.yml` настроен так, что оригиналы фото и видео хранятся в
этой Windows-папке, а SQLite остаётся в локальном Docker volume Ubuntu.
Самостоятельный доступ по телефону без Google по-прежнему используется по умолчанию.

## Окружение и фактическая схема

Хост — Windows, виртуальная машина VirtualBox, внутри Ubuntu. PhotoSync запускается
в Docker через существующий Bacus Agent. Адрес сервера — `https://photosync.bacus.dev`.

```text
Android: ключ телефона по умолчанию; Google-вход — привязка общего аккаунта
  → HTTPS → PhotoSync в Docker / Ubuntu / VirtualBox
              ├─ /storage → bind mount /mnt/server → VirtualBox share `server` → Windows
              └─ /data/system/photosync.db → bacus-photosync-data → Ubuntu Docker volume
```

Изначальный режим одного владельца расширен до семейного сервера с отдельными
Google-аккаунтами. Каждый участник может иметь несколько телефонов; папки личные
по умолчанию, совместный доступ выдаётся явно. Состав семьи и права папок описаны
в [плане семейного доступа](family-sharing.md). Публичный многопользовательский
платный хостинг в эту схему не входит. Google используется для входа, а не для
хранения фотографий.

## Авторизация и семейный доступ

1. «Войти через Google» в Android работает через Credential Manager.
2. Google ID token передаётся серверу по HTTPS. Сервер проверяет подпись,
   издателя, срок действия и аудиторию — ожидаемый OAuth client ID приложения.
3. Для связи используется стабильный Google `sub`, а не присланный клиентом email.
   Сервер не сохраняет ID token или Google access token.
4. Привязка возможна только из уже аутентифицированного пространства телефона.
   Устройства с одинаковым `sub` видят единый архив; выход отвязывает только
   текущее устройство и возвращает его в приватный режим.
5. Все операции с данными остаются защищены ключом установки. Google расширяет
   область видимости, но не заменяет bearer-ключ для фоновой синхронизации.
6. Новая папка всегда `Private`. Владелец может переключить её в `WholeFamily`
   либо `SelectedPeople` и выдать `View` или `Contribute`.

Веб-портал использует тот же подтверждённый Google `sub`. Google OAuth настроен
для package `com.photosync.android`, release SHA-1 и Web Client ID. Закрытый ключ
подписи и его пароли исключены из Git. Android client secret не требуется и не
должен находиться в APK.

## Подключение папки Windows — выполнено 2026-09-01

Текущая структура новых оригиналов:

```text
{phone_model}_{user_name}/{folder_name}/{original_file_name}
```

Значения очищаются до Windows-safe сегментов: пробелы и недопустимые символы
заменяются `_`. `user_name` берётся из проверенного Google-профиля; без Google
используется `local`. Старые файлы автоматически не перемещаются, а сохранённые
в SQLite прежние относительные пути продолжают работать.

Фактическая конфигурация:

- VirtualBox shared-folder name: `server`;
- Ubuntu mount point: `/mnt/server`;
- filesystem: `vboxsf`;
- Ubuntu mount owner/group: `mbacus:mbacus` (обычно numeric `1000:1000`);
- directory mode: `0775`;
- file mode: `0664`;
- Docker bind mount: `/mnt/server:/storage`;
- `PhotoSync__StorageRoot=/storage`;
- SQLite: `/data/system/photosync.db` в named volume `bacus-photosync-data`;
- контейнер остаётся non-root и получает storage GID как supplementary group через
  `PHOTOSYNC_STORAGE_GID` (по умолчанию `1000`).

Проверка монтирования Ubuntu должна показывать именно `vboxsf`; обычная пустая
директория `/mnt/server` не считается подключённым Windows-хранилищем. Тестовый
файл, созданный в `/mnt/server`, должен появляться в Windows.

SMB и открытие сетевой папки в интернет для этой схемы не требуются. После
успешной загрузки оригинал находится непосредственно в папке Windows; отдельная
синхронизация из виртуального диска не нужна. Не перемещать и не переименовывать
управляемые PhotoSync файлы вручную: относительные пути записаны в базе.

## Реализованное разделение хранения

| Данные | Место хранения |
| --- | --- |
| Оригиналы фото и видео | Windows shared folder → `/mnt/server`, внутри контейнера `/storage` |
| Временные загрузки | `_temp` внутри `PhotoSync__StorageRoot`, то есть Windows storage |
| SQLite, включая WAL/SHM | `/data/system/` в локальном Docker volume Ubuntu |

`StoragePathResolver` формирует файлы относительно `PhotoSync__StorageRoot` и
хранит новые оригиналы в структуре
`<phone_model>_<user_name>/<folder_name>/<original_file_name>`. SQLite намеренно
не переносится на VirtualBox shared folder.

Старые оригиналы автоматически не перемещаются: записанные в SQLite относительные
пути продолжают указывать на прежнее место. Любую будущую миграцию выполнять
отдельно, с резервной копией базы и без удаления `bacus-photosync-data`.

## Защита от «призрачных» папок — 0.6.1-beta

До 0.6.1 сервер мог записать новую папку в SQLite, а затем получить ошибку при
`Directory.CreateDirectory` на `/storage`. В результате Android видел альбом, но
физической директории в Windows могло не быть.

Начиная с 0.6.1:

- создание новой записи Album и физической директории выполняется как одна
  контролируемая операция;
- если `/storage` недоступен или нет прав записи, транзакция SQLite откатывается и
  сервер возвращает `503 STORAGE_UNAVAILABLE`;
- при старте сервер проходит по всем активным Album и создаёт отсутствующие
  директории;
- поэтому старые «призрачные» записи автоматически получают физическую папку при
  следующем успешном запуске сервера;
- если storage недоступен на старте, PhotoSync должен упасть явно, а не продолжить
  работу с ложным состоянием;
- Android 0.6.1 для пустых альбомов отдельно показывает «синхронизирован с
  сервером» и «ожидает сервер», используя наличие подтверждённого server album ID,
  а не количество фотографий.

Для диагностики на Ubuntu:

```bash
findmnt -T /mnt/server
C=$(docker ps --format '{{.Names}}' | grep -i photosync | head -n1)
docker exec "$C" sh -lc 'id; ls -ld /storage; test -w /storage && echo STORAGE_WRITABLE || echo STORAGE_NOT_WRITABLE'
docker logs --tail 100 "$C"
```

`findmnt` должен показывать `vboxsf`, а контейнер — `STORAGE_WRITABLE`.

## Порядок запуска и отказоустойчивость

- Запускать PhotoSync только после подключения `/mnt/server` как `vboxsf`.
- После перезагрузки Ubuntu проверить, что shared folder смонтирован до начала
  загрузок PhotoSync.
- Если папка пропала, диск переполнен или запись невозможна, не подтверждать
  синхронизацию и не переключаться молча на локальную директорию Ubuntu.
- Подтверждать загрузку только после проверки и сохранения файла и метаданных.
- Windows, VirtualBox и Ubuntu должны работать для доступности сервера. Сон хоста
  и остановка VM прерывают синхронизацию.
- Общая папка не является резервной копией. Нужны отдельный бэкап оригиналов,
  согласованная копия SQLite и проверка восстановления. Снимок VM не заменяет
  резервную копию файлов в общей папке Windows.

## Проверки после переключения

- [x] Windows shared folder выбрана и подключена как `server`.
- [x] Ubuntu видит её как `/mnt/server` и запись из Ubuntu проверена.
- [x] `compose.yml` разделяет Windows media storage и локальную SQLite.
- [ ] Проверить запись именно из non-root PhotoSync container в `/storage`.
- [ ] Загрузить новый файл с Android и убедиться, что он появляется в Windows.
- [ ] Сверить SHA-256 загруженного и скачанного оригинала.
- [ ] Проверить большое видео, обрыв загрузки и повторную отправку.
- [ ] Проверить поведение при недоступной `/mnt/server` и отсутствие ложного успеха.
- [ ] Проверить сохранность после пересоздания контейнера и перезапуска VM.
- [ ] Настроить резервное копирование и восстановление.

## Связанные материалы

- [Семейный доступ: личные и общие папки](family-sharing.md).
- [Контракт развёртывания Bacus Agent](bacus-deployment.md).
- [Oracle: Guest Additions и общие папки VirtualBox](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/guestadditions.html).
- [Docker: bind mounts](https://docs.docker.com/engine/storage/bind-mounts/).
- [Google: проверка входа на сервере](https://developers.google.com/identity/sign-in/android/backend-auth).
- [Android: Google-вход через Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
- [SQLite: ограничения удалённых файловых систем](https://www.sqlite.org/useovernet.html).

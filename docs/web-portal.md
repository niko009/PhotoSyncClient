# PhotoSync web: пользователь, администратор, владелец

Состояние на 2026-09-01: Google-вход и ролевой кабинет реализованы и развёрнуты на Ubuntu.
Пользователь подтвердил `https://photosync.bacus.dev` и запуск на Ubuntu.
Публикация выполнена через Bacus Agent; API загрузки и сохранность после обновления
проверены. Первичный владелец определяется по единственному Google-аккаунту,
который уже подтверждён Android-устройством на этом сервере.
Результат и ограничения зафиксированы в bacus-deployment.md.

## Продукт и внешний вид

Продолжаем тёплый семейный дизайн Android: молочный фон, терракотовые действия,
шалфейный цвет статуса, заголовки с засечками. Вместо отдельного несвязанного
админ-сайта — один вход `/portal/` и доступные по роли разделы.

| Роль | Возможности первого выпуска |
| --- | --- |
| User | Связанные с его Google `sub` телефоны, альбомы, последние 100 загрузок и скачивание собственных оригиналов |
| ServerAdmin | Статистика всех устройств, последний контакт, объём данных, состояние базы/диска, журнал управления |
| SuperAdmin | Всё выше, создание пользователей/ServerAdmin и подтверждённое назначение свободных телефонов аккаунтам |

Администратор видит технические метаданные, но не получает неявного права скачивать
чужие фотографии. Даже SuperAdmin скачивает только файлы устройств, назначенных
его аккаунту. Полный доступ к содержимому потребует отдельного продуктового
решения и аудита; владение инфраструктурой само по себе не равно разрешению UI.
Назначение свободного телефона намеренно выдаёт доступ ко всем его существующим
альбомам; интерфейс требует подтверждения владельца/UUID. Уже назначенное
устройство нельзя переназначить этим endpoint. Не назначать по одному похожему имени.

## Что работает

- ASP.NET Core Identity, отдельная SQLite-база пользователей/ролей/аудита.
- Secure + HttpOnly + SameSite=Strict cookie; сессия 8 часов без автопродления.
- CSRF-токен для входа и всех изменений; пароли не находятся в localStorage.
- 5 неверных паролей блокируют аккаунт на 15 минут, лимит попыток входа.
- Смена пароля отзывает предыдущие сессии через security stamp.
- Нет публичной регистрации и нет «первый посетитель становится админом».
- Android продолжает использовать отдельный Device bearer scheme. Cookie кабинета
  не даёт доступ к Android API; ключ телефона не даёт права администратора.
- Данные кабинета фильтруются на сервере по подтверждённому владельцу устройства.
- Legacy `/api/admin/dashboard` теперь требует административную cookie-сессию.
- Журнал записывает создание аккаунтов и назначение устройств, не пароли/ключи.
- `/health` проверяет подключение к БД; `/api/server/capabilities` сообщает протокол 2.

Названия устройств/файлов выводятся через textContent, не HTML. Оригиналы кабинета
выдаются как attachment / application/octet-stream. Это список загрузок, не новая
полноценная фотогалерея; визуальные превью в вебе — следующий шаг.

## Начальные ограничения

Один экземпляр приложения, максимум 5 зарегистрированных телефонов, 10 GiB
проиндексированных файлов, 25 MiB на файл, резерв свободного места 512 MiB.
Записи файлов сериализуются внутри процесса. Это НЕ распределённая квота:
запускать несколько реплик на одну SQLite/папку нельзя.
Неиндексированные посторонние файлы учитываются свободным местом диска,
а не суммой в каталоге. Само наличие Docker-тома не является резервной копией.

Новые телефоны регистрируются автоматически до лимита. Для закрытия подключения
новых устройств установить `PhotoSync__AllowDeviceRegistration=false`;
существующие ключи продолжают работать. Лимит снижает ущерб, но не заменяет
приглашения: злоумышленник всё ещё может занять свободные места. До публичного
массового использования нужны одобрение подключения/приглашения и MFA для владельца.

## Развёртывание и первоначальный владелец

Для первого теста пользователь разрешил локальное хранилище на Ubuntu, без
ожидания подключения Windows-папки. Используется том `bacus-photosync-data`:

- `/data/devices/` — оригиналы;
- `/data/system/photosync.db` — каталог и ключи устройств;
- `/data/system/photosync-portal.db` — Identity, владельцы телефонов, аудит;
- `/data/system/keys/` — ключи защиты cookie (секретные, включать в защищённый backup).

На Ubuntu оператор создаёт закрытый файл `/srv/bacus/apps/photosync/portal.env`
вне Git. Пример НАЗВАНИЙ параметров, не готовые учётные данные:

```dotenv
Portal__BootstrapUser=<имя владельца>
Portal__BootstrapPassword=<уникальный пароль минимум 12 символов>
Portal__TrustedProxyAddresses__0=<фактический IP reverse proxy в bacus-net>
```

Пароль должен содержать заглавные/строчные буквы, цифры и спецсимволы. Не отправлять
его в чат/коммиты/логи. Bootstrap создаёт SuperAdmin только в пустой базе;
последующие рестарты не сбрасывают пароль. После создания удалить bootstrap-пароль
из env-файла и пересоздать контейнер без удаления томов.

Cookie и antiforgery требуют HTTPS. `X-Forwarded-Proto` принимается только от
доверенного proxy, не от произвольного клиента. Указать фактический адрес proxy;
не отключать проверку known proxies. Без этой настройки за контейнерным proxy
вход может быть недоступен, даже если страница открывается.

Для текущего single-domain deployment дополнительно задан фиксированный
`Portal__PublicOrigin=https://photosync.bacus.dev`. Он разрешает portal cookie/CSRF
за reverse proxy только при совпадении публичного HTTPS-origin и Host, не включая
доверие ко всем forwarded-заголовкам. Для другого домена значение нужно изменить.

Командная шина Bacus умеет register/deploy, но не умеет создавать секретный
env-файл. Поэтому provisioning аккаунта/прокси нужен
перед полноценным вводом кабинета в эксплуатацию. Docker Engine на локальной
Windows недоступен; образ успешно собран и запущен на Ubuntu через Bacus Agent.
Пока настройка не завершена, страница показывает состояние первого запуска и
скрывает форму входа. Это не отключает аутентификацию, HTTPS или CSRF-защиту.

## Следующие этапы

1. Настроить секрет владельца и trusted proxy, развернуть один
   контейнер; проверить APK → upload → download, перезапуск и сохранность тома.
2. Безопасное сопряжение телефона через одноразовый код/QR вместо ручного назначения.
3. Веб-вход через Google и управляемое восстановление потерянных пространств.
4. Семейные приглашения и ACL отдельных альбомов (отдельно от роли администратора).
5. Веб-галерея, аудит ошибок загрузки, управление блокировками/квотами, MFA.
6. Реестр нескольких серверов: стабильный ID, heartbeat, версии, состояние диска,
   очередь ошибок, отдельные серверные удостоверения. Сейчас UI честно показывает
   один экземпляр; удалённые сервера не опрашиваются и не управляются.

## Проверки

32 серверных integration/unit теста прошли: изоляция телефонов и пользователей,
роли, CSRF, защищённая cookie, отзыв сессий, блокировка входа, назначение устройства,
скачивание только владельцем, лимиты устройств/файлов/места и прежний upload API.
Release publish и JavaScript syntax check прошли. В браузере проверены страница
входа и ширина 390 px без горизонтального переполнения. Весь интерактивный кабинет
в реальном HTTPS-браузере ещё требует отдельного smoke-test.
NuGet audit не обнаружил известных уязвимых зависимостей в текущих источниках.

SDK: проверка выполнена установленным .NET 10.0.400 из корня репозитория.
В `server/global.json` остаётся исходный pin 10.0.204; запуск из `server/` требует
этого SDK. Pin не менялся в рамках первого кабинета.

## Google OAuth configuration

The public Web/Server Client ID is configured in Android `BuildConfig`, server
`appsettings.json` and the deployed Compose environment. No Google client secret
is required for ID-token authentication, and no secret may be embedded in the APK.

Google Cloud must also contain an Android OAuth client in the same project:

- package name: `com.photosync.android`;
- release signing SHA-1: `52:DB:EB:ED:D4:CC:89:5B:1B:2C:EF:DF:8B:4E:44:B3:60:6F:4F:B4`.

If the OAuth consent screen is in Testing mode, add each Google account that will
test PhotoSync. Android Credential Manager, server-side ID-token validation and
device linking are implemented in 0.3.0-beta. The server validates the token
audience against the Web Client ID and stores only Google `sub`, verified email
and display name; it does not store the ID token or a Google access token.

The web portal uses the same Web Client ID and Google Identity Services button.
Google Cloud must list `https://photosync.bacus.dev` under **Authorized JavaScript
origins** for that client. The popup/callback flow does not use a redirect URI or
client secret. The browser sends the returned ID token to PhotoSync over HTTPS;
the server validates it again and creates its own Secure, HttpOnly portal session.

Portal enrollment is intentionally closed:

- a new Google portal account is accepted only if the same verified `sub` is
  already linked to an Android device on this server;
- when the portal database is empty, SuperAdmin bootstrap succeeds only if exactly
  one distinct Google subject is already present among linked devices;
- if several subjects exist before bootstrap, the server refuses to guess the
  owner and requires private operator setup;
- later linked Google accounts receive `User`; matching devices are assigned
  automatically, while existing ownership is never silently transferred.

Family invitations and per-folder ACLs are not implemented yet.

# Семейный доступ PhotoSync — 0.4.0-beta

Статус: реализуется в ветке `feature/family-sharing-immutable-archive`. Этот документ фиксирует актуальную модель и заменяет старое планирование семейного доступа.

## Главный инвариант хранения

Успешно проверенный и committed оригинал фотографии или видео является частью append-only архива. Обычные PhotoSync UI/API/background jobs **никогда физически не удаляют committed original**.

Удаление фото или папки в интерфейсе — только логический archive/tombstone (`ArchivedAtUtc`). Исключение человека из семьи, отзыв ACL, logout, unlink устройства или изменение членства также не удаляют и не перемещают оригиналы. Удаление временного незавершённого `.upload` разрешено. Любая будущая физическая очистка committed originals должна быть отдельной администраторской/offline процедурой вне пользовательских PhotoSync flows.

## Identity и семья

Каждый человек использует собственный Google-аккаунт. Постоянный ключ пользователя — проверенный Google `sub`. Проверенный email хранится как атрибут профиля и используется как ограничение приглашения; email не является primary identity key.

Сущности сервера: `User`, `Family`, `FamilyMember`, `FamilyInvitation`, `Device`, `Album` (логическая папка), `FolderAcl`, `StoredFile`.

В 0.4 пользователь состоит максимум в одной семье. При миграции каждый существующий Google-пользователь получает собственную семью и роль Owner. Существующие папки и медиа остаются private. Физические пути файлов при миграции не меняются.

Owner семьи управляет членством, но сама роль Owner семьи не даёт доступ к приватным папкам других членов.

## Приглашение без почтового сервера

SMTP и собственный email server не используются.

1. Owner открывает Family и вводит точный Google email адресата.
2. Сервер нормализует/валидирует email и генерирует 32 криптографически случайных байта.
3. Клиент получает raw token только в URL. В SQLite хранится только SHA-256 hash токена.
4. Срок действия — 7 дней; приглашение одноразовое и может быть отозвано Owner.
5. Android показывает стандартный Share Sheet, Copy Link и QR. WhatsApp/Telegram не захардкожены.
6. `/join/<token>` — минимальная публичная landing page без данных семьи. Она предлагает открыть PhotoSync через `photosync://join/<token>`.
7. При принятии Android выполняет Google Sign-In. Сервер повторно проверяет Google token, `sub` и verified email. Email из тела запроса не является доказательством личности.
8. Verified email должен в точности совпасть с `ExpectedEmail`; при ошибочном аккаунте возвращается masked expected email.
9. Принятие выполняется транзакционно. Повторно использованный, expired или revoked token отклоняется; unique membership не позволяет создать дубль.

Verified Android App Links для произвольного self-hosted домена не обязательны в 0.4: используется безопасный web landing + custom scheme. Для `photosync.bacus.dev/join/*` Android также зарегистрирован как HTTPS deep-link target; полноценный `assetlinks.json`/deferred deep-link можно добавить после фиксации production signing certificate.

## Folder privacy и ACL

Новая папка всегда `Private`.

Режимы:

- `Private` — только владелец папки.
- `WholeFamily` — динамически все текущие и будущие **активные** участники той же семьи получают настроенный `View` или `Contribute`.
- `SelectedPeople` — ACL для выбранных активных участников семьи.

Права:

| Permission | Просмотр/preview/download | Upload | ACL/settings |
| --- | --- | --- | --- |
| None | нет | нет | нет |
| View | да | нет | нет |
| Contribute | да | да | нет |
| Owner | да | да | да |

`Contribute` не позволяет менять ACL и никогда не позволяет физически удалять committed originals. При исключении участника family membership становится inactive, поэтому и WholeFamily, и старые SelectedPeople ACL перестают давать доступ немедленно.

Custom Family Groups отложены. Модель ACL отделена от membership и допускает дальнейшее расширение.

## Server-side authorization

Проверка прав выполняется на сервере. UI не является границей безопасности.

`FolderAccessService` централизует проверки Owner/View/Contribute. Они применяются к:

- списку папок;
- списку файлов;
- preview;
- download original;
- upload;
- hash/dedup check;
- dashboard counters;
- ACL changes;
- logical archive.

Угадывание ID закрытой папки/файла возвращает нейтральный `404` либо, для dedup, `exists=false`, не раскрывая наличие чужого объекта или hash. Dedup работает только в разрешённом upload destination.

`Contribute` проверяется перед началом upload и ещё раз после size/hash verification непосредственно перед publication/commit. Если право отозвано во время загрузки, временный файл не публикуется.

## Logical removal

API 0.4 поддерживает logical archive:

- `POST /api/files/{id}/archive`
- `POST /api/albums/{id}/archive`

Операции выставляют `ArchivedAtUtc`. Они не вызывают `File.Delete`, `Directory.Delete` и не удаляют committed originals. В обычных списках archived объекты скрыты.

## Family API

- `GET /api/family`
- `POST /api/family/invites`
- `DELETE /api/family/invites/{inviteId}`
- `POST /api/family/join/{token}`
- `DELETE /api/family/members/{userId}`
- `PUT /api/albums/{albumId}/sharing`

Invite create/accept rate-limited. Raw invite token не сохраняется и не должен логироваться.

## Android 0.4

В Settings появился вход в Family. Экран показывает только family name, текущую роль, минимальный профиль активных участников и, для Owner, pending invites. Он не показывает чужие устройства, приватные папки, photo counts, storage usage или activity.

Owner может создать приглашение, поделиться ссылкой стандартным Android chooser, скопировать её или показать QR, отозвать pending invite и удалить участника. Deep-link приглашения открывает Family flow и предлагает Google Sign-In для принятия.

## Отложено

SMTP/email sending, public user directory, guest/public links, Google Family API, physical deletion/GC committed media, E2EE, family ownership transfer, multiple families per user, custom family groups и полноценный deferred deep linking не входят в 0.4.

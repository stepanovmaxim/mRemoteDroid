# Ответы для Play Console (раздел «Контент приложения»)

Ссылка на политику конфиденциальности:
**https://github.com/stepanovmaxim/mRemoteDroid/blob/main/PRIVACY.md**

Тексты страницы в магазине на 7 языках — в [listing.md](listing.md).

---

## Политика конфиденциальности
Вставить ссылку выше.

## Доступ к приложению (App access)
«Все функции доступны без специального доступа» (All functionality is available without special access).
В примечании для проверяющих (если попросят) — текст на английском:

> The app has no account or login. To open a remote desktop session the reviewer needs
> their own computer with Remote Desktop (RDP) enabled; all other features (connection
> list, folders, search, confCons.xml import/export, biometric lock, About screen) work
> without a server.

## Реклама (Ads)
«Нет, в приложении нет рекламы».

## Возрастной рейтинг (Content rating)
Категория: **Утилиты / Utility, Productivity, Communication or Other**.
На все вопросы о насилии, азартных играх и т. п. — «Нет». Взаимодействие пользователей / обмен
контентом между пользователями — «Нет». Ожидаемый рейтинг: 3+ / PEGI 3 / Everyone.

## Целевая аудитория (Target audience)
Только **18 и старше** (приложение для администраторов). «Может ли привлекать детей» — Нет.

## Безопасность данных (Data safety)
- «Собирает ли приложение или передаёт ли третьим лицам пользовательские данные из списка?» — **Нет**.
  Обоснование: разработчик ничего не получает; список подключений и пароли хранятся только на
  устройстве; логин и пароль отправляются только на сервер, который пользователь сам указал
  (это действие пользователя, а не сбор данных разработчиком); отчёт о сбое отправляется только
  вручную через системное меню «Поделиться».
- Если Play всё же потребует указать сбор (например, при ручной проверке), честный вариант:
  «Личные данные → Учётные данные (Other / User IDs)», цель — «Функциональность приложения»,
  «Не передаются третьим лицам», «Обработка обязательна», «Данные шифруются при передаче — Да»,
  «Пользователь может запросить удаление — Да (удаление приложения)».

## Разрешения на службы переднего плана (Foreground service permissions)
Тип: **FOREGROUND_SERVICE_SPECIAL_USE**. Описание (англ.):

> mRemoteDroid is a remote desktop (RDP) client. While the user has a remote desktop
> session open, a foreground service with an ongoing notification ("Connected: host")
> keeps the RDP network connection alive when the screen turns off or the user switches
> to another app. Without it the system suspends the app's network access and the
> session to the remote computer drops. The service starts only when the user opens a
> session and stops as soon as the last session is closed. The user can return to the
> session or see the open sessions from the notification.

Нужно также короткое видео (можно «доступ по ссылке» на YouTube): открыть сеанс → выключить экран
на ~30 с → включить → сеанс на месте; показать уведомление «Подключено: …».

## Новостное приложение / государственное / финансовое / медицинское
Везде «Нет».

## Категория приложения
Приложения → **Инструменты (Tools)** или **Бизнес (Business)**. Теги: Remote desktop, Productivity.

## Контакты
Email разработчика — обязателен и будет виден в Google Play. Укажите отдельный ящик, если
не хотите светить личный. Сайт — https://github.com/stepanovmaxim/mRemoteDroid

## Графика
- Значок 512×512 PNG (32-бит, без прозрачности по краям не обязательно).
- Баннер 1024×500.
- Скриншоты телефона: минимум 2, рекомендуется 4–8, сторона 1080–7680 px. Хорошо подойдут:
  дерево подключений, редактор подключения, сеанс в альбомной ориентации, меню сеанса,
  экран «О приложении». На скриншотах сеанса не должно быть реальных адресов и рабочих столов
  ваших серверов.

-- МЕНЮТАТА КЪМ НАСТРОЙКИТЕ НА МОДУЛА — кой ги вижда. ЧЕТЕ САМО.
--
-- Менюто от кода (Menu.xml, bg-localization-settings) е groups="admins" и е в ADMIN_MENUS на каталога
--, а грантовете му от по-ранен каталог са оттеглени (nx_retired.csv). Ръчно направеното
-- меню „Настройки — Българска локализация“ сочи същото действие
-- (action-app-bg-localization-view) и видимостта му зависи САМО от групите и ролите му в базата: меню
-- без групи и без роли Axelor показва на всеки. Действието отказва на не-администратор (моделът
-- AppBgLocalization няма права в каталога), но менюто остава видимо.
--
-- Колоните на връзките са сверени с db/migration/2026-09-10-bg-orphan-nyxa-root-menu.sql:
-- meta_menu_groups(meta_menu_id, group_id), meta_menu_roles(menus, roles).
--
-- Очаквано: за всеки ред групи = admins и роли = (няма). Ред с празни групи и празни роли = видим
-- за всички → отделно решение (да се изтрие ръчното меню или да получи groups=admins).
--   psql … -v ON_ERROR_STOP=1 -f 2026-10-16-app-config-menu-groups.sql
BEGIN READ ONLY;
\pset border 2
\pset format aligned
SELECT m.id,
       m.name                                          AS меню,
       m.title                                         AS заглавие,
       coalesce(m.module, '(ръчно)')                   AS модул,
       coalesce(p.name, '')                            AS родител,
       coalesce(a.name, '')                            AS действие,
       coalesce(m.hidden, false)                       AS скрито,
       coalesce((SELECT string_agg(g.code, ', ' ORDER BY g.code)
                   FROM meta_menu_groups mg JOIN auth_group g ON g.id = mg.group_id
                  WHERE mg.meta_menu_id = m.id), '(няма)') AS групи,
       coalesce((SELECT string_agg(r.name, ', ' ORDER BY r.name)
                   FROM meta_menu_roles mr JOIN auth_role r ON r.id = mr.roles
                  WHERE mr.menus = m.id), '(няма)')       AS роли
  FROM meta_menu m
  LEFT JOIN meta_action a ON a.id = m.action
  LEFT JOIN meta_menu p ON p.id = m.parent
 WHERE a.name = 'action-app-bg-localization-view'
    OR m.title = 'Настройки — Българска локализация'
    OR m.name = 'bg-localization-settings'
 ORDER BY m.module NULLS FIRST, m.id;
ROLLBACK;

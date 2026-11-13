-- ОСТАТЪЦИ В МЕТАДАННИТЕ + ЗАПИСЪТ НА НАСТРОЙКИТЕ. ЧЕТЕ САМО — пуска се след деплой;
-- резултатът решава дали и какво се чисти (скрипт за почистване НЯМА).
--   PGPASSWORD=… psql -h … -U … -d <база> -v ON_ERROR_STOP=1 -f 2026-10-14-app-config-orphans.sql
--
-- А. 12-те имена, изтрити от кода на axelor-bg-localization, които при деплой ОСТАВАТ в базата:
--    Axelor обновява и добавя метаданни, но не трие изчезналите от XML-а. Празен ред = няма го.
--    ⚠️ nyxa-root и bg-compliance-root са МЕНЮТА-КОРЕНИ — ако ги има, може да се виждат празни.
-- Б. Записът AppBgLocalization: „Конфигуриране“ на картата (AppController#configure) иска App с
--    обратна връзка към ТОЧНО ТОЗИ запис — тоест app трябва да е попълнено. Очаквано: 1 запис,
--    0 с празно app, приложението bg-localization съществува.
\pset border 2
\pset format aligned
BEGIN READ ONLY;

-- ── А. остатъците ──────────────────────────────────────────────────────────────────────────
WITH wanted(kind, name, removed_in) AS (
  VALUES ('view',   'app-bg-localization-form',                         'премахнат от кода'),
         ('view',   'app-bg-localization-grid',                         'премахнат от кода'),
         ('menu',   'nyxa-root',                                        'премахнат от кода'),
         ('menu',   'bg-compliance-root',                               'премахнат от кода'),
         ('action', 'action-bg-payslip-recompute',                      'премахнат от кода'),
         ('action', 'action-bg-art55-download',                         'премахнат от кода'),
         ('action', 'action-bg-insurance-contribution-validate-rates',  'премахнат от кода'),
         ('action', 'action-bg-min-income-check-consistency',           'премахнат от кода'),
         ('select', 'bg.etz.event.kind.select',                         'премахнат от кода'),
         ('select', 'bg.etz.ground.select',                             'премахнат от кода'),
         ('select', 'bg.health.insurance.status.select',                'премахнат от кода'),
         ('select', 'bg.relationship.type.select',                      'премахнат от кода'))
SELECT w.kind AS вид, w.name AS име, w.removed_in AS изтрит_в,
       count(x.id) AS в_базата, string_agg(DISTINCT x.module, ', ') AS модул,
       string_agg(DISTINCT x.extra, ' | ') AS подробности
  FROM wanted w
  LEFT JOIN (
    SELECT 'view' AS kind, v.id, v.name, v.module, v.type || ' ' || coalesce(v.model, '') AS extra
      FROM meta_view v
    UNION ALL
    SELECT 'menu', m.id, m.name, m.module,
           coalesce(m.title, '') || ' · родител ' || coalesce(p.name, '—')
           || ' · деца ' || (SELECT count(*) FROM meta_menu c WHERE c.parent = m.id)
      FROM meta_menu m LEFT JOIN meta_menu p ON p.id = m.parent
    UNION ALL
    SELECT 'action', a.id, a.name, a.module, a.type FROM meta_action a
    UNION ALL
    SELECT 'select', s.id, s.name, s.module, '' FROM meta_select s
  ) x ON x.kind = w.kind AND x.name = w.name
 GROUP BY w.kind, w.name, w.removed_in
 ORDER BY w.kind, w.name;

-- Действието със старо име и нова цел: при стар код — app-bg-localization-form, при нов —
-- app-bg-localization-config-form. (Axelor пази XML-а си с name преди type.)
SELECT a.name AS действие, a.module AS модул,
       substring(a.xml FROM '<view name="([^"]+)" type="form"') AS форма
  FROM meta_action a
 WHERE a.name = 'action-app-bg-localization-view';

-- ── Б. записът на настройките ──────────────────────────────────────────────────────────────
SELECT (SELECT count(*) FROM studio_app_bg_localization) AS записи,
       (SELECT count(*) FROM studio_app_bg_localization WHERE app IS NULL) AS с_празно_app,
       (SELECT count(*) FROM studio_app WHERE code = 'bg-localization') AS приложение_bg_localization,
       (SELECT string_agg(l.id || '→' || coalesce(p.code, 'NULL'), ', ')
          FROM studio_app_bg_localization l LEFT JOIN studio_app p ON p.id = l.app) AS запис_към_приложение;

ROLLBACK;

-- BG · МЕНЮТА — ПРОВЕРКА ЗА СИРАЧЕТО „Nyxa“ (`meta_menu`). Read-only, нищо не пише.
--
-- Пуска се ДВА ПЪТИ: преди `2026-09-10-bg-orphan-nyxa-root-menu.sql`, за да се види дали редът е
-- узрял за чистене и какво би го спряло; и след него, за да се види, че го няма.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-09-10-bg-orphan-nyxa-root-menu-verify.sql
--
-- ⚠️ Обвито в транзакция с ROLLBACK. Не защото пише, а за да е невъзможно да пише.
--
-- Присъдата в раздел 6 е същият набор проверки, който носи и самото почистване — тоест този файл
-- казва предварително дали то ще мине, ще пропусне (вече чисто) или ще откаже.

BEGIN;

\pset pager off
\pset null '·'

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 1 · КАНДИДАТЪТ — редът с name = ''nyxa-root'' (стабилният ключ; id-то е различно по среди)'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

\x on
SELECT id, name, xml_id, title, module, parent, hidden, archived, order_seq, priority,
       icon, icon_background, left_menu, mobile_menu, action, user_id, link,
       condition_to_check, module_to_check, created_on, updated_on, version
  FROM meta_menu
 WHERE name = 'nyxa-root';
\x off

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 2 · ПОСТ-ДЕПЛОЙ ЛИ Е БАЗАТА — внесен ли е вече XML-ът от преместването на менюто'
\echo '     Свидетелят е ''noi-root'': ражда се САМО от кода след преместването на менюто. Няма ли го, view-sync-ът'
\echo '     още не е минал и „Nyxa“ НЕ Е сирак, а живият корен на непреместени менюта.'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

WITH expected(name, expected_parent, why) AS (
  VALUES ('noi-root',          NULL,        'НОВ корен от преместването на менюто — свидетелят за минал view-sync'),
         ('nap-root',          NULL,        'корен на nyxa-nap-submissions, заварен'),
         ('hr-root',           NULL,        'корен на axelor-human-resource, заварен'),
         ('nyxa-payroll',      'hr-root',   'преместен под HR от преместването на менюто'),
         ('bg-compliance-vat', 'nap-root',  'преместен под НАП от преместването на менюто')
)
SELECT e.name                                    AS "меню",
       m.id                                      AS "id",
       coalesce(p.name, '·')                     AS "родител сега",
       coalesce(e.expected_parent, '· (корен)')  AS "родител по преместването на менюто",
       CASE WHEN m.id IS NULL THEN 'ЛИПСВА'
            WHEN coalesce(p.name, '') IS DISTINCT FROM coalesce(e.expected_parent, '')
              THEN 'НЕ СЪВПАДА'
            ELSE 'ok'
       END                                       AS "състояние",
       e.why                                     AS "какво доказва"
  FROM expected e
  LEFT JOIN meta_menu m ON m.name = e.name
  LEFT JOIN meta_menu p ON p.id = m.parent
 ORDER BY e.name;

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 3 · ДЕЦА — меню с деца НЕ Е сирак и почистването отказва'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

SELECT c.id AS "id", c.name AS "име", c.title AS "заглавие", c.module AS "модул"
  FROM meta_menu c
 WHERE c.parent = (SELECT id FROM meta_menu WHERE name = 'nyxa-root')
 ORDER BY c.order_seq, c.id;

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 4 · ВЪНШНИ ВРЪЗКИ — всяка колона в схемата, която сочи meta_menu(id), преброена срещу'
\echo '     кандидата. Списъкът се чете от pg_constraint, не от паметта: към 10.09.2026 г. са 9'
\echo '     ограничения в 7 таблици, две от които (studio_studio_menu) не личат от domain XML-ите.'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

CREATE TEMP TABLE bg_nyxa_root_refs (
  ref_table  text,
  ref_column text,
  constraint_name text,
  kind       text,
  rows_found bigint
) ON COMMIT DROP;

DO $refs$
DECLARE
  v_id  bigint;
  v_n   bigint;
  r     record;
BEGIN
  SELECT id INTO v_id FROM meta_menu WHERE name = 'nyxa-root';
  IF v_id IS NULL THEN
    RETURN;
  END IF;

  FOR r IN
    SELECT ns.nspname   AS schema_name,
           src.relname  AS ref_table,
           att.attname  AS ref_column,
           con.conname  AS constraint_name
      FROM pg_constraint con
      JOIN pg_class src        ON src.oid = con.conrelid
      JOIN pg_namespace ns     ON ns.oid = src.relnamespace
      CROSS JOIN LATERAL unnest(con.conkey) AS k(attnum)
      JOIN pg_attribute att    ON att.attrelid = con.conrelid AND att.attnum = k.attnum
     WHERE con.contype = 'f'
       AND con.confrelid = 'meta_menu'::regclass
     ORDER BY 2, 3
  LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I WHERE %I = $1',
                   r.schema_name, r.ref_table, r.ref_column)
       INTO v_n USING v_id;

    INSERT INTO bg_nyxa_root_refs
    VALUES (r.ref_table, r.ref_column, r.constraint_name,
            CASE WHEN (r.ref_table, r.ref_column) IN (('meta_menu_groups', 'meta_menu_id'),
                                                      ('meta_menu_roles',  'menus'))
                   THEN 'собствена връзка (трие се заедно с реда)'
                 WHEN (r.ref_table, r.ref_column) = ('meta_menu', 'parent')
                   THEN 'деца (виж раздел 3)'
                 ELSE 'ЧУЖДА — блокира'
            END,
            v_n);
  END LOOP;
END
$refs$;

SELECT ref_table AS "таблица", ref_column AS "колона", kind AS "вид", rows_found AS "редове",
       constraint_name AS "ограничение"
  FROM bg_nyxa_root_refs
 ORDER BY (rows_found > 0) DESC, ref_table, ref_column;

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 5 · meta_help ПО ИМЕ — единствената връзка, която НЕ Е външен ключ (menu_name е низ)'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

SELECT id AS "id", menu_name AS "меню", language AS "език", help_value AS "текст"
  FROM meta_help
 WHERE menu_name = 'nyxa-root';

\echo ''
\echo '══════════════════════════════════════════════════════════════════════════════════════════'
\echo ' 6 · ПРИСЪДА — точно проверките, които носи и самото почистване'
\echo '══════════════════════════════════════════════════════════════════════════════════════════'

WITH cand AS (
  SELECT * FROM meta_menu WHERE name = 'nyxa-root'
),
checks(nr, label, state, ok) AS (
  SELECT 1, 'редове с name = ''nyxa-root''',
         (SELECT count(*)::text FROM cand),
         (SELECT count(*) FROM cand) <= 1
  UNION ALL
  SELECT 2, 'title = ''Nyxa''',
         (SELECT coalesce(title, '·') FROM cand),
         NOT EXISTS (SELECT 1 FROM cand WHERE title IS DISTINCT FROM 'Nyxa')
  UNION ALL
  SELECT 3, 'module = ''axelor-bg-localization''',
         (SELECT coalesce(module, '·') FROM cand),
         NOT EXISTS (SELECT 1 FROM cand WHERE module IS DISTINCT FROM 'axelor-bg-localization')
  UNION ALL
  SELECT 4, 'parent IS NULL (top-level корен)',
         (SELECT coalesce(parent::text, 'NULL') FROM cand),
         NOT EXISTS (SELECT 1 FROM cand WHERE parent IS NOT NULL)
  UNION ALL
  SELECT 5, 'деца',
         (SELECT count(*)::text FROM meta_menu c WHERE c.parent = (SELECT id FROM cand)),
         NOT EXISTS (SELECT 1 FROM meta_menu c WHERE c.parent = (SELECT id FROM cand))
  UNION ALL
  SELECT 6, 'свидетелят ''noi-root'' е в базата (view-sync е минал)',
         CASE WHEN EXISTS (SELECT 1 FROM meta_menu WHERE name = 'noi-root') THEN 'да' ELSE 'НЕ' END,
         EXISTS (SELECT 1 FROM meta_menu WHERE name = 'noi-root')
  UNION ALL
  SELECT 7, 'чужди външни връзки към реда',
         (SELECT coalesce(sum(rows_found), 0)::text FROM bg_nyxa_root_refs
           WHERE kind = 'ЧУЖДА — блокира'),
         NOT EXISTS (SELECT 1 FROM bg_nyxa_root_refs
                      WHERE kind = 'ЧУЖДА — блокира' AND rows_found > 0)
  UNION ALL
  SELECT 8, 'meta_help редове по това име',
         (SELECT count(*)::text FROM meta_help WHERE menu_name = 'nyxa-root'),
         NOT EXISTS (SELECT 1 FROM meta_help WHERE menu_name = 'nyxa-root')
)
SELECT nr AS "#", label AS "проверка", state AS "състояние",
       CASE WHEN ok THEN 'PASS' ELSE 'BLOCK' END AS "присъда"
  FROM checks
 ORDER BY nr;

SELECT CASE
         WHEN NOT EXISTS (SELECT 1 FROM meta_menu WHERE name = 'nyxa-root')
           THEN 'ВЕЧЕ ЧИСТО — почистването ще мине като no-op.'
         WHEN EXISTS (SELECT 1 FROM meta_menu c
                       WHERE c.parent = (SELECT id FROM meta_menu WHERE name = 'nyxa-root'))
           THEN 'НЕ Е ГОТОВО — редът още има деца, тоест не е сирак. Деплойни и пусни пак.'
         WHEN NOT EXISTS (SELECT 1 FROM meta_menu WHERE name = 'noi-root')
           THEN 'НЕ Е ГОТОВО — липсва ''noi-root'': view-sync-ът с кода след преместването на менюто още не е минал.'
         WHEN EXISTS (SELECT 1 FROM bg_nyxa_root_refs
                       WHERE kind = 'ЧУЖДА — блокира' AND rows_found > 0)
           THEN 'ОТКАЗ — към реда сочи чужд запис (виж раздел 4). Иска ЧОВЕК.'
         WHEN EXISTS (SELECT 1 FROM meta_help WHERE menu_name = 'nyxa-root')
           THEN 'ОТКАЗ — има meta_help ред по това име (виж раздел 5). Иска ЧОВЕК.'
         WHEN (SELECT count(*) FROM meta_menu WHERE name = 'nyxa-root') > 1
           THEN 'ОТКАЗ — повече от един ред с това име. Иска ЧОВЕК.'
         WHEN EXISTS (SELECT 1 FROM meta_menu WHERE name = 'nyxa-root'
                        AND (title IS DISTINCT FROM 'Nyxa'
                             OR module IS DISTINCT FROM 'axelor-bg-localization'
                             OR parent IS NOT NULL))
           THEN 'ОТКАЗ — редът не отговаря на описанието на сирачето. Иска ЧОВЕК.'
         ELSE 'ГОТОВО — почистването ще изтрие точно един ред: '
              || (SELECT 'id=' || id FROM meta_menu WHERE name = 'nyxa-root')
       END AS "ПРИСЪДА";

ROLLBACK;

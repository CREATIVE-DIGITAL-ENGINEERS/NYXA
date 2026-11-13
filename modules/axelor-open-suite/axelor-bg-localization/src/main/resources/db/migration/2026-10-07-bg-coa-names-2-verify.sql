-- Проверка на кръг 2 на имената в BG_COA (728/791 + label-ите). ЧЕТЕ САМО (пише единствено временна таблица на сесията).
--
-- Пуска се ПРЕДИ миграцията (какво предстои) и СЛЕД нея.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-names-2-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS     — целевото състояние.
--   TODO     — миграцията ще го поправи. Преди пускане това е НОРМАЛНО; след нея не бива да има.
--   РЪЧНО    — име, различно и от старото, и от целевото. Миграцията НЕ го пипа; решава човек.
--   ЧУЖДО    — label, който не отговаря на код/име, но НЕ е от кръг 1. Миграцията НЕ го пипа.
--   ЛИПСВА   — фирмата няма тази сметка.
--   ПРОВЕРИ  — (секция 4) осчетоводени редове по 728/791 отпреди преименуването.
--
-- ⚠️ СЕКЦИЯ 3 ОБХОЖДА ВСИЧКИ BG СМЕТКИ, НЕ САМО ДЕВЕТТЕ. Очаквано е след миграцията да остане
-- клас „NULL“: INSERT-ите на 168, 442 и кръг 2 (N2) пишат `label = NULL`. Той не е поправен тук
-- нарочно — изброява се, за да се реши отделно. Приложението го попълва при запис на сметката.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 0 · ОБХВАТ (опори 4551 и 4552) ═══'
SELECT co.code AS фирма, co.name AS име
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══ 1 · ИМЕНАТА НА 728 И 791 СРЕЩУ НАП (NRA_Nom_Accounts 1.0.2) ═══'
WITH target(code, old_name, new_name) AS (
  VALUES ('728', 'Други финансови приходи', 'Приходи от реинтегрирани провизии'),
         ('791', 'Извънредни приходи',      'Приходи от отписване на кредитни експозиции')
), scope AS (
  SELECT co.id, co.code
    FROM base_company co
   WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
     AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
)
SELECT s.code AS фирма, t.code AS сметка, a.name AS име_сега, t.new_name AS целево,
       CASE WHEN a.id IS NULL THEN 'ЛИПСВА'
            WHEN a.name = t.new_name
             AND a.label = a.code || '_' || coalesce(s.code, 'null') || ' - ' || a.name
              THEN 'PASS'
            WHEN a.name = t.new_name THEN 'TODO (само label)'
            WHEN a.name = t.old_name THEN 'TODO'
            ELSE 'РЪЧНО' END AS статус
  FROM scope s
 CROSS JOIN target t
  LEFT JOIN account_account a ON a.company = s.id AND a.code = t.code
 ORDER BY s.id, t.code;

\echo ''
\echo '═══ 2 · ДЕВЕТТЕ ОТ КРЪГ 1 (#243): label СРЕЩУ код/име ═══'
WITH n1(code, old_code, old_name) AS (
  VALUES ('202', '203', 'Сгради и конструкции'), ('203', '204', 'Компютърна техника'),
         ('204', '205', 'Съоръжения'), ('205', '206', 'Машини и оборудване'),
         ('206', '207', 'Транспортни средства'), ('207', '208', 'Офис обзавеждане'),
         ('154', '153', 'Облигационни заеми'), ('40901', '409', 'Ваучери за храна'),
         ('41902', '416', 'Съмнителни и спорни вземания')
), scope AS (
  SELECT co.id, co.code
    FROM base_company co
   WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
     AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
)
SELECT s.code AS фирма, n1.code AS сметка, coalesce(a.label, 'NULL') AS label_сега,
       CASE WHEN a.id IS NULL THEN 'ЛИПСВА'
            WHEN a.label = a.code || '_' || coalesce(s.code, 'null') || ' - ' || a.name
              THEN 'PASS'
            WHEN a.label = n1.old_code || '_' || coalesce(s.code, 'null') || ' - ' || n1.old_name
              THEN 'TODO (отпечатък на кръг 1)'
            ELSE 'ЧУЖДО' END AS статус
  FROM scope s
 CROSS JOIN n1
  LEFT JOIN account_account a ON a.company = s.id AND a.code = n1.code
 ORDER BY s.id, n1.code;

\echo ''
\echo '═══ 3 · ВСИЧКИ BG СМЕТКИ: label, КОЙТО НЕ ОТГОВАРЯ НА код/име ═══'
\echo '    Автоматично се поправя САМО клас „N1“. Останалите се изброяват за решение.'
DROP TABLE IF EXISTS pg_temp.bg_names2_bad_label;
CREATE TEMP TABLE bg_names2_bad_label AS
WITH n1(code, old_code, old_name) AS (
  VALUES ('202', '203', 'Сгради и конструкции'), ('203', '204', 'Компютърна техника'),
         ('204', '205', 'Съоръжения'), ('205', '206', 'Машини и оборудване'),
         ('206', '207', 'Транспортни средства'), ('207', '208', 'Офис обзавеждане'),
         ('154', '153', 'Облигационни заеми'), ('40901', '409', 'Ваучери за храна'),
         ('41902', '416', 'Съмнителни и спорни вземания')
)
SELECT co.id AS cid, co.code AS фирма, a.code AS сметка, a.name AS име,
       coalesce(a.label, 'NULL') AS label_сега,
       a.code || '_' || coalesce(co.code, 'null') || ' - ' || a.name AS label_целеви,
       CASE WHEN n1.code IS NOT NULL
             AND a.label = n1.old_code || '_' || coalesce(co.code, 'null') || ' - ' || n1.old_name
              THEN 'N1 (TODO — миграцията го поправя)'
            WHEN a.label IS NULL THEN 'NULL (INSERT на 168/442/N2)'
            ELSE 'ДРУГО (стар код или име)' END AS клас
  FROM account_account a
  JOIN base_company co ON co.id = a.company
  LEFT JOIN n1 ON n1.code = a.code
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
   AND a.label IS DISTINCT FROM a.code || '_' || coalesce(co.code, 'null') || ' - ' || a.name;

\echo '    3.1 · обобщение'
SELECT фирма, клас, count(*) AS сметки
  FROM bg_names2_bad_label GROUP BY cid, фирма, клас ORDER BY cid, клас;

\echo '    3.2 · поименно'
SELECT фирма, сметка, клас, label_сега, label_целеви
  FROM bg_names2_bad_label ORDER BY cid, клас, сметка;

\echo ''
\echo '═══ 4 · ОСЧЕТОВОДЕНО ПО 728/791 — ОДИТ, НЕ ГЕЙТ ═══'
\echo '    Докато се казваха „Други финансови приходи“/„Извънредни приходи“, по тях може да са минали'
\echo '    такива приходи. В SAF-T те и досега излизаха с НАП имената (мапингът е IDENTITY).'
\echo '    Реклас към 729/799, ако е нужен, е РЕДОВНА статия — не UPDATE.'
SELECT co.code AS фирма, a.code AS сметка, count(ml.id) AS осчетоводени,
       to_char(coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0),
               'FM999999999990.00') AS салдо_dt_kt,
       CASE WHEN count(ml.id) = 0 THEN 'PASS (празна)' ELSE 'ПРОВЕРИ' END AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id AND a.code IN ('728', '791')
  LEFT JOIN account_move_line ml ON ml.account = a.id
   AND EXISTS (SELECT 1 FROM account_move m WHERE m.id = ml.move AND m.status_select IN (2, 3))
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code, a.code ORDER BY co.id, a.code;

\echo ''
\echo '═══ край ═══'

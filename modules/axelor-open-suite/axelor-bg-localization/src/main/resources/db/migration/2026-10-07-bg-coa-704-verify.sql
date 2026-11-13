-- Проверка на имената на 704/706 в BG_COA. ЧЕТЕ САМО — нищо не записва.
--
-- Пуска се ПРЕДИ миграцията (какво предстои) и СЛЕД нея (очакват се само PASS или РЪЧНО).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-704-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS     — името и label-ът са целевите.
--   TODO     — старото име; миграцията ще го смени. Преди пускане това е НОРМАЛНО.
--   РЪЧНО    — име, различно и от старото, и от целевото. Миграцията НЕ го пипа; решава човек.
--   FAIL     — целевото име с разминат label (падащите списъци показват старото).
--   ЛИПСВА   — фирмата няма тази сметка.
--   ПРОВЕРИ  — (секция 2) осчетоводени редове по 704 отпреди преименуването; виж бележката.

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
\echo '═══ 1 · ИМЕНАТА НА 704 И 706 СРЕЩУ НАП (NRA_Nom_Accounts 1.0.2) ═══'
WITH target(code, old_name, new_name) AS (
  VALUES ('704', 'Приходи от продажба на материали', 'Приходи от наеми'),
         ('706', 'Приходи от продажба на материали', 'Приходи от продажба на суровини/материали')
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
            WHEN a.name = t.new_name THEN 'FAIL (label: ' || coalesce(a.label, 'NULL') || ')'
            WHEN a.name = t.old_name THEN 'TODO'
            ELSE 'РЪЧНО' END AS статус
  FROM scope s
 CROSS JOIN target t
  LEFT JOIN account_account a ON a.company = s.id AND a.code = t.code
 ORDER BY s.id, t.code;

\echo ''
\echo '═══ 2 · ОСЧЕТОВОДЕНО ПО 704 — ОДИТ, НЕ ГЕЙТ ═══'
\echo '    Докато 704 се казваше „материали“, по нея може да са минали продажби на материали.'
\echo '    В SAF-T те и досега излизаха като „Приходи от наеми“ (мапингът е IDENTITY).'
\echo '    Реклас към 706, ако е нужен, е РЕДОВНА статия — не UPDATE.'
SELECT co.code AS фирма, count(ml.id) AS осчетоводени,
       to_char(coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0),
               'FM999999999990.00') AS салдо_dt_kt,
       CASE WHEN count(ml.id) = 0 THEN 'PASS (празна)' ELSE 'ПРОВЕРИ' END AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id AND a.code = '704'
  LEFT JOIN account_move_line ml ON ml.account = a.id
   AND EXISTS (SELECT 1 FROM account_move m WHERE m.id = ml.move AND m.status_select IN (2, 3))
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code ORDER BY co.id;

\echo ''
\echo '═══ край ═══'

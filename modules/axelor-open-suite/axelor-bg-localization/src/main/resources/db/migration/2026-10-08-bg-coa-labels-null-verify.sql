-- Проверка: празните label-и на сметките и 603 в BG дружествата. ЧЕТЕ САМО.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-labels-null-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS   — нула празни label-и / 603 с името по НАП и своя label.
--   TODO   — миграцията ще го поправи. Преди пускане това е НОРМАЛНО.
--   РЪЧНО  — 603 с име, различно и от старото, и от НАП. Не се пипа.
--   ИНФО   — (секция 3) НЕпразен label, който не отговаря на код/име. Тази миграция НЕ го пипа:
--            остарял, но непразен label не е празен ред в списъка; решава се отделно.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · ПРАЗНИ LABEL-И ПО ДРУЖЕСТВО (опори 4551 и 4552) ═══'
SELECT co.code AS фирма,
       count(a.id) FILTER (WHERE a.label IS NULL) AS празни,
       count(a.id) AS сметки,
       CASE WHEN count(a.id) FILTER (WHERE a.label IS NULL) = 0 THEN 'PASS' ELSE 'TODO' END AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code ORDER BY co.id;

\echo ''
\echo '═══ 2 · 603 СРЕЩУ НАП („Разходи за амортизации“) ═══'
SELECT co.code AS фирма, a.name AS име_сега, coalesce(a.label, 'NULL') AS label_сега,
       CASE WHEN a.name = 'Разходи за амортизации'
             AND a.label = '603_' || coalesce(co.code, 'null') || ' - ' || a.name THEN 'PASS'
            WHEN a.name IN ('Разходи за амортизация', 'Разходи за амортизации') THEN 'TODO'
            ELSE 'РЪЧНО' END AS статус
  FROM base_company co
  JOIN account_account a ON a.company = co.id AND a.code = '603'
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══ 3 · ИНФО: НЕпразен label ≠ код/име (не се пипа) ═══'
SELECT co.code AS фирма, count(*) AS сметки
  FROM base_company co
  JOIN account_account a ON a.company = co.id
 WHERE a.label IS NOT NULL
   AND a.label <> a.code || '_' || coalesce(co.code, 'null') || ' - ' || a.name
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code ORDER BY co.id;

\echo ''
\echo '═══ край ═══'

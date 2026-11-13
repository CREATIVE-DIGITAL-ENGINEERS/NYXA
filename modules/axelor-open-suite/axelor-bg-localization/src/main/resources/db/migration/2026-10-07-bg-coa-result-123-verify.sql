-- Проверка: сметките за резултат в AccountConfig на BG дружествата → 123. ЧЕТЕ САМО.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-result-123-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS        — 123.
--   TODO        — точно старата стойност (печалба 122/1221, загуба 1222); миграцията я сменя.
--   ЧУЖДО       — друга или празна; миграцията НЕ я пипа.
--   ПРИКЛЮЧВАНО — (секция 2) дружеството вече е приключило година през Axelor. Статиите остават;
--                 прехвърлянето 123 → 122/121 занапред е ръчна статия по решението за разпределение.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 1 · СМЕТКИТЕ ЗА РЕЗУЛТАТ (опори 4551 и 4552) ═══'
SELECT co.code AS фирма,
       coalesce(pa.code, 'NULL') AS печалба,
       CASE WHEN pa.code = '123' THEN 'PASS'
            WHEN pa.code IN ('122', '1221') AND has123.id IS NOT NULL THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус_печалба,
       coalesce(la.code, 'NULL') AS загуба,
       CASE WHEN la.code = '123' THEN 'PASS'
            WHEN la.code = '1222' AND has123.id IS NOT NULL THEN 'TODO'
            ELSE 'ЧУЖДО' END AS статус_загуба,
       CASE WHEN has123.id IS NOT NULL THEN 'да' ELSE 'НЕ' END AS има_123
  FROM base_company co
  LEFT JOIN account_account_config cfg ON cfg.company = co.id
  LEFT JOIN account_account pa ON pa.id = cfg.result_profit_account
  LEFT JOIN account_account la ON la.id = cfg.result_loss_account
  LEFT JOIN account_account has123 ON has123.company = co.id AND has123.code = '123'
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══ 2 · ВЕЧЕ ПРИКЛЮЧВАНИ ГОДИНИ — ДОКЛАД, СТАТИИТЕ НЕ СЕ ПИПАТ ═══'
\echo '    Откриващи статии (functional_origin = 1) по 122/1221/1222 и затворени финансови години.'
SELECT co.code AS фирма, a.code AS сметка, extract(year FROM m.date_val)::int AS година,
       count(ml.id) AS редове,
       to_char(coalesce(sum(ml.debit - ml.credit), 0), 'FM999999999990.00') AS салдо_dt_kt,
       'ПРИКЛЮЧВАНО' AS статус
  FROM base_company co
  JOIN account_move m ON m.company = co.id AND m.functional_origin_select = 1
   AND m.status_select IN (2, 3)
  JOIN account_move_line ml ON ml.move = m.id
  JOIN account_account a ON a.id = ml.account AND a.code IN ('122', '1221', '1222')
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code, a.code, extract(year FROM m.date_val)
 ORDER BY co.id, 3, a.code;

SELECT co.code AS фирма, y.code AS година, y.from_date, y.to_date, 'затворена' AS статус
  FROM base_year y
  JOIN base_company co ON co.id = y.company
 WHERE y.status_select = 2
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 ORDER BY co.id, y.from_date;

\echo ''
\echo '═══ край ═══'

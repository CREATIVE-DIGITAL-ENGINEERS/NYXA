-- ФАЗА 1 (691 + C): осчетоводени статии по сметките под въпрос. ЧЕТЕ САМО — пуска се преди
-- промените по сметка 691 и група C; резултатът решава дали има заварени статии, които искат реклас.
--   PGPASSWORD=… psql -h … -U … -d <база> -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-691-c-posted-lines.sql
-- Произход: technical 1 ръчна · 2 автоматична · 3 шаблон · 4 внос · 5 масово;
--           functional 1 откриване · 2 закриване · 3 продажба · 4 покупка · 5 плащане ·
--           6 cut-off · 7 ДМА · 8 съмнителни · 9 несъбираеми · 10 лихви за забава
\pset border 2
\pset format aligned
SELECT co.code AS фирма, a.code AS сметка, a.name AS име,
       count(ml.id) AS редове,
       to_char(coalesce(sum(ml.debit), 0), 'FM999999999990.00') AS дебит,
       to_char(coalesce(sum(ml.credit), 0), 'FM999999999990.00') AS кредит,
       min(extract(year FROM m.date_val))::int AS от, max(extract(year FROM m.date_val))::int AS до,
       string_agg(DISTINCT m.technical_origin_select || '/' || coalesce(m.functional_origin_select, 0)
                  || ' ' || coalesce(j.code, '?'), ', ') AS произход_журнал
  FROM base_company co
  JOIN account_account a ON a.company = co.id
   AND a.code IN ('691', '692', '456', '481', '408', '103', '455', '615', '622',
                  '722', '723', '725', '792', '462', '4557')
  LEFT JOIN account_move_line ml ON ml.account = a.id
   AND EXISTS (SELECT 1 FROM account_move mm WHERE mm.id = ml.move AND mm.status_select IN (2, 3))
  LEFT JOIN account_move m ON m.id = ml.move
  LEFT JOIN account_journal j ON j.id = m.journal
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4552')
 GROUP BY co.id, co.code, a.code, a.name
 ORDER BY co.id, a.code;

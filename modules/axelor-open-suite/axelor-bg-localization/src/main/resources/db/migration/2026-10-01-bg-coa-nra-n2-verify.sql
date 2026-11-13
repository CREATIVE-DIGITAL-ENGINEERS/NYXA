-- Проверка на кръг 2 от привеждането на BG_COA към НАП. ЧЕТЕ САМО — нищо не записва.
--
-- Пуска се ПРЕДИ миграцията (какво предстои и какво ще я спре) и СЛЕД нея (очакват се само PASS).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-01-bg-coa-nra-n2-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS  — състоянието вече е целевото.
--   TODO  — миграцията има какво да направи. Преди пускане това е НОРМАЛНО.
--   FAIL  — миграцията ЩЕ ОТКАЖЕ, докато това не се оправи с човешко решение.
--
-- ⚠️ FAIL-овете тук не са дефекти на файла, а въпроси към счетоводителя: осчетоводен ред по
-- сметка, която става репортен възел, иска реклас с РЕДОВНА статия, не UPDATE.

\pset border 2
\pset format aligned

\echo ''
\echo '═══ 0 · ОБХВАТ ═══'
SELECT co.code AS фирма, co.name AS име,
       (SELECT count(*) FROM account_account a WHERE a.company = co.id) AS сметки
  FROM base_company co
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
   AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
 ORDER BY co.id;

\echo ''
\echo '═══ 1 · КОЛОНАТА НА А/П ОСТА (идва от ddl=update, НЕ от миграцията) ═══'
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS статус,
       CASE WHEN count(*) = 1 THEN 'account_account.bg_ap_select съществува'
            ELSE 'НЯМА я — вдигни приложението с кода на PR-N2 веднъж, после пусни миграцията' END
         AS бележка
  FROM information_schema.columns
 WHERE table_schema = current_schema() AND table_name = 'account_account'
   AND column_name = 'bg_ap_select';

\echo ''
\echo '═══ 2 · СЕКВЕНЦИИТЕ (миграцията ПРАВИ INSERT-и) ═══'
SELECT seq AS секвенция,
       CASE WHEN last_val >= max_id THEN 'PASS' ELSE 'FAIL' END AS статус,
       last_val AS секвенцията_е_на, max_id AS max_id
  FROM (
    SELECT 'account_account_seq' AS seq,
           coalesce(pg_sequence_last_value('public.account_account_seq'::regclass), 0) AS last_val,
           (SELECT coalesce(max(id), 0) FROM account_account) AS max_id
    UNION ALL
    SELECT 'account_accounting_report_config_line_seq',
           coalesce(pg_sequence_last_value(
             'public.account_accounting_report_config_line_seq'::regclass), 0),
           (SELECT coalesce(max(id), 0) FROM account_accounting_report_config_line)
  ) q;

\echo ''
\echo '═══ 3 · БРОЯТ НА СМЕТКИТЕ (186 → 461) ═══'
SELECT co.code AS фирма,
       count(*) AS сега,
       CASE WHEN count(*) = 461 THEN 'PASS'
            WHEN count(*) = 186 THEN 'TODO (предстоят 275)'
            ELSE 'TODO (' || (461 - count(*)) || ' липсват)' END AS статус
  FROM base_company co JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
 GROUP BY co.code ORDER BY co.code;

\echo ''
\echo '═══ 4 · ⚠️ ГЕЙТ: ОСЧЕТОВОДЕНИ РЕДОВЕ ПО СМЕТКА, КОЯТО СТАВА РЕПОРТЕН ВЪЗЕЛ ═══'
\echo '    Непразен резултат = миграцията ЩЕ ОТКАЖЕ. Реклас към листото с РЕДОВНА статия.'
SELECT co.code AS фирма, a.code AS сметка, a.name AS име, count(ml.id) AS осчетоводени,
       to_char(coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0),
               'FM999999999990.00') AS салдо,
       'FAIL' AS статус
  FROM account_account a
  JOIN base_company co ON co.id = a.company
  JOIN account_move_line ml ON ml.account = a.id
  JOIN account_move m ON m.id = ml.move AND m.status_select IN (2, 3)
  JOIN account_account_type t ON t.id = a.account_type
 WHERE a.code IN ('201', '452', '453', '601', '602', '609')
   AND t.technical_type_select <> 'view'
 GROUP BY co.code, a.code, a.name ORDER BY co.code, a.code;

\echo ''
\echo '═══ 5 · ШЕСТТЕ КОНВЕРСИИ И ДЕЦАТА ИМ ═══'
SELECT co.code AS фирма, a.code AS сметка, t.technical_type_select AS тип,
       (SELECT count(*) FROM account_account k WHERE k.parent_account = a.id) AS деца,
       CASE WHEN t.technical_type_select = 'view'
             AND (SELECT count(*) FROM account_account k WHERE k.parent_account = a.id) > 0
            THEN 'PASS' ELSE 'TODO' END AS статус
  FROM account_account a
  JOIN base_company co ON co.id = a.company
  JOIN account_account_type t ON t.id = a.account_type
 WHERE a.code IN ('201', '452', '453', '601', '602', '609')
 ORDER BY co.code, a.code;

\echo ''
\echo '═══ 6 · Z124 — паркираната от кръг 1 ═══'
SELECT co.code AS фирма,
       count(ml.id) AS осчетоводени,
       CASE WHEN count(a.id) = 0 THEN 'PASS (премахната)'
            WHEN count(ml.id) > 0 THEN 'FAIL (носи история — иска реклас към 123)'
            ELSE 'TODO (празна, ще се премахне)' END AS статус
  FROM base_company co
  LEFT JOIN account_account a ON a.company = co.id AND a.code = 'Z124'
  LEFT JOIN account_move_line ml ON ml.account = a.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
 GROUP BY co.code ORDER BY co.code;

\echo ''
\echo '═══ 7 · НЕАКТИВНИ СМЕТКИ (целта е 165) ═══'
SELECT co.code AS фирма, count(*) FILTER (WHERE a.status_select = 0) AS неактивни,
       CASE WHEN count(*) FILTER (WHERE a.status_select = 0) = 165 THEN 'PASS' ELSE 'TODO' END
         AS статус
  FROM base_company co JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
 GROUP BY co.code ORDER BY co.code;

\echo ''
\echo '═══ 8 · А/П ОС: 412 попълнени, 49 празни (репортните възли) ═══'
SELECT co.code AS фирма,
       count(*) FILTER (WHERE a.bg_ap_select IS NOT NULL) AS попълнени,
       count(*) FILTER (WHERE a.bg_ap_select IS NULL) AS празни,
       CASE WHEN count(*) FILTER (WHERE a.bg_ap_select IS NOT NULL) = 412
             AND count(*) FILTER (WHERE a.bg_ap_select IS NULL) = 49 THEN 'PASS' ELSE 'TODO' END
         AS статус
  FROM base_company co JOIN account_account a ON a.company = co.id
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
 GROUP BY co.code ORDER BY co.code;

\echo ''
\echo '═══ 9 · ТВЪРДИТЕ ВРЪЗКИ (ДМА отчетна стойност · покупки) ═══'
SELECT 'ДМА 609→60908' AS връзка, count(*) AS редове,
       CASE WHEN count(*) FILTER (WHERE a.code = '60908') = count(*) THEN 'PASS' ELSE 'TODO' END
         AS статус
  FROM account_fixed_asset_category f
  LEFT JOIN account_account a ON a.id = f.realised_assets_value_account
UNION ALL
SELECT 'покупки 601/602→листа', count(*),
       CASE WHEN count(*) FILTER (WHERE a.code IN ('601', '602')) = 0 THEN 'PASS' ELSE 'TODO' END
  FROM account_account_management m
  JOIN account_account a ON a.id = m.purchase_account;

\echo ''
\echo '═══ 10 · СПРАВКИТЕ: нито един ред не сочи гол coarse код ═══'
SELECT coalesce(cl.code, '(без код)') AS ред, cl.account_code AS чете,
       'FAIL' AS статус
  FROM account_accounting_report_config_line cl
 WHERE cl.account_code IN ('201', '452', '453', '601', '602', '609')
 ORDER BY cl.code;

\echo ''
\echo '═══ 11 · СПРАВКИТЕ: брой редове (644 → 838) ═══'
SELECT count(*) AS редове,
       CASE WHEN count(*) = 838 THEN 'PASS'
            WHEN count(*) = 644 THEN 'TODO (предстоят 194)'
            ELSE 'TODO' END AS статус
  FROM account_accounting_report_config_line;

\echo ''
\echo '═══ 12 · ⚠️ ОСТАНАЛИ НАБОРИ КЪМ ПРЕНАСОЧЕНА СМЕТКА ═══'
\echo '    Смятането прави accountSet ∪ LIKE(accountCode): стар набор връща старата сметка тихо.'
SELECT cl.code AS ред, cl.account_code AS чете, a.code AS набор_сочи, 'FAIL' AS статус
  FROM account_accounting_report_config_line cl
  JOIN account_accounting_report_config_line_account_set s
    ON s.account_accounting_report_config_line = cl.id
  JOIN account_account a ON a.id = s.account_set
 WHERE cl.account_code IS NOT NULL
   AND (cl.account_code LIKE '%\%%' OR cl.account_code LIKE '%,%')
 ORDER BY cl.code;

\echo ''
\echo '═══ край ═══'

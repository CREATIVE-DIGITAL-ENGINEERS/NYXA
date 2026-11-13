-- Проверка на кръг 3 — референтните таблици на SAF-T. ЧЕТЕ САМО — нищо не записва.
--
-- Пуска се ПРЕДИ миграцията (какво предстои и какво ще я спре) и СЛЕД нея (очакват се само PASS).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-02-bg-saft-reference-tables-verify.sql
--
-- ЧЕТЕНЕ НА ИЗХОДА:
--   PASS  — състоянието вече е целевото.
--   TODO  — миграцията има какво да направи. Преди пускане това е НОРМАЛНО.
--   FAIL  — миграцията ЩЕ ОТКАЖЕ, докато това не се оправи с човешко решение.
--
-- ⚠️ РЕЖИМИТЕ „ДРУГО“ НЕ СЕ ПРОВЕРЯВАТ ТУК. Този кръг не ги маха — изваждането им, пътищата в
-- OCR-а и миграцията за заварените записи са ОТДЕЛНА писта. Раздел 6 брои само трите НОВИ режима.

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
\echo '═══ 1 · ЧЕТИРИТЕ ДУБЛИКАТА ═══'
WITH dup(old_code, new_code, expect_type) AS (VALUES
  ('105','115','equity'), ('305','303','currentAsset'),
  ('631','608','charge'),  ('793','799','income'))
SELECT co.code AS фирма, d.old_code AS стара, d.new_code AS нова,
       CASE WHEN o.id IS NULL THEN 'PASS'
            WHEN n.id IS NULL THEN 'FAIL'
            WHEN ot.technical_type_select IS DISTINCT FROM d.expect_type
              OR nt.technical_type_select IS DISTINCT FROM d.expect_type THEN 'FAIL'
            WHEN EXISTS (SELECT 1 FROM account_account k WHERE k.parent_account = o.id) THEN 'FAIL'
            ELSE 'TODO' END AS статус,
       CASE WHEN o.id IS NULL THEN 'вече я няма'
            WHEN n.id IS NULL THEN 'заместителят ' || d.new_code || ' ЛИПСВА — пусни първо кръг 2'
            WHEN ot.technical_type_select IS DISTINCT FROM d.expect_type
              OR nt.technical_type_select IS DISTINCT FROM d.expect_type
              THEN 'типът не е ' || d.expect_type || ' — това не е дубликат, а разместване'
            WHEN EXISTS (SELECT 1 FROM account_account k WHERE k.parent_account = o.id)
              THEN 'носи подсметки — пренареждането им е решение на счетоводител'
            ELSE 'ще се премести оборот: ' ||
                 (SELECT count(*) FROM account_move_line ml WHERE ml.account = o.id)::text ||
                 ' реда' END AS бележка
  FROM base_company co
  CROSS JOIN dup d
  LEFT JOIN account_account o ON o.company = co.id AND o.code = d.old_code
  LEFT JOIN account_account n ON n.company = co.id AND n.code = d.new_code
  LEFT JOIN account_account_type ot ON ot.id = o.account_type
  LEFT JOIN account_account_type nt ON nt.id = n.account_type
 WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
 ORDER BY co.id, d.old_code;

\echo ''
\echo '═══ 2 · ОСТАНАЛИ ЧУЖДИ КЛЮЧОВЕ КЪМ ДУБЛИКАТИТЕ (всеки ред тук СПИРА миграцията) ═══'
-- ⚠️ Каталогът се пита, а не се изброява на ръка: следващият модул ще добави своя връзка и списък,
-- писан днес, би мълчал точно за нея.
DO $probe$
DECLARE
  f record; v_cnt bigint; v_found boolean := false;
BEGIN
  FOR f IN
    SELECT cl.relname::text AS tbl, att.attname::text AS col
      FROM pg_constraint con
      JOIN pg_class cl ON cl.oid = con.conrelid
      JOIN pg_class rcl ON rcl.oid = con.confrelid
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
     WHERE con.contype = 'f' AND rcl.relname = 'account_account'
       AND array_length(con.conkey, 1) = 1
     ORDER BY 1, 2
  LOOP
    -- account_move_line и двата набора ги пренасочва/чисти самата миграция — те не са пречка.
    CONTINUE WHEN f.tbl IN ('account_move_line',
                            'account_accounting_report_config_line_account_set',
                            'account_account_compatible_account_set');
    -- ⚠️ ЕДНА-ЕДИНСТВЕНА ДВОЙКА СЕ ИЗВАЖДА ЧАСТИЧНО, А НЕ ЦЯЛАТА ТАБЛИЦА. Миграцията сама
    -- пренасочва несъбираемите вземания 631 → 608 (раздел 1.3), тоест точно тази стойност не е
    -- пречка. Изключена цяла, таблицата щеше да премълчи СЪСЕДНАТА си колона, ако утре някоя
    -- настройка посочи 105 или 793 — а това вече СПИРА миграцията.
    IF f.tbl = 'account_account_config' AND f.col = 'irrecoverable_account' THEN
      EXECUTE format(
        'SELECT count(*) FROM %I t JOIN account_account a ON a.id = t.%I
          WHERE a.code IN (''105'',''305'',''793'')', f.tbl, f.col) INTO v_cnt;
    ELSE
      EXECUTE format(
        'SELECT count(*) FROM %I t JOIN account_account a ON a.id = t.%I
          WHERE a.code IN (''105'',''305'',''631'',''793'')', f.tbl, f.col) INTO v_cnt;
    END IF;
    IF v_cnt > 0 THEN
      v_found := true;
      RAISE NOTICE 'FAIL  %.% : % реда сочат дубликат', f.tbl, f.col, v_cnt;
    END IF;
  END LOOP;
  IF NOT v_found THEN
    RAISE NOTICE 'PASS  нито един останал чужд ключ към 105/305/631/793';
  END IF;
END
$probe$;

\echo ''
\echo '═══ 3 · РЕДОВЕТЕ В СПРАВКИТЕ ═══'
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'TODO' END AS статус,
       count(*) AS реда,
       'редове, които още четат изчезваща сметка (12 се трият, 2 се пренасочват)' AS бележка
  FROM account_accounting_report_config_line
 WHERE account_code IN ('105','305','631','793');

\echo ''
\echo '═══ 4 · РОДИТЕЛСКИТЕ ПРАВИЛА ═══'
-- Правило, което още сочи изтрит ред, НЕ гърми: сборът просто пропуска липсващото събираемо.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'TODO' END AS статус,
       coalesce(string_agg(DISTINCT code, ', '), '—') AS правила
  FROM account_accounting_report_config_line
 WHERE rule ~ ('(P_1_2_105|MP_1_2_105|A_2_1_305|MA_2_1_305|E_1_3_631|R_EXP_631|MO_631'
             || '|I_3_1_793|R_INC_793|MO_793|SC_O_105|SC_C_105)([^A-Za-z0-9_]|$)');

\echo ''
\echo '═══ 5 · AccountConfig: НЕСЪБИРАЕМИ ВЗЕМАНИЯ ═══'
SELECT co.code AS фирма,
       CASE WHEN a.code = '608' THEN 'PASS' WHEN a.code = '631' THEN 'TODO' ELSE 'PASS' END AS статус,
       coalesce(a.code, '— не е зададена') AS сметка
  FROM base_company co
  JOIN account_account_config cfg ON cfg.company = co.id
  LEFT JOIN account_account a ON a.id = cfg.irrecoverable_account
 WHERE EXISTS (SELECT 1 FROM account_account x WHERE x.company = co.id AND x.code = '4551')
 ORDER BY co.id;

\echo ''
\echo '═══ 6 · ТРИТЕ НОВИ РЕЖИМА НА ПЛАЩАНЕ ═══'
-- ⚠️ Остатъчните INC_OTH / OUT_OTH НЕ се броят: този кръг не ги пипа. Те стоят в
-- bg_saf_payment_mapping.csv с isExcluded = true, защото номенклатурата на НАП няма остатъчен
-- механизъм — и точно това е записано като ДАННИ, вместо да се премълчи.
SELECT CASE WHEN count(*) = 3 THEN 'PASS' ELSE 'TODO' END AS статус,
       count(*) AS налични,
       coalesce(string_agg(code, ', ' ORDER BY code), '— нито един') AS режими
  FROM account_payment_mode
 WHERE code IN ('INC_OFFSET', 'OUT_OFFSET', 'OUT_ADV');

\echo ''
\echo '═══ 7 · РЕФЕРЕНТНИТЕ ТАБЛИЦИ (идват от боота, НЕ от миграцията) ═══'
-- ⚠️ Празна таблица тук НЕ Е дефект на миграцията. Четирите входа се внасят от DataInitSyncService
-- при първото вдигане на приложението с кода на този кръг. Ако са празни СЛЕД вдигане — виж лога
-- за „Syncing data-init CSVs for module axelor-bg-localization“.
SELECT t AS таблица, очаквани,
       CASE WHEN to_regclass('public.' || t) IS NULL THEN 'TODO (няма таблица — вдигни приложението)'
            ELSE 'виж броя вляво' END AS бележка
  FROM (VALUES ('bglocalization_bg_saf_account_mapping', 457),
               ('bglocalization_bg_saf_tax_mapping', 50),
               ('bglocalization_bg_saf_payment_mapping', 16),
               ('bglocalization_bg_saf_vat_cell_code', 33)) v(t, очаквани);

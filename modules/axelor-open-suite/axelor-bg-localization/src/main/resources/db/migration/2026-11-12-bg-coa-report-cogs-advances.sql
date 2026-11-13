-- BG_COA · СПРАВКИТЕ 27/45 (ОПР) И 28/46 (БАЛАНС) — СЕБЕСТОЙНОСТТА И АВАНСИТЕ, В ЗАВАРЕНИТЕ РЕДОВЕ.
--
-- ЗАЩО. Шаблонът (account_accountingReportConfigLine.csv) вече носи двете поправки, но всяко
-- заварено дружество има свое копие на редовете от вноса:
--   1. ОПР: изписването на продадените стоки е Дт 702 / Кт 304 (вярното по БГ сметкоплана), а редът
--      на прихода нетираше 702 — „Нетни приходи“ бяха приход минус себестойност (Демо Търговия:
--      55 943,70 при ~131 хил. по фактурите), а себестойността не беше разход никъде. Нов скрит ред
--      E_1_7_STOCK (НСС) / MO_STOCK (МСС) с „702@30,706@30“ влиза в „Други разходи“ и се показва в
--      нов ред „в т.ч. балансова стойност на продадените активи“ (СС 1 т. 18.1, раздел А, ст. 5 а;
--      ГОД 10510) веднага под тях — редовете след него в справката се изместват с едно надолу.
--      Приложението (BgReportAccountMap) взима в скрития ред само статиите със сметка 30x и ги
--      изважда от реда на прихода: приходът е брутен, себестойността — отделен разход.
--   3. Само отчет, без промяна: осчетоводените (и всички други) статии за пропорционалния ред при
--      извеждане на актив, записани с продажната цена вместо с амортизацията на реда (AOS
--      FixedAssetLineMoveServiceImpl#generateMove, isDisposal → fixedAsset.disposalValue) — броят им
--      по статус излиза в NOTICE. Поправят се на ръка (сторно), ако счетоводителят реши.
--   2. Баланс: 412 „Клиенти по аванси“ (получени аванси, пасив) стоеше във „Вземания“, а 402
--      „Доставчици по аванси“ (предоставени аванси, актив) — в „Задължения“. Редовете си разменят
--      кода и сметката: A_2_2_412 → A_2_2_402 (402), P_3_3_402 → P_3_3_412 (412); в МСС
--      MA_2_2_412 → MA_2_2_402, MP_3_2_402 → MP_3_2_412. Сборните редове сочат новите кодове.
--      Наборът от сметки (account_set) на двата реда се изчиства: изчислението сече LIKE по кода с
--      него и старата сметка би останала (виж BgAccountingReportValueMoveLineServiceImpl).
--
-- КОГА. В automated.txt. Само данни. Безопасен за ПРЕДИШНАТА версия: тя търси LIKE '702@30', не
-- намира сметка и новият ред е 0 — ОПР остава такава, каквато беше; размяната 402/412 е обикновен
-- ред по сметка. Нова база: редовете идват от новия CSV и файлът е празен ход.
--
-- ИДЕМПОТЕНТЕН: второто пускане не намира нищо за смяна. Транзакцията и ON_ERROR_STOP — от
-- обвивката; файлът няма BEGIN/COMMIT. Проверката в края връща всичко назад при остатък.

DO $fix$
DECLARE
  r record;
  v_new_id bigint;
  n_added int := 0;
  n_rules int := 0;
  n_swapped int := 0;
  n int;
BEGIN
  -- ── 1 · Себестойността на продадените стоки: скрит ред до 6091, в „Други разходи“ и „в т.ч.“ ──
  FOR r IN
    SELECT src.*, x.new_code, x.new_import, x.total_code, x.shown_code, x.shown_import,
           tot.id AS total_id, tot.sequence AS total_sequence, tot.type_select AS total_type,
           tot.style_select AS total_style, tot.import_origin AS total_origin,
           tot.import_id AS total_import_id
      FROM (VALUES ('E_1_7_6091', 'E_1_7_STOCK', '2788', 'E_ST1_7', 'E_ST1_7_A', '2790'),
                   ('MO_6091', 'MO_STOCK', '2789', 'MO_ST_21931', 'MO_ST_21931_A', '2791'))
           AS x(src_code, new_code, new_import, total_code, shown_code, shown_import)
      JOIN account_accounting_report_config_line src ON src.code = x.src_code
      JOIN account_accounting_report_config_line tot
        ON tot.report_type = src.report_type AND tot.code = x.total_code
     WHERE src.report_type IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                        WHERE o.report_type = src.report_type AND o.code = x.new_code)
  LOOP
    -- „в т.ч.“ застава веднага под „Други разходи“: следващите редове слизат с едно.
    UPDATE account_accounting_report_config_line
       SET sequence = sequence + 1, version = COALESCE(version, 0) + 1
     WHERE report_type = r.report_type AND sequence > r.total_sequence;
    INSERT INTO account_accounting_report_config_line (
      id, version, archived, created_on, updated_on, created_by, updated_by,
      import_id, import_origin, process_instance_id, attrs,
      report_type, report_type_column, sequence, code, label,
      type_select, rule_type_select, style_select, detail_by_select, result_select,
      negate_value, rule, account_code, analytic_account_code,
      hidden, not_computed_if_intersect, balance_before_period
    ) VALUES (
      nextval('public.account_accounting_report_config_line_seq'), 0, false, now(), NULL, NULL, NULL,
      CASE WHEN r.total_import_id ~ '\d+$'
           THEN regexp_replace(r.total_import_id, '\d+$', r.shown_import) END,
      r.total_origin, NULL, NULL,
      r.report_type, NULL, r.total_sequence + 1, r.shown_code,
      '      в т.ч. балансова стойност на продадените активи',
      r.total_type, 3, r.total_style, 0, 0,
      false, r.new_code, NULL, NULL,
      false, false, false
    );

    v_new_id := nextval('public.account_accounting_report_config_line_seq');
    INSERT INTO account_accounting_report_config_line (
      id, version, archived, created_on, updated_on, created_by, updated_by,
      import_id, import_origin, process_instance_id, attrs,
      report_type, report_type_column, sequence, code, label,
      type_select, rule_type_select, style_select, detail_by_select, result_select,
      negate_value, rule, account_code, analytic_account_code,
      hidden, not_computed_if_intersect, balance_before_period
    ) VALUES (
      v_new_id, 0, false, now(), NULL, NULL, NULL,
      CASE WHEN r.import_id ~ '\d+$' THEN regexp_replace(r.import_id, '\d+$', r.new_import) END,
      r.import_origin, NULL, NULL,
      r.report_type, NULL,
      -- скрит: последен в справката (поредността е уникална в справката; позицията не се вижда)
      (SELECT max(o.sequence) + 1 FROM account_accounting_report_config_line o
        WHERE o.report_type = r.report_type),
      r.new_code, NULL,
      r.type_select, r.rule_type_select, r.style_select, r.detail_by_select, r.result_select,
      false, NULL, '702@30,706@30', NULL,
      true, r.not_computed_if_intersect, r.balance_before_period
    );
    n_added := n_added + 1;
  END LOOP;

  UPDATE account_accounting_report_config_line t
     SET rule = t.rule || ' + ' || x.new_code, version = COALESCE(t.version, 0) + 1
    FROM (VALUES ('E_ST1_7', 'E_1_7_STOCK'), ('MO_ST_21931', 'MO_STOCK')) AS x(total_code, new_code)
   WHERE t.code = x.total_code
     AND t.rule IS NOT NULL
     AND t.rule !~ ('\m' || x.new_code || '\M')
     AND EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                  WHERE o.report_type = t.report_type AND o.code = x.new_code);
  GET DIAGNOSTICS n_rules = ROW_COUNT;

  -- ── 2 · 402/412 на верните страни на баланса (НСС 28 и МСС 46) ──────────────────────────────
  FOR r IN
    SELECT l.id, l.report_type, x.old_code, x.new_code, x.new_account
      FROM (VALUES ('A_2_2_412', 'A_2_2_402', '412', '402'),
                   ('P_3_3_402', 'P_3_3_412', '402', '412'),
                   ('MA_2_2_412', 'MA_2_2_402', '412', '402'),
                   ('MP_3_2_402', 'MP_3_2_412', '402', '412')) AS x(old_code, new_code, old_account, new_account)
      JOIN account_accounting_report_config_line l
        ON l.code = x.old_code AND l.account_code = x.old_account
     WHERE l.report_type IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                        WHERE o.report_type = l.report_type AND o.code = x.new_code)
  LOOP
    UPDATE account_accounting_report_config_line
       SET code = r.new_code, account_code = r.new_account, version = COALESCE(version, 0) + 1
     WHERE id = r.id;
    DELETE FROM account_accounting_report_config_line_account_set
     WHERE account_accounting_report_config_line = r.id;
    UPDATE account_accounting_report_config_line t
       SET rule = regexp_replace(t.rule, '\m' || r.old_code || '\M', r.new_code, 'g'),
           version = COALESCE(t.version, 0) + 1
     WHERE t.report_type = r.report_type
       AND t.rule ~ ('\m' || r.old_code || '\M');
    n_swapped := n_swapped + 1;
  END LOOP;

  RAISE NOTICE 'BG_COA справки: нови редове за себестойността %, сборни редове %, разменени редове 402/412 %',
    n_added, n_rules, n_swapped;

  -- ── 3 · Само отчет: статиите за пропорционалния ред при извеждане с продажната цена ───────────
  FOR r IN
    SELECT m.status_select AS status, count(*) AS n, sum(amt.debit - l.depreciation) AS extra
      FROM account_fixed_asset_line l
      JOIN account_fixed_asset fa ON fa.id = l.fixed_asset
      JOIN account_move m ON m.id = l.depreciation_account_move
      JOIN (SELECT ml.move, sum(ml.debit) AS debit FROM account_move_line ml GROUP BY ml.move) amt
        ON amt.move = m.id
     WHERE fa.disposal_date IS NOT NULL AND l.depreciation_date = fa.disposal_date
       AND amt.debit <> l.depreciation AND amt.debit = fa.disposal_value
     GROUP BY m.status_select ORDER BY 1
  LOOP
    RAISE NOTICE 'НЕ СЕ ПИПА: % статии в статус % (1 — чернова, 2 — в дневника, 3 — осчетоводена) за пропорционалния ред при извеждане са с продажната цена вместо с амортизацията на реда — разлика %',
      r.n, r.status, r.extra;
  END LOOP;
END $fix$;

-- Проверка: не остава справка, която този файл е трябвало да поправи.
DO $verify$
DECLARE
  left_over int;
BEGIN
  SELECT count(*) INTO left_over FROM (
    -- ОПР със сборен ред „Други разходи“, но без реда за себестойността или без него в сбора
    SELECT t.report_type
      FROM account_accounting_report_config_line t
      JOIN (VALUES ('E_ST1_7', 'E_1_7_STOCK'), ('MO_ST_21931', 'MO_STOCK')) AS x(total_code, new_code)
        ON t.code = x.total_code
     WHERE t.report_type IS NOT NULL
       AND (t.rule IS NULL OR t.rule !~ ('\m' || x.new_code || '\M')
            OR NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                            WHERE o.report_type = t.report_type AND o.code = x.new_code
                              AND o.account_code = '702@30,706@30')
            OR NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                            WHERE o.report_type = t.report_type AND o.code = x.total_code || '_A'
                              AND o.rule = x.new_code))
    UNION ALL
    -- редове с размяната отпреди
    SELECT l.report_type
      FROM account_accounting_report_config_line l
     WHERE l.report_type IS NOT NULL
       AND ((l.code IN ('A_2_2_412', 'MA_2_2_412') AND l.account_code = '412')
         OR (l.code IN ('P_3_3_402', 'MP_3_2_402') AND l.account_code = '402'))
    UNION ALL
    -- сбор, който още сочи стар код
    SELECT t.report_type
      FROM account_accounting_report_config_line t
     WHERE t.report_type IS NOT NULL
       AND t.rule ~ '\m(A_2_2_412|P_3_3_402|MA_2_2_412|MP_3_2_402)\M'
       AND NOT EXISTS (SELECT 1 FROM account_accounting_report_config_line o
                        WHERE o.report_type = t.report_type
                          AND o.code IN ('A_2_2_412', 'P_3_3_402', 'MA_2_2_412', 'MP_3_2_402'))
  ) x;
  IF left_over > 0 THEN
    RAISE EXCEPTION 'BG_COA справки: остават % справки без поправката (себестойност / 402–412)', left_over;
  END IF;
END $verify$;

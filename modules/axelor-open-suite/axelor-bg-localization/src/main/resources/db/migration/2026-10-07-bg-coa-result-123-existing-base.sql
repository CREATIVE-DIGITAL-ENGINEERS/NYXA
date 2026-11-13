-- BG_COA: сметките за резултат в AccountConfig → 123 „Печалби и загуби от текущата година“ — върху
-- СЪЩЕСТВУВАЩА база. Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. Годишното приключване на Axelor (AccountingCloseAnnualServiceImpl#generateResultMove)
-- пише резултата на годината в началото на N+1: загуба Дт resultLossAccount / Кт 9000, печалба
-- Дт 9000 / Кт resultProfitAccount. В BG_COA те бяха 1222 „Непокрита загуба от минали години“ и
-- 122 „Неразпределена печалба от минали години“ — тоест резултатът прескачаше 123 и влизаше
-- направо в натрупания резултат, преди решението за разпределение. По българската практика той
-- стои в 123 до решението; прехвърлянето 123 → 122/121 е ОТДЕЛНА РЪЧНА статия по него.
--
-- КАКВО СЕ ПИПА: `result_profit_account` и `result_loss_account` на AccountConfig на дружествата с
-- BG план (опори 4551 и 4552), `version + 1`, `updated_on`. Само ако сегашната сметка е ТОЧНО
-- старата: печалба 122 (seed-ът) или 1221; загуба 1222. Друга (или празна) → `ЧУЖДО`, не се пипа.
--
-- ⚠️ СТАТИИ НЕ СЕ ПИПАТ. Дружество, което вече е приключило година през Axelor, носи резултатни
-- статии по 122/1222 — те се ДОКЛАДВАТ (брой, сума) и остават. `profit_account` (123 за clearance
-- на надплащания) не се пипа.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-coa-result-123-existing-base.sql

BEGIN;

DO $mig$
DECLARE
  c            record;
  v_cfg        bigint;
  v_123        bigint;
  v_profit     text;
  v_loss       text;
  v_posted     bigint;
  v_sum        numeric;
  v_companies  integer := 0;
  n_changed    integer := 0;
  n_skipped    integer := 0;
  n_foreign    integer := 0;
  n_closed     integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/123 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    RAISE NOTICE 'BG_COA/123 ── фирма [%] % (id=%)', c.ccode, c.cname, c.cid;

    SELECT cfg.id, pa.code, la.code INTO v_cfg, v_profit, v_loss
      FROM account_account_config cfg
      LEFT JOIN account_account pa ON pa.id = cfg.result_profit_account
      LEFT JOIN account_account la ON la.id = cfg.result_loss_account
     WHERE cfg.company = c.cid;
    IF NOT FOUND THEN
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'BG_COA/123 ⚠ ЧУЖДО [%] — няма AccountConfig', c.ccode;
      CONTINUE;
    END IF;

    SELECT a.id INTO v_123 FROM account_account a WHERE a.company = c.cid AND a.code = '123';
    IF NOT FOUND THEN
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'BG_COA/123 ⚠ ЧУЖДО [%] — сметка 123 я няма; не се пипа', c.ccode;
      CONTINUE;
    END IF;

    -- ДОКЛАД, не гейт: вече приключени години през Axelor (резултатни статии в N+1).
    SELECT count(ml.id), coalesce(sum(ml.debit - ml.credit), 0) INTO v_posted, v_sum
      FROM account_move_line ml
      JOIN account_move m ON m.id = ml.move
      JOIN account_account a ON a.id = ml.account
     WHERE m.company = c.cid AND m.functional_origin_select = 1 AND m.status_select IN (2, 3)
       AND a.code IN ('122', '1221', '1222');
    IF v_posted > 0 THEN
      n_closed := n_closed + 1;
      RAISE NOTICE 'BG_COA/123 ⚠ ПРИКЛЮЧВАНО [%] — % реда по 122/1221/1222, салдо (Dt−Kt) = %',
        c.ccode, v_posted, to_char(v_sum, 'FM999999999990.00');
    END IF;

    -- Печалба
    IF v_profit = '123' THEN
      n_skipped := n_skipped + 1;
      RAISE NOTICE 'BG_COA/123 skip    [%] печалба — вече е 123', c.ccode;
    ELSIF v_profit IN ('122', '1221') THEN
      UPDATE account_account_config
         SET result_profit_account = v_123, version = coalesce(version, 0) + 1, updated_on = now()
       WHERE id = v_cfg;
      n_changed := n_changed + 1;
      RAISE NOTICE 'BG_COA/123 CHANGED [%] печалба % → 123', c.ccode, v_profit;
    ELSE
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'BG_COA/123 ⚠ ЧУЖДО [%] печалба = % — не се пипа', c.ccode,
        coalesce(v_profit, 'NULL');
    END IF;

    -- Загуба
    IF v_loss = '123' THEN
      n_skipped := n_skipped + 1;
      RAISE NOTICE 'BG_COA/123 skip    [%] загуба — вече е 123', c.ccode;
    ELSIF v_loss = '1222' THEN
      UPDATE account_account_config
         SET result_loss_account = v_123, version = coalesce(version, 0) + 1, updated_on = now()
       WHERE id = v_cfg;
      n_changed := n_changed + 1;
      RAISE NOTICE 'BG_COA/123 CHANGED [%] загуба 1222 → 123', c.ccode;
    ELSE
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'BG_COA/123 ⚠ ЧУЖДО [%] загуба = % — не се пипа', c.ccode,
        coalesce(v_loss, 'NULL');
    END IF;
  END LOOP;

  IF v_companies = 0 THEN
    RAISE NOTICE 'BG_COA/123: нула фирми с BG сметкоплан (опори 4551 и 4552). Нищо за правене.';
  END IF;
  RAISE NOTICE 'BG_COA/123 ═══ край ═══ фирми=% | сменени=% | skip=% | чужди=% | приключвали=%',
    v_companies, n_changed, n_skipped, n_foreign, n_closed;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
-- Нито една BG фирма не сочи вече старата стойност (122/1221 за печалба, 1222 за загуба).
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, pa.code AS profit, la.code AS loss
      FROM base_company co
      JOIN account_account_config cfg ON cfg.company = co.id
      LEFT JOIN account_account pa ON pa.id = cfg.result_profit_account
      LEFT JOIN account_account la ON la.id = cfg.result_loss_account
     WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '123')
  LOOP
    IF x.profit IN ('122', '1221') OR x.loss = '1222' THEN
      v_bad := v_bad || format(E'\n  · [%s] печалба=%s загуба=%s', x.ccode, x.profit, x.loss);
    END IF;
  END LOOP;
  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/123: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/123 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

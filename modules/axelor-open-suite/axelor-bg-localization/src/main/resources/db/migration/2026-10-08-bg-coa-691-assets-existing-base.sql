-- ДМА: брак и липса НЕ на 691 — върху СЪЩЕСТВУВАЩА база. Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. Всяко отписване, което не е продажба, Axelor осчетоводява по
-- FixedAssetCategory.approExtraordDepreciationExpenseAccount (FixedAssetLineMoveServiceImpl
-- #generateDisposalMove). В BG_COA това беше 691, а по NRA_Nom_Accounts (1.0.2) 691 е „Разходи от
-- отписване на кредитни експозиции“ — тоест брак и липса на ДМА излизаха в SAF-T като отписани
-- кредитни експозиции. Правилото:
--     унищожен актив (брак, непреодолима сила) → 60908 „Балансова стойност на отписани дълготрайни
--                                                 активи“
--     изчезнал актив (липса)                   → 60905 „Разходи за липси и брак на активи“
--
-- КАК — най-малката намеса, без Java в избора на сметка:
--   1. категориите на дружествата с BG план: approExtraordDepreciationExpenseAccount 691 → 60905
--      (това е подразбирането — изчезнал актив и всеки резервен случай);
--   2. причините BG_SCRAP и BG_FORCE: bgRemovalAccountCode → 60908 (пренасочването, по което
--      BG_SHORT_REC вече отива на 442). ⚠️ Причините са ГЛОБАЛНИ и идват от seed-а при всеки boot
--      (DataInitSync); тук е само резервът за база без синхронизация.
--
-- ⚠️ САМО ТОЧНО СТАРАТА СТОЙНОСТ: категория, която не сочи 691, и причина с непразен код → ЧУЖДО,
-- не се пипа. Статии и салда — НЕ: заварените отписвания по 691 остават (виж db/checks/).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-691-assets-existing-base.sql

BEGIN;

DO $mig$
DECLARE
  c            record;
  r            record;
  v_691        bigint;
  v_60905      bigint;
  v_cnt        integer;
  v_code       text;
  v_companies  integer := 0;
  n_cat        integer := 0;
  n_cat_skip   integer := 0;
  n_cat_other  integer := 0;
  n_reason     integer := 0;
  n_reason_skip integer := 0;
  n_reason_other integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/691 ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  -- ── 1 · КАТЕГОРИИТЕ, ПО ДРУЖЕСТВО ─────────────────────────────────────────────────────────────
  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.name AS cname
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    SELECT a.id INTO v_691 FROM account_account a WHERE a.company = c.cid AND a.code = '691';
    SELECT a.id INTO v_60905 FROM account_account a WHERE a.company = c.cid AND a.code = '60905';

    SELECT count(*) INTO v_cnt FROM account_fixed_asset_category f
     WHERE f.company = c.cid AND f.appro_extraord_depreciation_expense_account = v_60905;
    n_cat_skip := n_cat_skip + v_cnt;

    SELECT count(*) INTO v_cnt FROM account_fixed_asset_category f
     WHERE f.company = c.cid
       AND f.appro_extraord_depreciation_expense_account IS DISTINCT FROM v_691
       AND f.appro_extraord_depreciation_expense_account IS DISTINCT FROM v_60905;
    IF v_cnt > 0 THEN
      n_cat_other := n_cat_other + v_cnt;
      RAISE NOTICE 'BG_COA/691 ⚠ ЧУЖДО [%] % категории сочат друга сметка — не се пипат', c.ccode, v_cnt;
    END IF;

    IF v_691 IS NULL THEN
      CONTINUE;
    END IF;
    IF v_60905 IS NULL THEN
      SELECT count(*) INTO v_cnt FROM account_fixed_asset_category f
       WHERE f.company = c.cid AND f.appro_extraord_depreciation_expense_account = v_691;
      n_cat_other := n_cat_other + v_cnt;
      RAISE NOTICE 'BG_COA/691 ⚠ ЧУЖДО [%] няма сметка 60905 — % категории остават на 691', c.ccode, v_cnt;
      CONTINUE;
    END IF;

    UPDATE account_fixed_asset_category f
       SET appro_extraord_depreciation_expense_account = v_60905,
           version = coalesce(f.version, 0) + 1, updated_on = now()
     WHERE f.company = c.cid AND f.appro_extraord_depreciation_expense_account = v_691;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    n_cat := n_cat + v_cnt;
    IF v_cnt > 0 THEN
      RAISE NOTICE 'BG_COA/691 CHANGED [%] % категории: 691 → 60905', c.ccode, v_cnt;
    END IF;
  END LOOP;

  -- ── 2 · ПРИЧИНИТЕ (ГЛОБАЛНИ) ──────────────────────────────────────────────────────────────────
  IF to_regclass('public.account_asset_disposal_reason') IS NOT NULL THEN
    FOR r IN SELECT unnest(ARRAY['BG_SCRAP', 'BG_FORCE']) AS code LOOP
      SELECT d.bg_removal_account_code INTO v_code
        FROM account_asset_disposal_reason d WHERE d.code = r.code;
      IF NOT FOUND THEN
        RAISE NOTICE 'BG_COA/691 ⚠ missing % — няма причина; DataInitSync я внася', r.code;
      ELSIF v_code = '60908' THEN
        n_reason_skip := n_reason_skip + 1;
      ELSIF coalesce(btrim(v_code), '') = '' THEN
        UPDATE account_asset_disposal_reason
           SET bg_removal_account_code = '60908', version = coalesce(version, 0) + 1,
               updated_on = now()
         WHERE code = r.code;
        n_reason := n_reason + 1;
        RAISE NOTICE 'BG_COA/691 CHANGED % : сметка по подразбиране → 60908', r.code;
      ELSE
        n_reason_other := n_reason_other + 1;
        RAISE NOTICE 'BG_COA/691 ⚠ ЧУЖДО % носи „%“ — не се пипа', r.code, v_code;
      END IF;
    END LOOP;
  END IF;

  RAISE NOTICE 'BG_COA/691 ═══ край ═══ фирми=% | категории сменени=% skip=% чужди=%',
    v_companies, n_cat, n_cat_skip, n_cat_other;
  RAISE NOTICE 'BG_COA/691                  причини сменени=% skip=% чужди=%',
    n_reason, n_reason_skip, n_reason_other;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
-- Нито една категория на BG дружество с 60905 в чарта не сочи вече 691; BG_SCRAP/BG_FORCE не
-- стоят с празен код.
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, count(*) AS n
      FROM account_fixed_asset_category f
      JOIN base_company co ON co.id = f.company
      JOIN account_account a ON a.id = f.appro_extraord_depreciation_expense_account
     WHERE a.code = '691'
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4552')
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '60905')
     GROUP BY co.id, co.code
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] %s категории сочат още 691', x.ccode, x.n);
  END LOOP;

  IF to_regclass('public.account_asset_disposal_reason') IS NOT NULL THEN
    FOR x IN
      SELECT d.code FROM account_asset_disposal_reason d
       WHERE d.code IN ('BG_SCRAP', 'BG_FORCE') AND coalesce(btrim(d.bg_removal_account_code), '') = ''
    LOOP
      v_bad := v_bad || format(E'\n  · %s е още без сметка по подразбиране', x.code);
    END LOOP;
  END IF;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/691: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/691 ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

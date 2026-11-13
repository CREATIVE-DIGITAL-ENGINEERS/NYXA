-- SAF-T G6a: BgSafTaxMapping — четири реда с код от източник — РЕЗЕРВЕН път за заварени бази.
-- Идемпотентно, транзакционно, fail-loud. Шаблонът е 2026-10-08-bg-saft-remap-existing-base.sql.
--
-- ⚠️ ОБИКНОВЕНО НЕ Е НУЖЕН. `bglocalization_bg_saf_tax_mapping` е ГЛОБАЛНА и се внася от seed-а при
-- ВСЕКИ boot (DataInitSyncService, upsert по importId SAFTTAX-…), когато `datainit.sync.modules`
-- съдържа axelor-bg-localization. App Install НЕ стига: при initDataLoaded = true не внася наново.
-- Този файл е за база, в която синхронизацията е изключена.
--
-- ЦЕЛТА (seed-ът на G6a, дословно):
--   BG_ZRS    празен            → 100214 (TAX-IMP; Блок Б к.19)
--   BG_OSS_EU празен            → 100218 (TAX-IMP „чл. 69, ал. 2 … дистанционни продажби“; к.23)
--   BG_MOSS   празен            → 100218 (ЗДДС чл. 21 ал. 6 → чл. 69 ал. 2 т. 1; к.23)
--   BG_ZRP    100331, динамичен → 100330, НЕ динамичен (QA с. 81)
--
-- КАКВО СЕ ПИПА: `saf_tax_code`, `is_dynamic`, `note`, `full_name`, `version + 1`, `updated_on`.
-- Стар ред = точно старата стойност (празно/NULL, а за BG_ZRP — 100331). Друга стойност → `ЧУЖДО`,
-- не се пипа. ⚠️ DataInitSync презаписва чуждото при следващия boot — това е seed, не настройка.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-15-bg-saft-tax-mapping-existing-base.sql

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_safttax;
CREATE TEMP TABLE bg_safttax (
  tax text NOT NULL, old_code text, new_code text NOT NULL, new_dynamic boolean NOT NULL,
  new_note text NOT NULL);
INSERT INTO bg_safttax VALUES
  ('BG_ZRS', NULL, '100214', false, 'Продажби с нулева ставка по глава трета — к.19 на Блок Б (BgVatSalesTaxCodeMapping), TAX-IMP 100214 „ДО на доставките със ставка 0 % по глава трета от ЗДДС“. ВОД е BG_VOD (100215). Същото като рутера на фактурите (G6a).'),
  ('BG_OSS_EU', NULL, '100218', false, 'OSS (дистанционни продажби в ЕС) — к.23 на Блок Б, TAX-IMP 100218 „ДО на доставки по чл. 69, ал. 2 ЗДДС, вкл. … дистанционни продажби, с място на изпълнение на територията на друга държава членка“ (ЗДДС чл. 69 ал. 2 т. 1). Същото като рутера на фактурите (G6a).'),
  ('BG_MOSS', NULL, '100218', false, 'MOSS — далекосъобщителни, радио/ТВ и електронни услуги на незадължени лица в ЕС, място на изпълнение по ЗДДС чл. 21 ал. 6 — „доставки в друга държава“ по чл. 69 ал. 2 т. 1: к.23 на Блок Б, поле 02-18 (ППЗДДС Прил. № 12), TAX-IMP 100218. НЕ 100217 — той е за услуги по чл. 21 ал. 2 (B2B). Същото като рутера на фактурите (G6a).'),
  ('BG_ZRP', '100331', '100330', false, 'Покупка без начислен ДДС (0 %) — 100330 независимо от кредита: QA с. 81 („се посочва при отразяването на фактури … без начислен ДДС“), TAX-IMP 100330 „… без право на данъчен кредит или без данък“, к.9 на дневника за покупки (ППЗДДС Прил. № 11). Същото като рутера на фактурите (G6a).');

DO $mig$
DECLARE
  r          record;
  v_id       bigint;
  v_code     text;
  v_dynamic  boolean;
  n_changed  integer := 0;
  n_skipped  integer := 0;
  n_foreign  integer := 0;
  n_missing  integer := 0;
BEGIN
  RAISE NOTICE 'SAFT/TAX ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.bglocalization_bg_saf_tax_mapping') IS NULL THEN
    RAISE NOTICE 'SAFT/TAX: таблицата я няма — първият boot я създава и внася seed-а. Нищо за правене.';
    RETURN;
  END IF;

  FOR r IN SELECT * FROM bg_safttax ORDER BY tax LOOP
    SELECT m.id, nullif(trim(m.saf_tax_code), ''), m.is_dynamic INTO v_id, v_code, v_dynamic
      FROM bglocalization_bg_saf_tax_mapping m
     WHERE m.tax_code = r.tax;

    IF NOT FOUND THEN
      n_missing := n_missing + 1;
      RAISE NOTICE 'SAFT/TAX ⚠ missing % — няма ред; DataInitSync ще го внесе', r.tax;
      CONTINUE;
    END IF;

    IF v_code IS NOT DISTINCT FROM r.new_code
       AND coalesce(v_dynamic, false) = r.new_dynamic THEN
      n_skipped := n_skipped + 1;
      RAISE NOTICE 'SAFT/TAX skip    % — вече е %', r.tax, r.new_code;
      CONTINUE;
    END IF;

    IF v_code IS DISTINCT FROM r.old_code AND v_code IS DISTINCT FROM r.new_code THEN
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'SAFT/TAX ⚠ ЧУЖДО % → „%“ — нито %, нито %; не се пипа', r.tax,
        coalesce(v_code, 'празно'), coalesce(r.old_code, 'празно'), r.new_code;
      CONTINUE;
    END IF;

    UPDATE bglocalization_bg_saf_tax_mapping
       SET saf_tax_code = r.new_code, is_dynamic = r.new_dynamic, note = r.new_note,
           full_name = r.tax || ' → ' || r.new_code,
           version = coalesce(version, 0) + 1, updated_on = now()
     WHERE id = v_id;
    n_changed := n_changed + 1;
    RAISE NOTICE 'SAFT/TAX CHANGED % : % → %', r.tax, coalesce(v_code, 'празно'), r.new_code;
  END LOOP;

  RAISE NOTICE 'SAFT/TAX ═══ край ═══ сменени=% | skip=% | чужди=% | липсващи=%',
    n_changed, n_skipped, n_foreign, n_missing;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  IF to_regclass('public.bglocalization_bg_saf_tax_mapping') IS NULL THEN
    RETURN;
  END IF;
  FOR x IN
    SELECT m.tax_code, nullif(trim(m.saf_tax_code), '') AS code, m.is_dynamic, m.full_name,
           t.old_code, t.new_code, t.new_dynamic
      FROM bglocalization_bg_saf_tax_mapping m
      JOIN bg_safttax t ON t.tax = m.tax_code
     ORDER BY m.tax_code
  LOOP
    IF x.code IS NOT DISTINCT FROM x.old_code THEN
      v_bad := v_bad || format(E'\n  · %s е още със стария код %s', x.tax_code,
        coalesce(x.old_code, 'празно'));
    ELSIF x.code = x.new_code AND (coalesce(x.is_dynamic, false) <> x.new_dynamic
          OR x.full_name IS DISTINCT FROM x.tax_code || ' → ' || x.new_code) THEN
      v_bad := v_bad || format(E'\n  · %s: is_dynamic=%s, full_name %L', x.tax_code,
        x.is_dynamic, x.full_name);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'SAFT/TAX: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'SAFT/TAX ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

-- SAF-T мапинг на BG_COA: 10 изрични реда — РЕЗЕРВЕН път за заварени бази. Идемпотентно,
-- транзакционно, fail-loud.
--
-- ⚠️ ОБИКНОВЕНО НЕ Е НУЖЕН. `bglocalization_bg_saf_account_mapping` е ГЛОБАЛНА (без дружество) и се
-- внася от `apps/init-data/*/bg_saf_account_mapping.csv` при ВСЕКИ boot (DataInitSync, upsert по
-- importId, `datainit.sync.modules = axelor-bg-localization`). Тоест новият код сам довежда
-- редовете до целта при първия старт. Този файл е за база, където синхронизацията е изключена.
--
-- ЗАЩО. Навиването по префикс прати сметки в НАП код със същите цифри, но друг смисъл. Целта е
-- дословно по NRA_Nom_Accounts (книгата 1.0.2):
--   6044 Разходи за командировки             604  → 60901 „Разходи за командировки“
--   1222 Непокрита загуба от минали години   122  → 121   „Непокрита загуба от минали години“
--   4551, 4553–4556 (ДОО, ДЗПО, фондовете)    455  → 461   „Разчети за задължително социално осигуряване“
--   4552 Разчети за здравно осигуряване       455  → 463   „Разчети за здравно осигуряване“
--   4621 Разчети по факторинг вземане         462  → 498   „Други дебитори“
--   4622 Разчети по факторинг задължение      462  → 499   „Други кредитори“
--
-- ⚠️ 4538/4539 ВЕЧЕ НЕ СА ТУК. Първоначално мапингът ги разменяше, защото BG_COA ги носеше с
-- разменени имена. Счетоводителят потвърди, че сметките са тези с обратен смисъл (4538 = за
-- възстановяване, 4539 = за внасяне), поправиха се имената в BG_COA, а мапингът е IDENTITY.
-- Старото разменяне НЕ бива да се пуска повече — виж 2026-10-17-bg-coa-vat-4538-4539-*.sql.
--
-- КАКВО СЕ ПИПА: `nra_code`, `rule_select` (→ EXPLICIT), `full_name` (съхранено изчислимо:
-- `accountCode → nraCode`), `version + 1`, `updated_on`. Сметки, статии, салда — НЕ.
-- Ред с НАП код, различен и от стария, и от новия → `ЧУЖДО`, не се пипа.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-07-bg-saft-mapping-existing-base.sql

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_saftmap;
CREATE TEMP TABLE bg_saftmap (code text NOT NULL, old_nra text NOT NULL, new_nra text NOT NULL);
INSERT INTO bg_saftmap VALUES
  ('6044', '604', '60901'), ('1222', '122', '121'),
  ('4551', '455', '461'), ('4553', '455', '461'), ('4554', '455', '461'),
  ('4555', '455', '461'), ('4556', '455', '461'), ('4552', '455', '463'),
  ('4621', '462', '498'), ('4622', '462', '499');

DO $mig$
DECLARE
  r          record;
  v_id       bigint;
  v_nra      text;
  v_rule     text;
  n_changed  integer := 0;
  n_skipped  integer := 0;
  n_foreign  integer := 0;
  n_missing  integer := 0;
BEGIN
  RAISE NOTICE 'SAFT/MAP ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.bglocalization_bg_saf_account_mapping') IS NULL THEN
    RAISE NOTICE 'SAFT/MAP: таблицата я няма — първият boot я създава и внася seed-а. Нищо за правене.';
    RETURN;
  END IF;

  FOR r IN SELECT * FROM bg_saftmap ORDER BY code LOOP
    SELECT m.id, m.nra_code, m.rule_select INTO v_id, v_nra, v_rule
      FROM bglocalization_bg_saf_account_mapping m
     WHERE m.account_code = r.code;

    IF NOT FOUND THEN
      n_missing := n_missing + 1;
      RAISE NOTICE 'SAFT/MAP ⚠ missing % — няма ред; DataInitSync ще го внесе', r.code;
      CONTINUE;
    END IF;

    IF v_nra = r.new_nra THEN
      n_skipped := n_skipped + 1;
      RAISE NOTICE 'SAFT/MAP skip    % — вече е %', r.code, r.new_nra;
      CONTINUE;
    END IF;

    IF v_nra IS DISTINCT FROM r.old_nra THEN
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'SAFT/MAP ⚠ ЧУЖДО % → „%“ — нито %, нито %; не се пипа', r.code,
        coalesce(v_nra, 'NULL'), r.old_nra, r.new_nra;
      CONTINUE;
    END IF;

    UPDATE bglocalization_bg_saf_account_mapping
       SET nra_code = r.new_nra, rule_select = 'EXPLICIT',
           full_name = r.code || ' → ' || r.new_nra,
           version = coalesce(version, 0) + 1, updated_on = now()
     WHERE id = v_id;
    n_changed := n_changed + 1;
    RAISE NOTICE 'SAFT/MAP CHANGED % : % (%) → % (EXPLICIT)', r.code, v_nra, v_rule, r.new_nra;
  END LOOP;

  RAISE NOTICE 'SAFT/MAP ═══ край ═══ сменени=% | skip=% | чужди=% | липсващи=%',
    n_changed, n_skipped, n_foreign, n_missing;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  IF to_regclass('public.bglocalization_bg_saf_account_mapping') IS NULL THEN
    RETURN;
  END IF;
  FOR x IN
    SELECT m.account_code, m.nra_code, m.full_name, t.old_nra, t.new_nra
      FROM bglocalization_bg_saf_account_mapping m
      JOIN bg_saftmap t ON t.code = m.account_code
     ORDER BY m.account_code
  LOOP
    IF x.nra_code = x.old_nra THEN
      v_bad := v_bad || format(E'\n  · %s сочи още стария %s', x.account_code, x.old_nra);
    ELSIF x.nra_code = x.new_nra
          AND x.full_name IS DISTINCT FROM x.account_code || ' → ' || x.new_nra THEN
      v_bad := v_bad || format(E'\n  · %s: full_name %L', x.account_code, x.full_name);
    END IF;
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'SAFT/MAP: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'SAFT/MAP ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

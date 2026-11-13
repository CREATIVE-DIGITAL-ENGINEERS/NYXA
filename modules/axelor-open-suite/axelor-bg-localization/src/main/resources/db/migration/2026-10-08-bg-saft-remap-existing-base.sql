-- SAF-T мапинг на BG_COA, PR C: 13 идентитета, чийто код в НАП значи друго — РЕЗЕРВЕН път за
-- заварени бази. Идемпотентно, транзакционно, fail-loud. Шаблонът е #285.
--
-- ⚠️ ОБИКНОВЕНО НЕ Е НУЖЕН. `bglocalization_bg_saf_account_mapping` е ГЛОБАЛНА и се внася от
-- seed-а при ВСЕКИ boot (DataInitSync, upsert по importId). Този файл е за база без синхронизация.
--
-- ЗАЩО. Сметките носят в BG_COA един смисъл, а еднаквият код в NRA_Nom_Accounts (1.0.2) — друг.
-- Сменя се САМО мапингът; кодовете и имената в BG_COA остават. Целта, дословно по НАП:
--   408 Доставчици по нефактурирани доставки       → 409   Други задължения към доставчици
--   456 Разчети с НОИ (болнични и обезщетения)      → 469   Други разчети с осигурители
--   481 Разчети по предстоящи плащания               → 483   Разчети по предстоящо плащане
--   103 Регистриран капитал                          → 101   Основен капитал, изискващ регистрация
--   455 Разчети за социално осигуряване              → 461   Разчети за задължително социално осигуряване
--   615 Разходи за организация и управление          → 614   Административни разходи
--   622 Разходи за финансови операции                → 629   Други финансови разходи
--   692 Корекции на разходи от минали години         → 60909 Други разходи
--   792 Корекции на приходи от минали години         → 709   Други приходи от дейността
--   722 Приходи от лихви по предоставени заеми       → 721   Приходи от лихви
--   723 Приходи от съучастия                         → 722   Приходи от съучастия
--   725 Приходи от ценни книжа                       → 723   Приходи от операции с финансови активи и инструменти
--   462 Разчети по транзитни плащания                → 499   Други кредитори
--
-- КАКВО СЕ ПИПА: `nra_code`, `rule_select` (→ EXPLICIT), `full_name`, `version + 1`, `updated_on`.
-- Ред с НАП код, различен и от стария, и от новия → `ЧУЖДО`, не се пипа.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-saft-remap-existing-base.sql

BEGIN;

DROP TABLE IF EXISTS pg_temp.bg_saftremap;
CREATE TEMP TABLE bg_saftremap (code text NOT NULL, old_nra text NOT NULL, new_nra text NOT NULL);
INSERT INTO bg_saftremap VALUES
  ('408', '408', '409'),
  ('456', '456', '469'),
  ('481', '481', '483'),
  ('103', '103', '101'),
  ('455', '455', '461'),
  ('615', '615', '614'),
  ('622', '622', '629'),
  ('692', '692', '60909'),
  ('792', '792', '709'),
  ('722', '722', '721'),
  ('723', '723', '722'),
  ('725', '725', '723'),
  ('462', '462', '499');

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
  RAISE NOTICE 'SAFT/REMAP ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.bglocalization_bg_saf_account_mapping') IS NULL THEN
    RAISE NOTICE 'SAFT/REMAP: таблицата я няма — първият boot я създава и внася seed-а. Нищо за правене.';
    RETURN;
  END IF;

  FOR r IN SELECT * FROM bg_saftremap ORDER BY code LOOP
    SELECT m.id, m.nra_code, m.rule_select INTO v_id, v_nra, v_rule
      FROM bglocalization_bg_saf_account_mapping m
     WHERE m.account_code = r.code;

    IF NOT FOUND THEN
      n_missing := n_missing + 1;
      RAISE NOTICE 'SAFT/REMAP ⚠ missing % — няма ред; DataInitSync ще го внесе', r.code;
      CONTINUE;
    END IF;

    IF v_nra = r.new_nra THEN
      n_skipped := n_skipped + 1;
      RAISE NOTICE 'SAFT/REMAP skip    % — вече е %', r.code, r.new_nra;
      CONTINUE;
    END IF;

    IF v_nra IS DISTINCT FROM r.old_nra THEN
      n_foreign := n_foreign + 1;
      RAISE NOTICE 'SAFT/REMAP ⚠ ЧУЖДО % → „%“ — нито %, нито %; не се пипа', r.code,
        coalesce(v_nra, 'NULL'), r.old_nra, r.new_nra;
      CONTINUE;
    END IF;

    UPDATE bglocalization_bg_saf_account_mapping
       SET nra_code = r.new_nra, rule_select = 'EXPLICIT',
           full_name = r.code || ' → ' || r.new_nra,
           version = coalesce(version, 0) + 1, updated_on = now()
     WHERE id = v_id;
    n_changed := n_changed + 1;
    RAISE NOTICE 'SAFT/REMAP CHANGED % : % (%) → % (EXPLICIT)', r.code, v_nra, v_rule, r.new_nra;
  END LOOP;

  RAISE NOTICE 'SAFT/REMAP ═══ край ═══ сменени=% | skip=% | чужди=% | липсващи=%',
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
      JOIN bg_saftremap t ON t.code = m.account_code
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
      E'SAFT/REMAP: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'SAFT/REMAP ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

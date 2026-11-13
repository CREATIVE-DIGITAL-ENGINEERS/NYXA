-- BG_COA: 691 получава името си по НАП — върху СЪЩЕСТВУВАЩА база. Идемпотентно, транзакционно,
-- fail-loud. Механизмът е същият като при …-names-2.
--
-- ЗАЩО ЧАК СЕГА. 691 „Извънредни разходи“ беше сметката на всяко отписване на ДМА, което не е
-- продажба. След 2026-10-08-bg-coa-691-assets (брак/непреодолима сила → 60908, липса → 60905) нищо
-- автоматично не пише в нея (BgCoa691NotPostedTest), и тя може да носи името си по NRA_Nom_Accounts
-- (1.0.2): „Разходи от отписване на кредитни експозиции“. ⚠️ ПУСКА СЕ СЛЕД …-691-assets.
--
-- КАКВО СЕ ПИПА: `name`, `label`, `version + 1`, `updated_on` на 691 във фирмите с BG план.
-- Само точно старото име; друго → РЪЧНО. Целевото име с остарял label → само label.
-- ⚠️ СТАТИИ НЕ СЕ МЕСТЯТ. Заварените отписвания по 691 остават там и се печатат като одит; реклас
-- към 60908/60905, ако е нужен, е РЕДОВНА статия (виж db/checks/…-posted-lines.sql).
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-08-bg-coa-691-name-existing-base.sql

BEGIN;

DO $mig$
DECLARE
  c            record;
  v_id         bigint;
  v_name       text;
  v_label      text;
  v_want       text;
  v_posted     bigint;
  v_balance    numeric;
  v_companies  integer := 0;
  n_renamed    integer := 0;
  n_relabeled  integer := 0;
  n_skipped    integer := 0;
  n_custom     integer := 0;
  c_old        constant text := 'Извънредни разходи';
  c_new        constant text := 'Разходи от отписване на кредитни експозиции';
BEGIN
  RAISE NOTICE 'BG_COA/691N ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  FOR c IN
    SELECT co.id AS cid, coalesce(co.code, '?') AS ccode, co.code AS raw_code
      FROM base_company co
     WHERE EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account a WHERE a.company = co.id AND a.code = '4552')
     ORDER BY co.id
  LOOP
    v_companies := v_companies + 1;
    SELECT a.id, a.name, a.label INTO v_id, v_name, v_label
      FROM account_account a WHERE a.company = c.cid AND a.code = '691';
    IF NOT FOUND THEN
      CONTINUE;
    END IF;
    v_want := '691_' || coalesce(c.raw_code, 'null') || ' - ' || c_new;

    IF v_name = c_new THEN
      IF v_label IS NOT DISTINCT FROM v_want THEN
        n_skipped := n_skipped + 1;
      ELSE
        UPDATE account_account
           SET label = v_want, version = coalesce(version, 0) + 1, updated_on = now()
         WHERE id = v_id;
        n_relabeled := n_relabeled + 1;
        RAISE NOTICE 'BG_COA/691N RELABEL [%] 691 — label „%“ → „%“', c.ccode,
          coalesce(v_label, 'NULL'), v_want;
      END IF;
      CONTINUE;
    END IF;

    IF v_name <> c_old THEN
      n_custom := n_custom + 1;
      RAISE NOTICE 'BG_COA/691N ⚠ РЪЧНО  [%] 691 носи „%“ — не се пипа', c.ccode, v_name;
      CONTINUE;
    END IF;

    -- ОДИТ, не гейт: статиите сочат сметката по id и не се местят.
    SELECT count(*), coalesce(sum(ml.debit), 0) - coalesce(sum(ml.credit), 0)
      INTO v_posted, v_balance
      FROM account_move_line ml JOIN account_move m ON m.id = ml.move
     WHERE ml.account = v_id AND m.status_select IN (2, 3);

    UPDATE account_account
       SET name = c_new, label = v_want, version = coalesce(version, 0) + 1, updated_on = now()
     WHERE id = v_id;
    n_renamed := n_renamed + 1;
    RAISE NOTICE 'BG_COA/691N RENAMED [%] 691 → „%“ · % осчетоводени реда, салдо (Dt−Kt) = %',
      c.ccode, c_new, v_posted, to_char(v_balance, 'FM999999999990.00');
  END LOOP;

  RAISE NOTICE 'BG_COA/691N ═══ край ═══ фирми=% | преименувани=% | само label=% | skip=% | ръчни=%',
    v_companies, n_renamed, n_relabeled, n_skipped, n_custom;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
DO $conv$
DECLARE
  v_bad  text := '';
  x      record;
BEGIN
  FOR x IN
    SELECT coalesce(co.code, '?') AS ccode, a.name, a.label,
           '691_' || coalesce(co.code, 'null') || ' - ' || a.name AS want
      FROM account_account a JOIN base_company co ON co.id = a.company
     WHERE a.code = '691'
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4551')
       AND EXISTS (SELECT 1 FROM account_account s WHERE s.company = co.id AND s.code = '4552')
  LOOP
    IF x.name = 'Извънредни разходи' THEN
      v_bad := v_bad || format(E'\n  · [%s] 691 носи още старото име', x.ccode);
    ELSIF x.name = 'Разходи от отписване на кредитни експозиции'
          AND x.label IS DISTINCT FROM x.want THEN
      v_bad := v_bad || format(E'\n  · [%s] 691: label %L вместо %L', x.ccode, x.label, x.want);
    END IF;
  END LOOP;
  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/691N: НЕ КОНВЕРГИРА към целевото състояние — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/691N ✔ конвергенцията е сверена';
END
$conv$;

COMMIT;

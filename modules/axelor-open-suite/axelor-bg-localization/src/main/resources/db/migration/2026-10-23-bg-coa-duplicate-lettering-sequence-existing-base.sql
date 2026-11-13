-- BG_COA: премахва ДУБЛИРАНИТЕ поредици за буквено маркиране (codeSelect='reconcileGroupDraft' и
-- 'reconcileGroupFinal') на СЪЩЕСТВУВАЩА база. Идемпотентно, транзакционно, fail-loud.
--
-- ЗАЩО. Дублирането на codeSelect='reconcile' (importId -101 срещу -65) се маха от
-- 2026-10-18-bg-coa-duplicate-reconcile-sequence-existing-base.sql. Същото дублиране има и при
-- двете поредици за буквено маркиране — base_sequence.csv на BG_COA внасяше по ДВЕ за всяка
-- фирма, при това с ОБЪРНАТИ регистри спрямо FRA_PCG и generic init-data:
--     importId 66  "Draft lettering"             reconcileGroupDraft  LOWERCASE  (каноничната)
--     importId 67  "Final lettering"             reconcileGroupFinal  UPPERCASE  (каноничната)
--     importId 103 "Чернова буквено маркиране"    reconcileGroupDraft  UPPERCASE  (дубликат)
--     importId 102 "Финално буквено маркиране"    reconcileGroupFinal  LOWERCASE  (дубликат)
--
-- ReconcileGroupSequenceServiceImpl.fillCodeFromSequence→SequenceService.getSequence(code, company)→
-- SequenceRepository.find(codeSelect, company) прави `.fetchOne()` без ORDER BY, затова при два реда
-- изборът е произволен. Ако черновата вземе каноничната 66 (LOWERCASE), а финалната — дубликата
-- 102 (също LOWERCASE), двата кода раздават ЕДНА И СЪЩА азбука от отделни броячи: черновата
-- изхабява 'aaaa', после финалната иска пак 'aaaa';
-- SequenceService.isSequenceAlreadyExisting отказва стойността ПРЕДИ да увеличи nextNum, затова
-- всеки следващ опит се проваля на същата стойност завинаги.
--
-- ОБХВАТ: само редове с import_id, завършващ на '-102'/'-103', чиято фирма носи и сестринския
-- '-67'/'-66' ред. Нито един ред не се трие, ако е бил ИЗПОЛЗВАН (SequenceVersion.next_num > 1) —
-- такъв случай СПИРА с грешка и иска ръчно решение. Съществуващите ReconcileGroup кодове и
-- използваните поредици не се пипат, не се преномерират и не се трият.
--
-- Пуска се РЪЧНО, след бекъп (виж придружаващия -verify.sql за сверка преди/след):
--
--     PGPASSWORD=... psql -h localhost -p 5432 -U nyxa -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-23-bg-coa-duplicate-lettering-sequence-existing-base.sql
--
-- Второто пускане не намира редове за триене и приключва като no-op.

BEGIN;

DO $mig$
DECLARE
  r           record;
  v_versions  integer;
  v_used      integer;
  n_deleted   integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/LETTERING_DUP ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.base_sequence') IS NULL THEN
    RAISE EXCEPTION 'BG_COA/LETTERING_DUP: таблица base_sequence липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  FOR r IN
    SELECT dup.id AS dup_id, dup.import_id AS dup_import_id, dup.code_select,
           dup.sequence_letters_type_select AS dup_case, dup.company AS company_id,
           co.code AS company_code, co.name AS company_name
      FROM base_sequence dup
      JOIN base_company co ON co.id = dup.company
      JOIN (VALUES ('reconcileGroupFinal', '-102', '-67'),
                   ('reconcileGroupDraft', '-103', '-66')) AS spec(code_select, dup_suffix, canon_suffix)
        ON spec.code_select = dup.code_select
       AND dup.import_id LIKE '%' || spec.dup_suffix
     WHERE EXISTS (
             SELECT 1 FROM base_sequence canon
              WHERE canon.company = dup.company
                AND canon.code_select = dup.code_select
                AND canon.import_id = REPLACE(dup.import_id, spec.dup_suffix, spec.canon_suffix))
     ORDER BY co.code, dup.code_select
  LOOP
    SELECT count(*), coalesce(max(next_num), 0)
      INTO v_versions, v_used
      FROM base_sequence_version
     WHERE sequence = r.dup_id;

    IF v_used > 1 THEN
      RAISE EXCEPTION
        'BG_COA/LETTERING_DUP: [%] поредица % (id=%, %) е ИЗПОЛЗВАНА (next_num=%) — не се трие автоматично. Раздадените букви са история: нужно е ръчно решение коя поредица остава.',
        r.company_code, r.dup_import_id, r.dup_id, r.code_select, v_used;
    END IF;

    DELETE FROM base_sequence_version WHERE sequence = r.dup_id;
    DELETE FROM base_sequence WHERE id = r.dup_id;
    n_deleted := n_deleted + 1;
    RAISE NOTICE 'BG_COA/LETTERING_DUP DELETED [%] % (id=%, %, %, фирма=%) — % версии изтрити',
      r.company_code, r.dup_import_id, r.dup_id, r.code_select, r.dup_case, r.company_name, v_versions;
  END LOOP;

  IF n_deleted = 0 THEN
    RAISE NOTICE 'BG_COA/LETTERING_DUP: няма дублирани поредици за буквено маркиране (вече приложено или base без BG_COA).';
  END IF;

  RAISE NOTICE 'BG_COA/LETTERING_DUP ═══ край ═══ изтрити=%', n_deleted;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
-- 1) по ТОЧНО ЕДНА поредица на фирма за всеки от двата кода;
-- 2) черновата и финалната да НЕ делят една азбука — иначе дефектът остава след триенето.
DO $conv$
DECLARE
  v_bad text := '';
  x     record;
BEGIN
  FOR x IN
    SELECT co.code AS ccode, s.code_select, count(*) AS n
      FROM base_sequence s
      JOIN base_company co ON co.id = s.company
     WHERE s.code_select IN ('reconcileGroupDraft', 'reconcileGroupFinal')
     GROUP BY co.code, s.code_select
    HAVING count(*) <> 1
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] има %s поредици %s (очаква се 1)', x.ccode, x.n, x.code_select);
  END LOOP;

  FOR x IN
    SELECT co.code AS ccode, d.sequence_letters_type_select AS shared
      FROM base_sequence d
      JOIN base_sequence f
        ON f.company = d.company
       AND f.code_select = 'reconcileGroupFinal'
      JOIN base_company co ON co.id = d.company
     WHERE d.code_select = 'reconcileGroupDraft'
       AND d.sequence_letters_type_select = f.sequence_letters_type_select
  LOOP
    v_bad := v_bad || format(
      E'\n  · [%s] черновата и финалната поредица са и двете %s — буквите ще се сблъскат пак', x.ccode, x.shared);
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/LETTERING_DUP: НЕ КОНВЕРГИРА — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/LETTERING_DUP ✔ сверено — по една чернова и една финална поредица на фирма, с различни азбуки';
END
$conv$;

COMMIT;

-- BG_COA: премахва ДУБЛИРАНАТА поредица codeSelect='reconcile' на СЪЩЕСТВУВАЩА база. Идемпотентно,
-- транзакционно, fail-loud.
--
-- ЗАЩО. base_sequence.csv на BG_COA внасяше ДВЕ поредици с codeSelect='reconcile' за всяка фирма:
--     importId 65  "Account reconcile"           yearlyResetOk=true   (каноничната — съвпада с
--                                                                       GBR_COA/FRA_PCG/EN/FR init-data)
--     importId 101 "Прихващане на сметки"         yearlyResetOk=false  (дубликат — само в BG_COA)
-- И двете носят един и същ prefixe='REC_%YY_%M_' и празен suffixe. Деклариран unique-constraint
-- (company, codeSelect, prefixe, suffixe) съществува (uk_du9doh0tcuvhn69xvwnw4tjo7), но в Postgres
-- NULL <> NULL, а suffixe е NULL и за двата реда — затова ограничението мълчаливо не хваща дублирането.
--
-- ReconcileSequenceService→SequenceService.getSequence("reconcile", company)→
-- SequenceRepository.find(codeSelect, company) прави `.fetchOne()` без ORDER BY върху
-- `codeSelect = :codeSelect AND company = :company` — с два съвпадащи реда изборът е произволен
-- (ред на Hibernate/PG), а не грешка, затова дублирането не се вижда, докато не се разгледа.
--
-- ОБХВАТ: само редове с codeSelect='reconcile' и import_id, завършващ на '-101', чиято фирма носи
-- и сестринския '-65' ред (гарантира, че не пипаме фирма с нестандартна настройка). Нито един ред
-- не се трие, ако е бил ИЗПОЛЗВАН (SequenceVersion.next_num > 1) — такъв случай спира с грешка и
-- изисква ръчно решение, не механично изтриване.
--
-- Пуска се РЪЧНО, след бекъп (виж придружаващия -verify.sql за сверка преди/след):
--
--     PGPASSWORD=... psql -h localhost -p 5432 -U nyxa -d nyxa \
--         -v ON_ERROR_STOP=1 -f 2026-10-18-bg-coa-duplicate-reconcile-sequence-existing-base.sql
--
-- Второто пускане не намира редове за триене (import_id вече няма '-101' сестра) и приключва като no-op.

BEGIN;

DO $mig$
DECLARE
  r           record;
  v_versions  integer;
  v_used      integer;
  n_deleted   integer := 0;
  n_skipped   integer := 0;
BEGIN
  RAISE NOTICE 'BG_COA/RECONCILE_DUP ═══ начало ═══ база=% потребител=%', current_database(), current_user;

  IF to_regclass('public.base_sequence') IS NULL THEN
    RAISE EXCEPTION 'BG_COA/RECONCILE_DUP: таблица base_sequence липсва — не е Axelor база. Нищо не е пипано.';
  END IF;

  FOR r IN
    SELECT dup.id AS dup_id, dup.import_id AS dup_import_id, dup.company AS company_id,
           co.code AS company_code, co.name AS company_name
      FROM base_sequence dup
      JOIN base_company co ON co.id = dup.company
     WHERE dup.code_select = 'reconcile'
       AND dup.import_id LIKE '%-101'
       AND EXISTS (
             SELECT 1 FROM base_sequence canon
              WHERE canon.company = dup.company
                AND canon.code_select = 'reconcile'
                AND canon.import_id = REPLACE(dup.import_id, '-101', '-65'))
     ORDER BY co.code
  LOOP
    SELECT count(*), coalesce(max(next_num), 0)
      INTO v_versions, v_used
      FROM base_sequence_version
     WHERE sequence = r.dup_id;

    IF v_used > 1 THEN
      RAISE EXCEPTION
        'BG_COA/RECONCILE_DUP: [%] поредица % (id=%) е ИЗПОЛЗВАНА (next_num=%) — не се трие автоматично, нужно е ръчно решение.',
        r.company_code, r.dup_import_id, r.dup_id, v_used;
    END IF;

    DELETE FROM base_sequence_version WHERE sequence = r.dup_id;
    DELETE FROM base_sequence WHERE id = r.dup_id;
    n_deleted := n_deleted + 1;
    RAISE NOTICE 'BG_COA/RECONCILE_DUP DELETED [%] % (id=%, фирма=%) — % версии изтрити',
      r.company_code, r.dup_import_id, r.dup_id, r.company_name, v_versions;
  END LOOP;

  IF n_deleted = 0 THEN
    RAISE NOTICE 'BG_COA/RECONCILE_DUP: няма дублирани reconcile поредици за триене (вече приложено или base без BG_COA).';
  END IF;

  RAISE NOTICE 'BG_COA/RECONCILE_DUP ═══ край ═══ изтрити=% | пропуснати=%', n_deleted, n_skipped;
END
$mig$;

-- ═══ СВЕРКА ЗА КОНВЕРГЕНЦИЯ — ВСЕ ОЩЕ В ТРАНЗАКЦИЯТА ═══════════════════════════════════════════
-- След миграцията всяка фирма трябва да има ТОЧНО ЕДНА поредица codeSelect='reconcile'.
DO $conv$
DECLARE
  v_bad text := '';
  x     record;
BEGIN
  FOR x IN
    SELECT co.code AS ccode, count(*) AS n
      FROM base_sequence s
      JOIN base_company co ON co.id = s.company
     WHERE s.code_select = 'reconcile'
     GROUP BY co.code
    HAVING count(*) <> 1
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] има %s reconcile поредици (очаква се 1)', x.ccode, x.n);
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/RECONCILE_DUP: НЕ КОНВЕРГИРА — НИЩО НЕ Е ЗАПИСАНО:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/RECONCILE_DUP ✔ конвергенцията е сверена — по една reconcile поредица на фирма';
END
$conv$;

COMMIT;

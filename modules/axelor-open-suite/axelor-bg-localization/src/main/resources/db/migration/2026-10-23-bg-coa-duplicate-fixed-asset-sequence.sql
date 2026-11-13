-- BG_COA · ДУБЛИРАНАТА ПОРЕДИЦА НА ИНВЕНТАРНИЯ НОМЕР (codeSelect='fixedAsset') — заварени дружества
-- (SAF-T A1).
--
-- ЗАЩО. base_sequence.csv на BG_COA внасяше ДВЕ поредици codeSelect='fixedAsset' за всяко дружество:
--     importId 104 „№ на дълготраен актив“   без годишно нулиране  (остава)
--     importId 74  „Fixed assets N°“          с годишно нулиране    (дубликат — маха се)
-- И двете с префикс IMMO%YYYY. SequenceRepository.find(codeSelect, company) прави .fetchOne() без
-- ORDER BY, тоест FixedAssetGenerationServiceImpl.generateSequence взима произволната от двете.
-- Инвентарният номер е AssetID в годишния SAF-T (MF.A.2): уникален в дружеството, не се
-- преизползва и не се мени. Същото дублиране за 'reconcile' се маха с
-- 2026-10-18-bg-coa-duplicate-reconcile-sequence-existing-base.sql — този файл е по образеца му.
-- Новите дружества получават една поредица (от CSV-то; пази го
-- ChartSequenceReconcileUniquenessTest).
--
-- КОГА И КАК. Пуска се в една транзакция с ON_ERROR_STOP — първо пробно (ROLLBACK), като се
-- прочетат NOTICE-ите, после истински. Файлът няма BEGIN/COMMIT: транзакцията я дава този, който
-- го пуска; всяка грешка, вкл. проверката в края, връща целия файл назад.
--
-- ОБХВАТ. Само редове codeSelect='fixedAsset' с import_id, завършващ на '-74', чиято фирма носи и
-- сестринския '-104' ред — не пипаме дружество с нестандартна настройка. ИЗПОЛЗВАНА поредица
-- (SequenceVersion.next_num > 1) НЕ се трие: файлът спира с грешка и иска ръчно решение (номерата,
-- дадени от нея, са вече инвентарни номера на активи).
--
-- ИДЕМПОТЕНТЕН: второто пускане не намира '-74' ред със '-104' сестра и не прави нищо. Не пипа
-- активи — само поредиците.

DO $mig$
DECLARE
  r           record;
  v_versions  integer;
  v_used      integer;
  n_deleted   integer := 0;
BEGIN
  IF to_regclass('public.base_sequence') IS NULL THEN
    RAISE EXCEPTION 'BG_COA/FIXED_ASSET_SEQ_DUP: таблица base_sequence липсва — не е Axelor база.';
  END IF;

  FOR r IN
    SELECT dup.id AS dup_id, dup.import_id AS dup_import_id, co.code AS company_code
      FROM base_sequence dup
      JOIN base_company co ON co.id = dup.company
     WHERE dup.code_select = 'fixedAsset'
       AND dup.import_id LIKE '%-74'
       AND EXISTS (
             SELECT 1 FROM base_sequence canon
              WHERE canon.company = dup.company
                AND canon.code_select = 'fixedAsset'
                AND canon.import_id = left(dup.import_id, length(dup.import_id) - 3) || '-104')
     ORDER BY co.code
  LOOP
    SELECT count(*), coalesce(max(next_num), 0)
      INTO v_versions, v_used
      FROM base_sequence_version
     WHERE sequence = r.dup_id;

    IF v_used > 1 THEN
      RAISE EXCEPTION
        'BG_COA/FIXED_ASSET_SEQ_DUP: [%] поредица % (id=%) е ИЗПОЛЗВАНА (next_num=%) — не се трие автоматично; нужно е ръчно решение.',
        r.company_code, r.dup_import_id, r.dup_id, v_used;
    END IF;

    DELETE FROM base_sequence_version WHERE sequence = r.dup_id;
    DELETE FROM base_sequence WHERE id = r.dup_id;
    n_deleted := n_deleted + 1;
    RAISE NOTICE 'BG_COA/FIXED_ASSET_SEQ_DUP: [%] изтрита % (id=%, версии=%)',
      r.company_code, r.dup_import_id, r.dup_id, v_versions;
  END LOOP;

  RAISE NOTICE 'BG_COA/FIXED_ASSET_SEQ_DUP: изтрити=%', n_deleted;
END
$mig$;

-- Проверка на резултата: всяко дружество с BG_COA поредица '-104' има ТОЧНО ЕДНА поредица
-- codeSelect='fixedAsset'. Иначе — грешка и връщане на целия файл.
DO $check$
DECLARE
  v_bad text := '';
  x     record;
BEGIN
  FOR x IN
    SELECT co.code AS ccode, count(*) AS n
      FROM base_sequence s
      JOIN base_company co ON co.id = s.company
     WHERE s.code_select = 'fixedAsset'
       AND EXISTS (SELECT 1 FROM base_sequence c
                    WHERE c.company = s.company AND c.code_select = 'fixedAsset'
                      AND c.import_id LIKE '%-104')
     GROUP BY co.code
    HAVING count(*) <> 1
  LOOP
    v_bad := v_bad || format(E'\n  · [%s] има %s поредици fixedAsset (очаква се 1)', x.ccode, x.n);
  END LOOP;

  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'BG_COA/FIXED_ASSET_SEQ_DUP: резултатът не е верен:%s', v_bad);
  END IF;
  RAISE NOTICE 'BG_COA/FIXED_ASSET_SEQ_DUP: проверка — по една поредица fixedAsset на дружество с BG_COA';
END
$check$;

-- АКТИВИ · ИНВЕНТАРНИЯТ НОМЕР КАТО AssetID (SAF-T A1).
--
-- ЗАЩО. Инвентарният номер на актива (account_fixed_asset.fixed_asset_seq) е AssetID в годишния
-- SAF-T (MF.A.2): уникален в дружеството, не се мени и не се преизползва. Днес уникалност няма.
-- Активите, внесени от Microinvest, са валидирани БЕЗ номер — оригиналният им инвентарен номер е в
-- bg_microinvest_inventory_number. Новият код чете три нови колони и разчита на уникалността.
-- Приложението НЕ променя схемата, тоест всичко идва оттук.
--
-- КАКВО ПРАВИ, по ред:
--   1. Колоните (ако новата версия още не е стартирала):
--        account_fixed_asset.bg_asset_kind_select         — видът на актива (ДМА / НМА / …);
--        account_fixed_asset.bg_split_from                — „отделен от“ при split, с FK към актива;
--        account_asset_disposal_reason.bg_nap_movement_code — кодът по НАП на причината.
--      Индекс за bg_split_from НЯМА нарочно (index="false" в домейна): неявното му име би накарало
--      Hibernate да го създаде при старт. FK-то Hibernate 5.6 разпознава по колоната.
--   2. Номерата от Microinvest: празен fixed_asset_seq ← bg_microinvest_inventory_number, САМО ако
--      номерът е уникален в дружеството — нито друг внесен актив със същия номер, нито заварен
--      актив с такъв fixed_asset_seq. Дублираните остават ПРАЗНИ; годишният
--      SAF-T дава за тях ГРЕШКА „дублиран инвентарен номер“ със списък. version се увеличава.
--   3. Празен низ (или само интервали) за номер → NULL: „няма номер“ е NULL и така двата
--      актива без номер не си пречат в уникалността (PostgreSQL не сравнява NULL-ите). Новата
--      версия пази същото при запис (BgFixedAssetListener).
--   4. Отказ, ако в някое дружество два актива вече имат един и същ непразен номер — със списъка.
--      Такъв случай иска ръчно решение (номерът е Move.origin на статиите им), не механична поправка.
--   5. Уникалност (company, fixed_asset_seq) — ограничението, което домейнът декларира
--      (<unique-constraint columns="company,fixedAssetSeq"/> в FixedAsset.xml на модула), С ИМЕТО,
--      което Hibernate му изчислява: uk_a53q3sjcj39twi5ixjpsj1ge4. Така ddl=update след деплоя го
--      намира и не прави второ. На чиста база (без този файл) същото ограничение идва от
--      домейна — схемата е една и съща по двата пътя.
--   6. Проверка на резултата накрая.
--
-- КОГА И КАК. Срещу заварената база, ПРЕДИ деплоя на новата версия:
--   1. файлът в транзакция с ROLLBACK (пробен пробег) — прочетете NOTICE-ите (колко номера са
--      попълнени и кои са оставени заради дубликат);
--   2. същото с COMMIT;
--   3. деплой. Новата версия при старт САМО ПРОВЕРЯВА (BgFixedAssetSchemaCheck в
--      BgFixedAssetIntegrator): липсва ли колона или уникалността, стартът спира с името на файла.
-- Транзакцията и ON_ERROR_STOP ги дава обвивката: файлът няма BEGIN/COMMIT; всяка грешка, вкл.
-- проверката в края, връща целия файл назад.
--
-- СЪВМЕСТИМО СЪС СТАРАТА ВЕРСИЯ. Колоните са нови и празни; старият код не ги познава. Попълнените
-- номера са същите, които клиентът е виждал в Microinvest. Уникалността пази и стария код: две
-- валидирания с един и същ номер (дублираната поредица 'fixedAsset', виж
-- 2026-10-23-bg-coa-duplicate-fixed-asset-sequence.sql) вече дават отказ от базата.
--
-- Празен низ за номер старата версия не пише (полето е readonly, номерът идва от поредицата при
-- валидиране); NULL е и нейното „няма номер“.
--
-- ИДЕМПОТЕНТЕН: второто пускане не намира празни номера с уникален кандидат, нито празни низове,
-- уникалността и FK-то са налични — нищо не се променя.

-- 1) Колоните.
ALTER TABLE account_fixed_asset ADD COLUMN IF NOT EXISTS bg_asset_kind_select varchar(255);
ALTER TABLE account_fixed_asset ADD COLUMN IF NOT EXISTS bg_split_from bigint;
ALTER TABLE account_asset_disposal_reason ADD COLUMN IF NOT EXISTS bg_nap_movement_code varchar(255);

DO $fk$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM pg_constraint con
     WHERE con.conrelid = 'account_fixed_asset'::regclass
       AND con.contype = 'f'
       AND con.confrelid = 'account_fixed_asset'::regclass
       AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'account_fixed_asset'::regclass
                                  AND attname = 'bg_split_from')]) THEN
    RAISE NOTICE 'FK bg_split_from → account_fixed_asset: налично';
  ELSE
    ALTER TABLE account_fixed_asset
      ADD CONSTRAINT fk_bg_fixed_asset_split_from FOREIGN KEY (bg_split_from)
      REFERENCES account_fixed_asset (id);
    RAISE NOTICE 'FK bg_split_from → account_fixed_asset: създадено';
  END IF;
END $fk$;

-- 2) Номерата от Microinvest — само уникалните в дружеството.
DO $fill$
DECLARE
  n_filled int;
  n_left   int;
  x        record;
BEGIN
  WITH cand AS (
    SELECT id, company, btrim(bg_microinvest_inventory_number) AS num
      FROM account_fixed_asset
     WHERE (fixed_asset_seq IS NULL OR btrim(fixed_asset_seq) = '')
       AND btrim(coalesce(bg_microinvest_inventory_number, '')) <> ''
  ),
  uniq AS (
    SELECT c.id, c.num
      FROM cand c
     WHERE (SELECT count(*) FROM cand c2 WHERE c2.company IS NOT DISTINCT FROM c.company
              AND c2.num = c.num) = 1
       AND NOT EXISTS (SELECT 1 FROM account_fixed_asset f
                        WHERE f.company IS NOT DISTINCT FROM c.company
                          AND btrim(coalesce(f.fixed_asset_seq, '')) = c.num)
  )
  UPDATE account_fixed_asset f
     SET fixed_asset_seq = u.num,
         version = coalesce(f.version, 0) + 1
    FROM uniq u
   WHERE f.id = u.id;
  GET DIAGNOSTICS n_filled = ROW_COUNT;

  SELECT count(*) INTO n_left
    FROM account_fixed_asset
   WHERE (fixed_asset_seq IS NULL OR btrim(fixed_asset_seq) = '')
     AND btrim(coalesce(bg_microinvest_inventory_number, '')) <> '';
  RAISE NOTICE 'номера от Microinvest: попълнени=%, оставени празни (дубликат)=%', n_filled, n_left;
  FOR x IN
    SELECT company, btrim(bg_microinvest_inventory_number) AS num, count(*) AS n
      FROM account_fixed_asset
     WHERE (fixed_asset_seq IS NULL OR btrim(fixed_asset_seq) = '')
       AND btrim(coalesce(bg_microinvest_inventory_number, '')) <> ''
     GROUP BY company, btrim(bg_microinvest_inventory_number)
     ORDER BY company, 2
  LOOP
    RAISE NOTICE '  оставен празен: дружество id=%, инвентарен № „%“ (% актива без номер)',
      x.company, x.num, x.n;
  END LOOP;
END $fill$;

-- 3) Празният номер е NULL.
DO $blank$
DECLARE
  n int;
BEGIN
  UPDATE account_fixed_asset
     SET fixed_asset_seq = NULL,
         version = coalesce(version, 0) + 1
   WHERE fixed_asset_seq IS NOT NULL AND btrim(fixed_asset_seq) = '';
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'празен номер → NULL: % актива', n;
END $blank$;

-- 4) Заварени дубликати на непразен номер — отказ със списъка.
DO $dups$
DECLARE
  v_bad text := '';
  x     record;
BEGIN
  FOR x IN
    SELECT company, fixed_asset_seq AS num, string_agg(id::text, ', ' ORDER BY id) AS ids
      FROM account_fixed_asset
     WHERE fixed_asset_seq IS NOT NULL AND fixed_asset_seq <> ''
     GROUP BY company, fixed_asset_seq
    HAVING count(*) > 1
     ORDER BY company, fixed_asset_seq
  LOOP
    v_bad := v_bad || format(E'\n  · дружество id=%s, № „%s“: активи %s', x.company, x.num, x.ids);
  END LOOP;
  IF v_bad <> '' THEN
    RAISE EXCEPTION USING MESSAGE = format(
      E'Два актива в едно дружество с един и същ инвентарен номер — нужно е ръчно решение (номерът е Move.origin на статиите им):%s',
      v_bad);
  END IF;
END $dups$;

-- 5) Уникалността (company, fixed_asset_seq) — с името на Hibernate, ако няма такава по колоните.
--    Частичен уникален индекс върху същите колони (от ранна версия на този файл) се маха: при него
--    ddl=update би сложил и своето ограничение — две вместо едно.
DO $uk$
DECLARE
  x record;
BEGIN
  FOR x IN
    SELECT c.relname
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE i.indrelid = 'account_fixed_asset'::regclass
       AND i.indisunique AND i.indpred IS NOT NULL AND i.indnatts = 2
       AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM unnest(i.indkey) k
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k)
           = ARRAY['company', 'fixed_asset_seq']
  LOOP
    EXECUTE format('DROP INDEX %I', x.relname);
    RAISE NOTICE 'частичен уникален индекс % — махнат', x.relname;
  END LOOP;

  IF EXISTS (
    SELECT 1
      FROM pg_index i
     WHERE i.indrelid = 'account_fixed_asset'::regclass
       AND i.indisunique AND i.indpred IS NULL AND i.indnatts = 2
       AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM unnest(i.indkey) k
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k)
           = ARRAY['company', 'fixed_asset_seq']) THEN
    RAISE NOTICE 'уникалност (company, fixed_asset_seq): налична';
  ELSE
    ALTER TABLE account_fixed_asset
      ADD CONSTRAINT uk_a53q3sjcj39twi5ixjpsj1ge4 UNIQUE (company, fixed_asset_seq);
    RAISE NOTICE 'уникалност (company, fixed_asset_seq): създадена';
  END IF;
END $uk$;

-- 6) Проверка на резултата — грешка (и връщане на целия файл), ако не е верен.
DO $check$
DECLARE
  expected text[][] := ARRAY[
    ARRAY['account_fixed_asset', 'bg_asset_kind_select', 'character varying(255)'],
    ARRAY['account_fixed_asset', 'bg_split_from', 'bigint'],
    ARRAY['account_asset_disposal_reason', 'bg_nap_movement_code', 'character varying(255)']];
  actual text;
  wrong  text[] := ARRAY[]::text[];
  n_uk   int;
  n_fk   int;
  n_bl   int;
  i      int;
BEGIN
  FOR i IN 1 .. array_length(expected, 1) LOOP
    SELECT format_type(a.atttypid, a.atttypmod) INTO actual
      FROM pg_attribute a
     WHERE a.attrelid = expected[i][1]::regclass
       AND a.attname = expected[i][2]
       AND a.attnum > 0
       AND NOT a.attisdropped;
    IF actual IS DISTINCT FROM expected[i][3] THEN
      wrong := array_append(wrong, format('%s.%s=%s (очаквано %s)',
        expected[i][1], expected[i][2], coalesce(actual, 'липсва'), expected[i][3]));
    END IF;
  END LOOP;

  SELECT count(*) INTO n_uk
    FROM pg_index i
   WHERE i.indrelid = 'account_fixed_asset'::regclass
     AND i.indisunique AND i.indnatts = 2
     AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM unnest(i.indkey) k
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k)
         = ARRAY['company', 'fixed_asset_seq'];
  IF n_uk <> 1 THEN
    wrong := array_append(wrong, format('уникалности (company, fixed_asset_seq): %s, очаквана 1', n_uk));
  END IF;

  SELECT count(*) INTO n_bl
    FROM account_fixed_asset
   WHERE fixed_asset_seq IS NOT NULL AND btrim(fixed_asset_seq) = '';
  IF n_bl > 0 THEN
    wrong := array_append(wrong, format('празен низ за номер: %s актива', n_bl));
  END IF;

  SELECT count(*) INTO n_fk
    FROM pg_constraint con
   WHERE con.conrelid = 'account_fixed_asset'::regclass
     AND con.contype = 'f'
     AND con.confrelid = 'account_fixed_asset'::regclass
     AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                              WHERE attrelid = 'account_fixed_asset'::regclass
                                AND attname = 'bg_split_from')];
  IF n_fk < 1 THEN
    wrong := array_append(wrong, 'FK bg_split_from → account_fixed_asset липсва');
  END IF;

  IF array_length(wrong, 1) IS NOT NULL THEN
    RAISE EXCEPTION USING MESSAGE = format('Резултатът не е верен: %s.', array_to_string(wrong, '; '));
  END IF;
  RAISE NOTICE 'проверка: колоните, уникалността, празните номера и FK-то — вярно';
END $check$;

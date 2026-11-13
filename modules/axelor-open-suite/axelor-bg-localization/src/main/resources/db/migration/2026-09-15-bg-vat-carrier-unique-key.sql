-- BG · ДДС НОСИТЕЛИ — подмяна на уникалния ключ на `bglocalization_bg_vat_annual_correction_line`.
--
-- ЗАЩО СЪЩЕСТВУВА. Старият ключ заключва реда по (annual_correction, fixed_asset) — по ЕДИН ред на
-- актив за година, когато носителят на начислен ДДС е един: този при придобиването. Има и ВТОРИ
-- носител (чл. 79а ал. 9 и ППЗДДС чл. 67 ал. 4), тоест един актив има по един ред за
-- всяко подобрение. Старият ключ ги прави взаимно изключващи се: вторият INSERT пада с
-- „duplicate key value violates unique constraint“.
--
-- ⚠️ ЗАЩО НЕ СЕ ОПРАВЯ САМО. `db.default.ddl = update` СЪЗДАВА липсващото, но не ТРИЕ заварено:
-- Hibernate в режим update не изпуска ограничения. Новият ключ (…, carrier_key) ще се появи, а
-- старият ще остане до него и ще продължи да отказва втория носител. На ЧИСТА база проблем няма —
-- затова грешката е невидима при разработка и се появява само на заварена инсталация.
--
-- ⚠️ ЗАЩО НЕ МОЖЕ ДА Е ПРОСТО (annual_correction, fixed_asset, subsequent_cost). PostgreSQL НЕ
-- смята два NULL-а за равни в уникално ограничение, тъй че редът за ПРИДОБИВАНЕТО — онзи, чийто
-- subsequent_cost е NULL, и най-честият — щеше да остане незащитен срещу дубликат. Затова
-- дискриминаторът е НЕнулев низ: „ACQUISITION“ или „COST:<id>“.
--
-- КОГА. СЛЕД деплоя и първото успешно вдигане с новия код — тогава колоната `carrier_key` вече
-- съществува (създава я ddl=update) и може да се напълни. Пуснато преди това, стъпка 1 отказва.
--
--     PGPASSWORD=axelor psql -h localhost -p 5432 -U axelor -d nyxa \
--       -f modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/migration/2026-09-15-bg-vat-carrier-unique-key.sql

\set ON_ERROR_STOP on

BEGIN;

-- 1) Колоната трябва да я има — инак кодът още не е вдигнат и ключът няма на какво да стъпи.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_name = 'bglocalization_bg_vat_annual_correction_line'
       AND column_name = 'carrier_key') THEN
    RAISE EXCEPTION USING MESSAGE =
      'Колоната carrier_key я няма — вдигнете приложението с новия код преди тази миграция.';
  END IF;
END $$;

-- 2) Заварените редове са все от носителя при придобиване: старият ключ не допуска друг.
UPDATE bglocalization_bg_vat_annual_correction_line
   SET carrier_key = 'ACQUISITION'
 WHERE carrier_key IS NULL OR btrim(carrier_key) = '';

ALTER TABLE bglocalization_bg_vat_annual_correction_line
  ALTER COLUMN carrier_key SET NOT NULL;

-- 3) Старият ключ отпада — той е точно този, който отказва втория носител.
DO $$
DECLARE
  old_name text;
BEGIN
  SELECT con.conname INTO old_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
   WHERE rel.relname = 'bglocalization_bg_vat_annual_correction_line'
     AND con.contype = 'u'
     AND pg_get_constraintdef(con.oid) = 'UNIQUE (annual_correction, fixed_asset)';
  IF old_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE bglocalization_bg_vat_annual_correction_line DROP CONSTRAINT %I',
                   old_name);
  END IF;
END $$;

-- 4) Новият ключ. Създава се само ако Hibernate не го е сложил вече.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'bglocalization_bg_vat_annual_correction_line'
       AND con.contype = 'u'
       AND pg_get_constraintdef(con.oid)
           = 'UNIQUE (annual_correction, fixed_asset, carrier_key)') THEN
    ALTER TABLE bglocalization_bg_vat_annual_correction_line
      ADD CONSTRAINT uk_bg_vat_annual_correction_line_carrier
      UNIQUE (annual_correction, fixed_asset, carrier_key);
  END IF;
END $$;

COMMIT;

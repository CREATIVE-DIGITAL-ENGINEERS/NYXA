-- ТРЗ · фишове и обезщетения — номерът е уникален в ДРУЖЕСТВОТО, не в цялата база.
--
-- ЗАЩО. Номерът на фиша (FISH-…) и на обезщетението (OBEZ-…) идва от редицата на дружеството: две
-- дружества имат по свой FISH-2026-0001. Новият код декларира (company, reference) вместо глобалното
-- unique на reference, но приложението НЕ променя схемата: тази промяна идва само оттук, както при
-- номера на партньора.
--
-- Колоната company на двата документа съществува САМО за този ключ: копие на платеца (payCompany на
-- договора; за самоосигуряващия се — основният договор от регистрацията), записано веднъж при
-- номерирането. Никой не я чете (BgQueryFieldTest).
--
-- КОГА И КАК. Срещу заварената база, ПРЕДИ деплоя на новата версия:
--   1. файлът в транзакция с ROLLBACK (пробен пробег) — прочетете NOTICE-ите;
--   2. същото с COMMIT;
--   3. деплой. Новата версия при старт САМО ПРОВЕРЯВА (BgTrzSchemaCheck): липсва ли
--      колоната company или стои ли още глобалното уникално по reference, стартът спира с името на
--      този файл. Редиците по дружество (от най-големия номер + 1) ги създава самото приложение при
--      старта (BgTrzSequenceBackfill) — не този файл.
--
-- ТРАНЗАКЦИЯТА И ON_ERROR_STOP ги дава обвивката: файлът няма BEGIN/COMMIT и се пуска като `BEGIN; \i файла; COMMIT|ROLLBACK;` с psql -v ON_ERROR_STOP=1. Всяка грешка (вкл.
-- проверките отдолу) връща целия файл назад.
--
-- СЪВМЕСТИМО СЪС СТАРАТА ВЕРСИЯ. Старият код номерира от една обща редица, тоест номерата му остават
-- уникални и без глобалното ограничение; колоната company не я познава. Фишовете, които запише между
-- този файл и деплоя, остават с празно company — новата версия го попълва при следващия им запис, а
-- максимума за брояча мери по договора.
-- ⚠️ Ако старата версия се РЕСТАРТИРА между този файл и деплоя, нейният ddl=update (мапингът още е
-- unique="true") може да върне глобалното ограничение — новата версия тогава не стартира и казва
-- това; пуснете файла пак.
--
-- КАКВО ПРАВИ:
--   1. колона company (+ FK към base_company) на двете таблици — ако я няма;
--   2. company на заварените редове — САМО празните, по правилото на платеца; документ с номер, на
--      който не се извежда дружество → грешка поименно (целият файл назад);
--   3. ограничението (company, reference) — ако го няма;
--   4. сваля глобалното уникално САМО по reference (ограничение или самостоятелен уникален индекс).
--
-- ИДЕМПОТЕНТЕН: второ пускане не променя нищо и не дава грешка.

-- 1) Колоната и FK-то.
ALTER TABLE bglocalization_bg_pay_slip ADD COLUMN IF NOT EXISTS company bigint;
ALTER TABLE bglocalization_bg_compensation ADD COLUMN IF NOT EXISTS company bigint;

DO $$
DECLARE
  t text;
  fk text;
BEGIN
  FOREACH t IN ARRAY ARRAY['bglocalization_bg_pay_slip', 'bglocalization_bg_compensation'] LOOP
    fk := 'fk_' || replace(t, 'bglocalization_', '') || '_company';
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
          JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attname = 'company'
         WHERE con.conrelid = t::regclass AND con.contype = 'f' AND con.conkey = ARRAY[a.attnum]) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (company) REFERENCES base_company (id)', t, fk);
      RAISE NOTICE 'база: % · добавено %', current_database(), fk;
    END IF;
  END LOOP;
END $$;

-- 2) Дружеството на заварените документи — по платеца.
DO $$
DECLARE
  n integer;
  orphans text;
BEGIN
  UPDATE bglocalization_bg_pay_slip p
     SET company = src.cid, version = p.version + 1
    FROM (SELECT p2.id,
                 CASE WHEN p2.bg_self_insured_registration IS NOT NULL THEN m.pay_company
                      ELSE c.pay_company END AS cid
            FROM bglocalization_bg_pay_slip p2
            LEFT JOIN hr_employment_contract c ON c.id = p2.employment_contract
            LEFT JOIN bglocalization_bg_self_insured_registration r ON r.id = p2.bg_self_insured_registration
            LEFT JOIN hr_employee e ON e.id = r.employee
            LEFT JOIN hr_employment_contract m ON m.id = e.main_employment_contract
           WHERE p2.company IS NULL) src
   WHERE p.id = src.id AND src.cid IS NOT NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'база: % · фишове с попълнено дружество: %', current_database(), n;

  UPDATE bglocalization_bg_compensation d
     SET company = c.pay_company, version = d.version + 1
    FROM hr_employment_contract c
   WHERE c.id = d.employment_contract AND d.company IS NULL AND c.pay_company IS NOT NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'база: % · обезщетения с попълнено дружество: %', current_database(), n;

  SELECT string_agg(format('фиш id=%s %s', id, reference), ', ' ORDER BY id) INTO orphans
    FROM bglocalization_bg_pay_slip WHERE company IS NULL AND reference IS NOT NULL;
  IF orphans IS NULL THEN
    SELECT string_agg(format('обезщетение id=%s %s', id, reference), ', ' ORDER BY id) INTO orphans
      FROM bglocalization_bg_compensation WHERE company IS NULL AND reference IS NOT NULL;
  END IF;
  IF orphans IS NOT NULL THEN
    RAISE EXCEPTION 'документи с номер, но без извеждащо се дружество (база %): %. Попълни платеца на договора им.',
      current_database(), orphans;
  END IF;
END $$;

-- 3–4) Ключът: (дружество, номер) вместо глобално.
DO $$
DECLARE
  t text;
  uk text;
  r record;
BEGIN
  FOREACH t IN ARRAY ARRAY['bglocalization_bg_pay_slip', 'bglocalization_bg_compensation'] LOOP
    uk := CASE t WHEN 'bglocalization_bg_pay_slip' THEN 'uk_bg_pay_slip_company_reference'
                 ELSE 'uk_bg_compensation_company_reference' END;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = uk AND conrelid = t::regclass) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (company, reference)', t, uk);
      RAISE NOTICE 'база: % · добавено %', current_database(), uk;
    END IF;
    -- Глобалното — по КОЛОНИТЕ (имената са хешове и се различават по база).
    FOR r IN
      SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attname = 'reference'
       WHERE con.conrelid = t::regclass AND con.contype = 'u' AND con.conkey = ARRAY[a.attnum]
    LOOP
      EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, r.conname);
      RAISE NOTICE 'база: % · свалено глобалното % на %.reference', current_database(), r.conname, t;
    END LOOP;
    FOR r IN
      SELECT ic.relname
        FROM pg_index i
        JOIN pg_class ic ON ic.oid = i.indexrelid
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attname = 'reference'
       WHERE i.indrelid = t::regclass AND i.indisunique AND i.indpred IS NULL
         AND i.indnatts = 1 AND i.indkey[0] = a.attnum
         AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid)
    LOOP
      EXECUTE format('DROP INDEX %I', r.relname);
      RAISE NOTICE 'база: % · свален глобалният индекс % на %.reference', current_database(), r.relname, t;
    END LOOP;
  END LOOP;
END $$;

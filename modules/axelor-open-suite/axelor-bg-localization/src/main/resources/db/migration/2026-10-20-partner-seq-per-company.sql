-- BG · ПАРТНЬОРИ — номерът е уникален в дружеството, не в цялата база.
--
-- ЗАЩО. axelor-base декларира Partner.partnerSeq с unique="true" — едно ограничение UNIQUE
-- (partner_seq) за всички дружества. Всеки партньор е на едно дружество (Partner.company) и всяко
-- дружество брои от своята поредица `partner`: P0001 в А и P0001 в Б са два партньора.
-- Новият код отменя глобалната уникалност и декларира (company, partner_seq), но приложението НЕ
-- променя схемата: тази промяна идва само оттук.
--
-- КОГА И КАК. Срещу заварената база, ПРЕДИ деплоя на новата версия:
--   1. файлът в транзакция с ROLLBACK (пробен пробег) — прочетете NOTICE-ите;
--   2. същото с COMMIT;
--   3. деплой. Новата версия при старт САМО ПРОВЕРЯВА (BgPartnerSchemaCheck): намери ли
--      още глобалното UNIQUE (partner_seq), стартът спира с името на този файл.
--
-- ТРАНЗАКЦИЯТА И ON_ERROR_STOP ги дава обвивката: файлът няма BEGIN/COMMIT и се пуска като `BEGIN; \i файла; COMMIT|ROLLBACK;` с psql -v ON_ERROR_STOP=1. Всяка грешка (вкл.
-- проверката в края) връща целия файл назад.
--
-- СЪВМЕСТИМО СЪС СТАРАТА ВЕРСИЯ. Старият код пише партньори с празно company и номер от общия брояч;
-- частичният уникален индекс ги пази уникални, колкото и досега. Колоната company е нова и празна.
-- ⚠️ Ако старата версия се РЕСТАРТИРА между този файл и деплоя, нейният ddl=update (мапингът още е
-- unique="true") може да върне глобалното ограничение — новата версия тогава не стартира и казва
-- това; пуснете файла пак.
--
-- ИДЕМПОТЕНТЕН: второ пускане не променя нищо и не дава грешка. Не пипа редове — само схемата.

-- 1) Колоната — ако новата версия още не е стартирала (FK и индекса ги дописва ddl=update).
ALTER TABLE base_partner ADD COLUMN IF NOT EXISTS company bigint;

-- 2) Глобалната уникалност на partner_seq — по КОЛОНИТЕ (имената са хешове и се различават по база):
--    уникално ограничение точно върху partner_seq и уникален индекс точно върху partner_seq без
--    условие, който не е на ограничение.
DO $$
DECLARE
  r record;
  dropped int := 0;
BEGIN
  FOR r IN
    SELECT con.conname
      FROM pg_constraint con
     WHERE con.conrelid = 'base_partner'::regclass
       AND con.contype = 'u'
       AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
              FROM unnest(con.conkey) k
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k)
           = ARRAY['partner_seq']
  LOOP
    EXECUTE format('ALTER TABLE base_partner DROP CONSTRAINT %I', r.conname);
    dropped := dropped + 1;
  END LOOP;
  FOR r IN
    SELECT ic.relname
      FROM pg_index i
      JOIN pg_class ic ON ic.oid = i.indexrelid
     WHERE i.indrelid = 'base_partner'::regclass
       AND i.indisunique
       AND i.indpred IS NULL
       AND i.indnatts = 1
       AND i.indkey[0] = (SELECT attnum FROM pg_attribute
                           WHERE attrelid = 'base_partner'::regclass AND attname = 'partner_seq')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
  LOOP
    EXECUTE format('DROP INDEX %I', r.relname);
    dropped := dropped + 1;
  END LOOP;
  RAISE NOTICE 'глобална уникалност на partner_seq — изпусната: %', dropped;
END $$;

-- 3) Уникалност в дружеството — само ако я няма (по колоните). Името е това, което Hibernate дава
--    на @UniqueConstraint(company, partnerSeq) (UK_ + MD5 по основа 35): ddl=update при старт я
--    намира и не прави втора.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM pg_constraint con
     WHERE con.conrelid = 'base_partner'::regclass
       AND con.contype = 'u'
       AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
              FROM unnest(con.conkey) k
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k)
           = ARRAY['company', 'partner_seq']) THEN
    RAISE NOTICE 'UNIQUE (company, partner_seq): налично';
  ELSE
    ALTER TABLE base_partner
      ADD CONSTRAINT uk_p09fve1mwil35xaxbps608xc UNIQUE (company, partner_seq);
    RAISE NOTICE 'UNIQUE (company, partner_seq): създадено';
  END IF;
END $$;

-- 4) Партньорите без дружество — системните (на потребители) и заварените — остават
--    уникални помежду си: PostgreSQL не смята два NULL-а за равни, тоест (company, partner_seq) тях
--    не ги пази.
CREATE UNIQUE INDEX IF NOT EXISTS uk_bg_partner_seq_without_company
  ON base_partner (partner_seq) WHERE company IS NULL;

-- 5) Проверка на резултата — грешка (и връщане на целия файл), ако не е верен.
DO $$
DECLARE
  global_unique int;
  company_unique int;
  without_company int;
BEGIN
  SELECT count(*) INTO global_unique
    FROM pg_index i
   WHERE i.indrelid = 'base_partner'::regclass
     AND i.indisunique
     AND i.indpred IS NULL
     AND i.indnatts = 1
     AND i.indkey[0] = (SELECT attnum FROM pg_attribute
                         WHERE attrelid = 'base_partner'::regclass AND attname = 'partner_seq');
  SELECT count(*) INTO company_unique
    FROM pg_constraint con
   WHERE con.conrelid = 'base_partner'::regclass
     AND con.contype = 'u'
     AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
            FROM unnest(con.conkey) k
            JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k)
         = ARRAY['company', 'partner_seq'];
  SELECT count(*) INTO without_company
    FROM pg_index i
   WHERE i.indrelid = 'base_partner'::regclass
     AND i.indisunique
     AND i.indnatts = 1
     AND i.indkey[0] = (SELECT attnum FROM pg_attribute
                         WHERE attrelid = 'base_partner'::regclass AND attname = 'partner_seq')
     AND pg_get_expr(i.indpred, i.indrelid) = '(company IS NULL)';
  IF global_unique <> 0 OR company_unique < 1 OR without_company <> 1 THEN
    RAISE EXCEPTION USING MESSAGE = format(
      'Резултатът не е верен: глобална уникалност=%s (очаквано 0), (company, partner_seq)=%s'
      ' (очаквано ≥1), уникалност без дружество=%s (очаквано 1).',
      global_unique, company_unique, without_company);
  END IF;
  RAISE NOTICE 'проверка: глобална=0, (company, partner_seq)=%, без дружество=1 — вярно',
    company_unique;
END $$;

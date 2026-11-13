-- Партньорите и броячите им по дружество — колко са заварените и какво ще иска миграцията им.
-- ЧЕТЕ САМО.
--
-- Новият партньор е на точно едно дружество (Partner.company) и номерът му идва от поредицата
-- `partner` на това дружество. Заварените партньори (company празно) не се пипат — те са за
-- миграцията (db/migration/2026-10-20-partner-seq-per-company.sql). Тази проверка казва колко са,
-- колко от тях вече сочат точно едно дружество през companySet (кандидатите за еднозначно попълване)
-- и докъде е стигнал общият брояч.
--
-- Само бройки и шаблони на кода (цифрите → 9) — без имена, без ЕИК, без адреси.
-- Работи и ПРЕДИ деплоя: дружеството се чете през to_jsonb(ред) ->> 'company', тоест при липсваща
-- колона всички излизат „без дружество“, а последният ред казва дали колоната я има.
--   psql … -v ON_ERROR_STOP=1 -f 2026-10-19-partner-sequence-per-company.sql
BEGIN READ ONLY;
\pset border 2
\pset format aligned

-- 1) Партньорите: с и без дружество, без номер, контакти.
SELECT count(*)                                                         AS партньори,
       count(*) FILTER (WHERE to_jsonb(p) ->> 'company' IS NULL)       AS без_дружество,
       count(*) FILTER (WHERE to_jsonb(p) ->> 'company' IS NOT NULL)   AS с_дружество,
       count(*) FILTER (WHERE p.partner_seq IS NULL)                    AS без_номер,
       count(*) FILTER (WHERE p.is_contact)                             AS контакти,
       count(*) FILTER (WHERE p.archived)                               AS архивирани
  FROM base_partner p;

-- 2) Заварените (без дружество) по броя дружества в companySet: 1 = еднозначен кандидат за миграцията,
--    0 и >1 искат друго правило (документите, потребителя, служителя).
WITH legacy AS (
  SELECT p.id
    FROM base_partner p
   WHERE to_jsonb(p) ->> 'company' IS NULL),
sets AS (
  SELECT l.id, count(cs.company_set) AS n
    FROM legacy l
    LEFT JOIN base_partner_company_set cs ON cs.base_partner = l.id
   GROUP BY l.id)
SELECT CASE WHEN n = 0 THEN '0' WHEN n = 1 THEN '1' ELSE '>1' END AS дружества_в_companySet,
       count(*)                                                     AS заварени
  FROM sets
 GROUP BY 1
 ORDER BY 1;

-- 2б) Заварените по роля — всяка иска свое правило в миграцията: партньорът на
--     дружество → самото дружество; на потребител → системен; контакт на служител → дружеството
--     по договора. Един партньор може да е в повече от една роля.
WITH legacy AS (
  SELECT p.id, p.is_contact
    FROM base_partner p
   WHERE to_jsonb(p) ->> 'company' IS NULL)
SELECT count(*)                                                                       AS заварени,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM base_company c WHERE c.partner = l.id))
                                                                                      AS на_дружество,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM auth_user u WHERE u.partner = l.id))
                                                                                      AS на_потребител,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM hr_employee e WHERE e.contact_partner = l.id))
                                                                                      AS контакт_на_служител,
       count(*) FILTER (WHERE l.is_contact)                                           AS контакти
  FROM legacy l;

-- 3) Шаблоните на кода — дали всички са от един брояч (P9999) или има внесени отвън.
SELECT regexp_replace(p.partner_seq, '[0-9]', '9', 'g') AS шаблон,
       count(*)                                         AS брой
  FROM base_partner p
 WHERE p.partner_seq IS NOT NULL
 GROUP BY 1
 ORDER BY 2 DESC
 LIMIT 15;

-- 4) Повторени номера днес (под глобалното ограничение трябва да е 0).
SELECT count(*) AS повторени_номера
  FROM (SELECT p.partner_seq
          FROM base_partner p
         WHERE p.partner_seq IS NOT NULL
         GROUP BY p.partner_seq
        HAVING count(*) > 1) d;

-- 5) Поредиците `partner`: общата (company празно) и тези на дружествата, със следващия номер.
SELECT coalesce(s.company::text, '(обща)') AS дружество,
       s.prefixe, s.suffixe, s.padding,
       (SELECT max(v.next_num) FROM base_sequence_version v WHERE v.sequence = s.id) AS следващ
  FROM base_sequence s
 WHERE s.code_select = 'partner'
 ORDER BY s.company NULLS FIRST;

-- 6) Дружества без собствена поредица `partner` (след първия старт с новия код трябва да е 0).
SELECT count(*) AS дружества_без_поредица
  FROM base_company c
 WHERE NOT EXISTS (SELECT 1 FROM base_sequence s
                    WHERE s.code_select = 'partner' AND s.company = c.id);

-- 7) Ограниченията на номера: глобалното UNIQUE (partner_seq) трябва да е изчезнало след
--    db/migration/2026-10-20-partner-seq-per-company.sql,
--    а UNIQUE (company, partner_seq) и частичният индекс за заварените — да ги има.
SELECT con.conname AS име, pg_get_constraintdef(con.oid) AS определение
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid = con.conrelid
 WHERE rel.relname = 'base_partner' AND con.contype = 'u';
SELECT indexname AS индекс, indexdef AS определение
  FROM pg_indexes
 WHERE tablename = 'base_partner' AND indexdef ILIKE '%partner_seq%';

SELECT EXISTS (SELECT 1
                 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'base_partner'
                  AND column_name = 'company')                          AS колоната_company_я_има;
ROLLBACK;

-- Броячите в ТРЗ — проверка САМО ЗА ЧЕТЕНЕ (READ ONLY + ROLLBACK; не пише нищо).
--
-- Пуска се по всяко време след старта на новия код:
--   PGPASSWORD=… psql -h … -U … -d <база> -f 2026-10-19-bg-trz-sequences-per-company.sql
--
-- Очаквано:
--   (1) всяко дружество има по една редица bgPaySlip и bgCompensation (новите — при раждането си,
--       заварените — при старта, BgTrzSequenceBackfill);
--   (2) за всяка година с номера „следващ“ > „последен“ (= последен + 1 веднага след старта).
--       Дружеството на документа се извежда от ДОГОВОРА (платецът), не от колоната company;
--   (3) документи с номер без company — 0 СЛЕД миграцията
--       db/migration/2026-10-21-trz-numbering-per-company.sql; преди нея — всички заварени;
--   (4) СЛЕД миграцията: глобалното уникално по reference го няма, (company, reference) го има.

BEGIN READ ONLY;

-- (1) Редиците по дружество.
SELECT co.code AS company, co.name, s.code_select, s.prefixe, s.padding, s.yearly_reset_ok, s.id AS sequence_id
  FROM base_company co
  LEFT JOIN base_sequence s ON s.company = co.id AND s.code_select IN ('bgPaySlip', 'bgCompensation')
 ORDER BY co.code, s.code_select;

-- (2) Последният номер по дружество и година срещу следващия от версията.
WITH docs AS (
  SELECT CASE WHEN p.bg_self_insured_registration IS NOT NULL THEN m.pay_company
              ELSE c.pay_company END AS company,
         'bgPaySlip' AS code, p.reference
    FROM bglocalization_bg_pay_slip p
    LEFT JOIN hr_employment_contract c ON c.id = p.employment_contract
    LEFT JOIN bglocalization_bg_self_insured_registration r ON r.id = p.bg_self_insured_registration
    LEFT JOIN hr_employee e ON e.id = r.employee
    LEFT JOIN hr_employment_contract m ON m.id = e.main_employment_contract
   WHERE p.reference ~ '^FISH-[0-9]{4}-[0-9]+$'
  UNION ALL
  SELECT c.pay_company, 'bgCompensation', d.reference
    FROM bglocalization_bg_compensation d
    JOIN hr_employment_contract c ON c.id = d.employment_contract
   WHERE d.reference ~ '^OBEZ-[0-9]{4}-[0-9]+$'
), last AS (
  SELECT company, code,
         substring(reference from '([0-9]{4})-[0-9]+$')::int AS year,
         max(substring(reference from '-([0-9]+)$')::bigint) AS last_num,
         count(*) AS documents
    FROM docs GROUP BY 1, 2, 3
)
SELECT co.code AS company, l.code, l.year, l.documents, l.last_num, v.next_num,
       CASE WHEN s.id IS NULL THEN 'НЯМА РЕДИЦА'
            WHEN v.next_num IS NULL THEN 'НЯМА ВЕРСИЯ'
            WHEN v.next_num <= l.last_num THEN 'ГРЕШКА: следващ ≤ последен'
            ELSE 'ok' END AS status
  FROM last l
  LEFT JOIN base_company co ON co.id = l.company
  LEFT JOIN base_sequence s ON s.company = l.company AND s.code_select = l.code
  LEFT JOIN base_sequence_version v ON v.sequence = s.id
        AND v.start_date <= make_date(l.year, 12, 31)
        AND (v.end_date IS NULL OR v.end_date >= make_date(l.year, 1, 1))
 ORDER BY co.code, l.code, l.year;

-- (3) Документи с номер без company (0 след миграцията).
SELECT 'bgPaySlip' AS code, count(*) FROM bglocalization_bg_pay_slip WHERE company IS NULL AND reference IS NOT NULL
UNION ALL
SELECT 'bgCompensation', count(*) FROM bglocalization_bg_compensation WHERE company IS NULL AND reference IS NOT NULL;

-- (4) Уникалните ограничения, в които участва reference.
SELECT con.conrelid::regclass AS table_name, con.conname,
       array_agg(a.attname ORDER BY a.attnum) AS columns
  FROM pg_constraint con
  JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
 WHERE con.contype = 'u'
   AND con.conrelid IN ('bglocalization_bg_pay_slip'::regclass, 'bglocalization_bg_compensation'::regclass)
   AND EXISTS (SELECT 1 FROM pg_attribute r
                WHERE r.attrelid = con.conrelid AND r.attname = 'reference' AND r.attnum = ANY (con.conkey))
 GROUP BY con.conrelid, con.conname
 ORDER BY 1, 2;

ROLLBACK;

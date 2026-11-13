-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- ИЗЛОЖЕНОСТ НА ФИШОВЕТЕ ПО 2xx ПЕРАТА-ПРЕВИШЕНИЯ + ПЕРО 165 В РЕЧНИКА (социални разходи)
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- САМО ЧЕТЕНЕ. Целият файл е в BEGIN READ ONLY … ROLLBACK: PostgreSQL отказва всеки INSERT,
-- UPDATE, DELETE или DDL в такава транзакция, а накрая тя се отменя. Нищо не се поправя.
--
-- Празни (1) и (2) значат, че промяната на флаговете може да е САМО НАПРЕД, без корекции на фишове.
--
-- Какво проверява:
--   (1) Кои фишове носят перо 201/203/205/211 (превишения над капачката на ДДО/ДЗО/„Живот“ и на
--       ваучерите), 261 (транспорт с лек автомобил) или 221/223/225 (превишения на еднократните
--       помощи). Кодът НЕ създава тези редове сам, тоест всеки намерен ред е въведен на ръка или
--       внесен. Ред 201/203/205/211/261 с bgIsTaxable = true влиза в основата за авансовия данък
--       (ЗДДФЛ чл. 42 ал. 2), докато 201/203/205/211/261 са и в основата по ЗКПО — тоест двойно
--       облагане спрямо ЗДДФЛ чл. 24 ал. 2 т. 9. Флагът е КОПИЕ на реда: заварен ред пази стария
--       си флаг. 221/223/225 са за сравнение — при тях
--       превишението наистина е доход на лицето (ЗКПО чл. 205).
--   (2) Обобщение: брой фишове по код на перото и по статус на фиша.
--   (3) Има ли в речника перото по чл. 177 КТ (pl_otpusk_177, 108-ият ред на манифеста).
--       Очаква се 1. Нула значи, че базата не е стартирана с билд, който го носи, или че
--       синхронизацията на сийда (datainit.sync.modules) не е минала.
--
-- Лични данни: показва се само идентификаторът на служителя (hr_employee.id) и export_code.
-- ЕГН, имена и контакти не се четат.
--
-- Как се пуска:
--   PGPASSWORD=… psql -h … -U … -d <база> -v ON_ERROR_STOP=1 -f 2026-10-12-2xx-social-expense-exposure.sql
--
-- Празен резултат в (1) и в (2) значи „няма засегнат фиш“.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
BEGIN READ ONLY;

-- ── (1) Фишовете с тези пера, по период ────────────────────────────────────────────────────
-- LEFT JOIN към договора, служителя и фирмата: фиш на самоосигуряващ се (bg_self_insured_registration)
-- няма трудов договор и иначе би изпаднал от списъка.
SELECT s.reference                       AS payslip_no,
       e.id                              AS employee_id,
       e.export_code                     AS employee_export_code,
       s.bg_self_insured_registration    AS self_insured_registration_id,
       p.code                            AS period,
       p.from_date                       AS period_from,
       co.code                           AS company_code,
       co.name                           AS company_name,
       s.status_select                   AS payslip_status,
       s.bg_is_historical_import         AS historical_import,
       s.bg_is_deletion                  AS deletion_slip,
       l.bg_line_number                  AS line_no,
       t.code                            AS line_type_code,
       l.bg_amount                       AS amount,
       l.bg_corrects_period              AS corrects_period_id
FROM bglocalization_bg_pay_slip_line l
JOIN bglocalization_bg_pay_slip s            ON s.id = l.pay_slip
LEFT JOIN bglocalization_bg_pay_slip_line_type t ON t.id = l.bg_pay_slip_line_type
LEFT JOIN base_period p                      ON p.id = s.period
LEFT JOIN hr_employment_contract c           ON c.id = s.employment_contract
LEFT JOIN hr_employee e                      ON e.id = c.employee
LEFT JOIN base_company co                    ON co.id = c.pay_company
WHERE l.bg_line_number IN (201, 203, 205, 211, 261, 221, 223, 225)
ORDER BY p.from_date, s.reference, l.bg_line_number;

-- ── (2) Обобщение: брой фишове по перо и статус ────────────────────────────────────────────
SELECT l.bg_line_number                  AS line_no,
       t.code                            AS line_type_code,
       s.status_select                   AS payslip_status,
       COUNT(DISTINCT s.id)              AS payslips,
       SUM(l.bg_amount)                  AS total_amount
FROM bglocalization_bg_pay_slip_line l
JOIN bglocalization_bg_pay_slip s            ON s.id = l.pay_slip
LEFT JOIN bglocalization_bg_pay_slip_line_type t ON t.id = l.bg_pay_slip_line_type
WHERE l.bg_line_number IN (201, 203, 205, 211, 261, 221, 223, 225)
GROUP BY l.bg_line_number, t.code, s.status_select
ORDER BY l.bg_line_number, s.status_select;

-- ── (3) Перото по чл. 177 КТ в речника (очаква се 1) ───────────────────────────────────────
SELECT count(*) FROM bglocalization_bg_pay_slip_line_type WHERE code = 'pl_otpusk_177';

ROLLBACK;

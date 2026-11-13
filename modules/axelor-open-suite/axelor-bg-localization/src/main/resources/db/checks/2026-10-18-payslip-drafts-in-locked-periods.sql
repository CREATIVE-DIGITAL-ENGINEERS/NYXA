-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- ЧЕРНОВИ ФИШОВЕ В ЗАКЛЮЧЕН ИЛИ ЗАТВАРЯЩ СЕ ПЕРИОД, ЗА ЧИЙТО МЕСЕЦ ВЕЧЕ ИМА Т. 9
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- САМО ЧЕТЕНЕ. Целият файл е в BEGIN READ ONLY … ROLLBACK: PostgreSQL отказва всеки INSERT,
-- UPDATE, DELETE или DDL в такава транзакция, а накрая тя се отменя. Нищо не се поправя.
--
-- Защо: „Преизчисли“ не бива да записва черновата и т. 9 (BgSickLeavePeriodLine) по болничните
-- листове на договора в период, който е ЗАКЛЮЧЕН (CLOSED или с подадена и неотхвърлена Д1/Д6)
-- или в ход на счетоводно затваряне (TEMPORARILY_CLOSED, CLOSURE_IN_PROGRESS). Потвърждаването
-- на такава чернова отказва, но т. 9 остава по листа, а износът на Прил. 9 я чете от листа.
--
-- Какво показва:
--   (1) Всяка такава РЕДОВНА чернова (не коригиращо/заличаващо копие — те по правило минават
--       в заключен период) и редовете по т. 9 за нейния месец по листовете на същия договор.
--       ⚠️ Т. 9 е по ЛИСТ × МЕСЕЦ, не по фиш: редът може да е записан от черновата, от потвърден
--       фиш за същия месец или от „Изведи т. 9 от потвърдения фиш“. Затова колоните
--       other_declarable_slips (потвърден/изплатен фиш за същия договор и месец) и
--       point9_derived_on (ръчното извеждане) — при 0 и празно т. 9 най-вероятно идва от черновата.
--   (2) Обобщение по период и вид на заключването.
--
-- Лични данни: само номер на фиш, номер на лист и идентификатор на служителя. ЕГН и имена не се
-- четат.
--
-- Как се пуска:
--   PGPASSWORD=… psql -h … -U … -d <база> -v ON_ERROR_STOP=1 -f 2026-10-18-payslip-drafts-in-locked-periods.sql
--
-- Празен резултат значи „няма засегната чернова“.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
BEGIN READ ONLY;

-- ── (1) Черновите и т. 9 за месеца им ──────────────────────────────────────────────────────
-- Заключване (А) = статус 2 (CLOSED); (Б) = подаване по декларации, обр. 1/6, в статус, който
-- заключва (BgD1PeriodLockRules.LOCKING_STATUSES). Затваряне = 4 (TEMPORARILY_CLOSED) и 5
-- (CLOSURE_IN_PROGRESS). 3 (ADJUSTING) не е заключване: в него се редактира коригиращото копие.
WITH period_lock AS (
  SELECT p.id,
         p.code,
         p.from_date,
         p.status_select,
         EXISTS (
           SELECT 1
           FROM napsubmissions_nap_submission n
           WHERE n.period = p.id
             AND n.service_code_select = 'declarations'
             AND n.service_document_type_select = 'DEC_1_6'
             AND n.submission_status_select IN
                 ('submitted', 'accepted', 'correction_required', 'submission_outcome_unknown')
         ) AS declared
  FROM base_period p
)
SELECT s.reference                                   AS payslip_no,
       s.id                                          AS payslip_id,
       pl.code                                       AS period,
       pl.status_select                              AS period_status,
       pl.declared                                   AS declared_d1_d6,
       CASE
         WHEN pl.status_select IN (4, 5) THEN 'closing'
         WHEN pl.status_select = 2 THEN 'closed'
         ELSE 'declared'
       END                                           AS lock_kind,
       co.code                                       AS company_code,
       c.employee                                    AS employee_id,
       sl.bg_document_number                         AS sick_sheet_no,
       sl.status_select                              AS sick_sheet_status,
       COUNT(l.id)                                   AS point9_lines,
       SUM(l.work_days)                              AS point9_work_days,
       STRING_AGG(DISTINCT l.payer, ', ')            AS payers,
       -- bgPoint9DerivedOn → bg_point9derived_on: Hibernate НЕ слага „_“ след цифра.
       sl.bg_point9derived_on                        AS point9_derived_on,
       (SELECT COUNT(*)
          FROM bglocalization_bg_pay_slip o
         WHERE o.employment_contract = s.employment_contract
           AND o.period = s.period
           AND o.status_select IN ('CONFIRMED', 'PAID')) AS other_declarable_slips,
       s.updated_on                                  AS payslip_updated_on
FROM bglocalization_bg_pay_slip s
JOIN period_lock pl                         ON pl.id = s.period
JOIN hr_employment_contract c               ON c.id = s.employment_contract
LEFT JOIN base_company co                   ON co.id = c.pay_company
JOIN bglocalization_bg_sick_leave sl        ON sl.employment_contract = c.id
JOIN bglocalization_bg_sick_leave_period_line l
                                            ON l.sick_leave = sl.id
                                           AND l.period_year = EXTRACT(YEAR FROM pl.from_date)
                                           AND l.period_month = EXTRACT(MONTH FROM pl.from_date)
WHERE s.status_select = 'DRAFT'
  AND COALESCE(s.bg_is_correction, FALSE) = FALSE
  AND COALESCE(s.bg_is_deletion, FALSE) = FALSE
  AND COALESCE(s.bg_is_historical_import, FALSE) = FALSE
  AND (pl.status_select IN (2, 4, 5) OR pl.declared)
GROUP BY s.reference, s.id, pl.code, pl.status_select, pl.declared, co.code, c.employee,
         sl.bg_document_number, sl.status_select, sl.bg_point9derived_on, s.employment_contract,
         s.period, s.updated_on
ORDER BY pl.code, s.reference, sl.bg_document_number;

-- ── (2) Обобщение по период и вид на заключването ─────────────────────────────────────────
SELECT p.code                                   AS period,
       p.status_select                          AS period_status,
       COUNT(DISTINCT s.id)                     AS draft_slips,
       COUNT(DISTINCT l.id)                     AS point9_lines
FROM bglocalization_bg_pay_slip s
JOIN base_period p                          ON p.id = s.period
JOIN hr_employment_contract c               ON c.id = s.employment_contract
JOIN bglocalization_bg_sick_leave sl        ON sl.employment_contract = c.id
JOIN bglocalization_bg_sick_leave_period_line l
                                            ON l.sick_leave = sl.id
                                           AND l.period_year = EXTRACT(YEAR FROM p.from_date)
                                           AND l.period_month = EXTRACT(MONTH FROM p.from_date)
WHERE s.status_select = 'DRAFT'
  AND COALESCE(s.bg_is_correction, FALSE) = FALSE
  AND COALESCE(s.bg_is_deletion, FALSE) = FALSE
  AND COALESCE(s.bg_is_historical_import, FALSE) = FALSE
  AND (p.status_select IN (2, 4, 5)
       OR EXISTS (SELECT 1
                    FROM napsubmissions_nap_submission n
                   WHERE n.period = p.id
                     AND n.service_code_select = 'declarations'
                     AND n.service_document_type_select = 'DEC_1_6'
                     AND n.submission_status_select IN
                         ('submitted', 'accepted', 'correction_required', 'submission_outcome_unknown')))
GROUP BY p.code, p.status_select
ORDER BY p.code;

ROLLBACK;

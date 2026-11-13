-- ВЪЗРАСТТА НА BG_COA.zip И НАЧАЛНИТЕ НАСТРОЙКИ НА СЧЕТОВОДСТВОТО. ЧЕТЕ САМО — пуска се с
--   PGPASSWORD=… psql -h … -U … -d <база> -v ON_ERROR_STOP=1 -f 2026-10-21-bg-coa-template-zip-age.sql
--
-- Шаблонът BG_COA НЕ се чете от кода при прилагане, а от зипа, записан в базата
-- (AccountingConfigTemplate.metaFile) при data-init на axelor-account. Деплоят не го опреснява.
-- Тоест ново дружество получава CSV-тата във вида им към created_on на зипа, а не към деплоя.
--
-- А. Зипът: зип, по-стар от CSV-тата в кода, дава на новите дружества стария сметкоплан (напр.
--    122/1222) и старите стойности на конфигурацията на сметките.
-- Б. AccountConfig по дружество: откъде идват 122/1222 и 501 (import_origin = BG_COA.zip е от
--    шаблона; празно — създадено/попълнено на ръка).
-- В. ДДС протоколите (колоните на axelor-bg-localization) — отделно, за да не спре А и Б, ако
--    базата е по-стара от тях.
\pset border 2
\pset format aligned
BEGIN READ ONLY;

-- ── А. зипът на шаблона ────────────────────────────────────────────────────────────────────
SELECT t.id, t.code, t.name, mf.file_name, mf.file_size AS байта, mf.created_on, mf.updated_on,
       (SELECT count(*) FROM account_account_config c WHERE c.accounting_config_template = t.id)
         AS приложен_на
  FROM account_accounting_config_template t
  LEFT JOIN meta_file mf ON mf.id = t.meta_file
 ORDER BY t.code;

-- ── Б. настройките на всяко дружество ──────────────────────────────────────────────────────
SELECT co.code AS дружество, ac.created_on::date AS създадена, ac.import_origin,
       tpl.code AS шаблон, ac.has_accounting_config_template_imported AS внесен,
       rp.code AS печалба, rl.code AS загуба,
       cd.code AS разлики_дт, cc.code AS разлики_кт,
       ac.allowed_tax_gap, ac.is_description_required AS описание,
       ac.partner_account_generation_mode_select AS сметки_партньори,
       ac.auto_reconcile_on_invoice AS равн_фактура, ac.auto_reconcile_on_payment AS равн_плащане,
       pin.code AS вх_режим, pout.code AS изх_режим
  FROM account_account_config ac
  JOIN base_company co ON co.id = ac.company
  LEFT JOIN account_accounting_config_template tpl ON tpl.id = ac.accounting_config_template
  LEFT JOIN account_account rp ON rp.id = ac.result_profit_account
  LEFT JOIN account_account rl ON rl.id = ac.result_loss_account
  LEFT JOIN account_account cd ON cd.id = ac.cash_position_variation_debit_account
  LEFT JOIN account_account cc ON cc.id = ac.cash_position_variation_credit_account
  LEFT JOIN account_payment_mode pin ON pin.id = ac.in_payment_mode
  LEFT JOIN account_payment_mode pout ON pout.id = ac.out_payment_mode
 ORDER BY co.code;

-- ── В. ДДС протоколите ─────────────────────────────────────────────────────────────────────
SELECT co.code AS дружество, j.code AS дневник, ch.code AS начислен, de.code AS кредит,
       ex.code AS разходна, inc.code AS насрещна
  FROM account_account_config ac
  JOIN base_company co ON co.id = ac.company
  LEFT JOIN account_journal j ON j.id = ac.bg_vat_correction_journal
  LEFT JOIN account_account ch ON ch.id = ac.bg_vat_charged_account
  LEFT JOIN account_account de ON de.id = ac.bg_vat_deductible_account
  LEFT JOIN account_account ex ON ex.id = ac.bg_vat_annual_correction_expense_account
  LEFT JOIN account_account inc ON inc.id = ac.bg_vat_correction_income_account
 ORDER BY co.code;

ROLLBACK;

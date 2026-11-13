// Generates the generated part of the role catalogue from one spec, checking
// every class and condition path against the domain index that index.js builds from the sources.
//
// Usage, from the repo root:
//   node modules/axelor-open-suite/nyxa-security/tools/index.js . <workDir>
//   node modules/axelor-open-suite/nyxa-security/tools/gen-catalogue.js <workDir> \
//        modules/axelor-open-suite/nyxa-security/src/main/resources/nyxa-security
//
// Rewrites nx_menu.csv, and the nx.acc.* / nx.md.* rows of nx_permission.csv; hand-written rows
// (e.g. the nx.baseline.* ones) are kept. Change the spec here, never the generated rows.
'use strict';
var fs = require('fs');
var path = require('path');
var scratch = process.argv[2], out = process.argv[3];
var D = require(path.join(scratch, 'domains.json'));

var A = 'com.axelor.apps.account.db.', B = 'com.axelor.apps.base.db.', G = 'com.axelor.apps.bglocalization.db.',
  S = 'com.axelor.studio.db.';
var ACTIVE = '__user__.activeCompany';
var PARTNER_SUB = 'select p.id from Partner p join p.companySet c where c = ?';

// Inheritance the index does not follow.
// SAF-T reference (НАП codes on accounts, taxes, payment modes, VAT cells, units): global, no company.
// Read for the ACC tiers and NAP roles, write for ACC Config.
var SAFT_REFERENCE = ['BgSafAccountMapping', 'BgSafTaxMapping', 'BgSafPaymentMapping', 'BgSafVatCellCode',
  'BgNraUnitOfMeasure'].map(function (n) { return G + n; });

var EXTENDS = {};
EXTENDS[A + 'MoveLineMassEntry'] = A + 'MoveLine';

function fields(fq) {
  var own = (D[fq] && D[fq].fields) || {};
  var parent = EXTENDS[fq] ? fields(EXTENDS[fq]) : {};
  var all = {}; Object.keys(parent).forEach(function (k) { all[k] = parent[k]; });
  Object.keys(own).forEach(function (k) { all[k] = own[k]; });
  return all;
}
// Verify a dotted path like "move.company" from an entity, ending on Company.
function checkPath(fq, p) {
  var cur = fq;
  p.split('.').forEach(function (seg) {
    var f = fields(cur)[seg];
    if (!f) throw new Error(fq + ': no field "' + seg + '" (path ' + p + ')');
    cur = f.ref;
  });
  if (cur !== B + 'Company') throw new Error(fq + ': path ' + p + ' ends on ' + cur + ', not Company');
}

// cond: null (none), a company path ("company", "move.company"), or {raw, check: [paths]}
function co(p) { return p; }
// An employee's contact partner (name, address, bank account for the salary) has no companySet:
// the employee wizard creates it without one. It follows its employee instead, with the same rule
// as EMPLOYEE below: the contract's paying company, or no contract yet and created by this user.
var EMP_CONTACT_SUB = 'select e.contactPartner.id from Employee e left join e.mainEmploymentContract m'
  + ' where m.payCompany = ? or (m.id is null and e.createdBy = ?)';
var EMP_CONTACT_VALUES = [ACTIVE, '__user__'];
var PARTNER = {
  raw: '? member of self.companySet or self.id in (' + EMP_CONTACT_SUB + ')',
  check: [], params: 3, values: [ACTIVE].concat(EMP_CONTACT_VALUES)
};
var VIA_PARTNER = {
  raw: 'self.partner.id in (' + PARTNER_SUB + ') or self.partner.id in (' + EMP_CONTACT_SUB + ')',
  check: [], params: 3, values: [ACTIVE].concat(EMP_CONTACT_VALUES)
};
// Same rule as VIA_PARTNER plus the account's own company. The partner is compared as an entity
// ("self.partner in (select p ...)") rather than by id: the same rows, 12 characters shorter, so the
// condition stays within MAX_CONDITION. No dotted path on self outside the subqueries, so an account
// without a partner is not dropped by an implicit join.
var BANK = {
  raw: 'self.company = ? or self.partner in (select p from Partner p join p.companySet c where c = ?)'
    + ' or self.partner in (select e.contactPartner from Employee e left join e.mainEmploymentContract m'
    + ' where m.payCompany = ? or (m.id is null and e.createdBy = ?))',
  check: ['company'], params: 4, values: [ACTIVE, ACTIVE].concat(EMP_CONTACT_VALUES)
};
// Employee: by the paying company of its contract. The creation wizard saves a new employee once
// before the contract exists, and Axelor re-checks the condition on the saved record, so an
// employee without a contract is allowed to the user who created it. The contract is matched by
// subquery: the dotted path would be an inner join and drop the "is null" rows. `path` is what the
// employee's children inherit; `values` gives each placeholder its own parameter.
var EMPLOYEE = {
  raw: 'self.mainEmploymentContract.id in (select c.id from EmploymentContract c where c.payCompany = ?)'
    + ' or (self.mainEmploymentContract is null and self.createdBy = ?)',
  path: 'mainEmploymentContract.payCompany',
  check: ['mainEmploymentContract.payCompany'],
  params: 2,
  values: [ACTIVE, '__user__']
};

// [class, cond, flags per role]. Missing role = no row. Tiers are self-contained.
var ACC = [
  // operations
  [A + 'Move', co('company'), 'RE', 'RWCDE'],
  [A + 'MoveLine', co('move.company'), 'RE', 'RWCDE'],
  [A + 'MoveLineMassEntry', co('moveMassEntry.company'), null, 'RWCD'],
  [A + 'Invoice', co('company'), 'RE', 'RWCDE'],
  [A + 'InvoiceLine', co('invoice.company'), 'RE', 'RWCD'],
  [A + 'InvoiceLineTax', co('invoice.company'), 'RE', 'RWCD'],
  [A + 'InvoiceTerm', co('company'), 'RE', 'RWCD'],
  [A + 'InvoicePayment', co('invoice.company'), 'RE', 'RWC'],
  [A + 'InvoiceTermPayment', co('invoicePayment.invoice.company'), 'R', 'RWC'],
  [A + 'InvoiceNote', co('invoice.company'), 'R', 'RWCD'],
  [A + 'Reconcile', co('company'), 'RE', 'RWCD'],
  [A + 'ReconcileGroup', co('company'), 'RE', 'RWCD'],
  [A + 'AccountClearance', co('company'), 'RE', 'RWC'],
  [A + 'TaxPaymentMoveLine', co('moveLine.move.company'), 'R', 'RWCD'],
  [A + 'PaymentMoveLineDistribution', co('move.company'), 'R', 'RWCD'],
  [A + 'PaymentVoucher', co('company'), 'RE', 'RWCDE'],
  [A + 'PayVoucherDueElement', co('paymentVoucher.company'), 'R', 'RWCD'],
  [A + 'PayVoucherElementToPay', co('paymentVoucher.company'), 'R', 'RWCD'],
  [A + 'PaymentSession', co('company'), 'RE', 'RWC'],
  [A + 'DepositSlip', co('company'), 'RE', 'RWC'],
  [A + 'ChequeRejection', co('company'), 'RE', 'RWC'],
  [A + 'Reimbursement', co('company'), 'RE', 'RWC'],
  [A + 'Irrecoverable', co('company'), 'RE', 'RWC'],
  [A + 'DebtRecovery', co('company'), 'RE', 'RWC'],
  [A + 'DebtRecoveryHistory', co('debtRecovery.company'), 'R', 'R'],
  [A + 'AccountingSituation', co('company'), 'RE', 'RWC'],
  [A + 'MoveTemplate', co('company'), 'R', 'RWCD'],
  [A + 'MoveTemplateLine', co('moveTemplate.company'), 'R', 'RWCD'],
  [A + 'AccountingReport', co('company'), 'RE', 'RWC'],
  [A + 'AccountingReportMoveLine', co('accountingReport.company'), 'R', 'RWC'],
  [A + 'AccountingReportValue', co('accountingReport.company'), 'R', 'RWC'],
  [A + 'FixedAsset', co('company'), 'RE', 'RWC'],
  [A + 'FixedAssetLine', co('fixedAsset.company'), 'RE', 'RWC'],
  [A + 'FixedAssetDerogatoryLine', co('fixedAsset.company'), 'R', 'RWC'],
  [A + 'MoveLineQuery', co('company'), 'RWC', 'RWC'],
  [A + 'MoveLineQueryLine', co('moveLineQuery.company'), 'RWC', 'RWC'],
  [A + 'AccountingBatch', co('company'), 'R', 'R', 'RWC'],
  [A + 'ClosureAssistant', co('company'), 'R', 'R', 'RWC'],
  [A + 'ClosureAssistantLine', co('closureAssistant.company'), 'R', 'R', 'RWC'],
  [A + 'InvoicingPaymentSituation', co('company'), 'R', 'R'],
  [G + 'BgAssetPlanReport', co('company'), 'RE', 'RWC'],
  [G + 'BgAssetPlanLine', co('report.company'), 'RE', 'RWCD'],
  [G + 'BgTaxDifferenceReport', co('company'), 'RE', 'RWC'],
  [G + 'BgTaxDifferenceLine', co('report.company'), 'RE', 'RWCD'],
  // ГОД към НСИ (меню „НСИ“): справка за пренасяне; без триене — заключената е следа от пренесеното.
  [G + 'BgNsiGodReport', co('company'), 'RE', 'RWCE'],
  [G + 'BgNsiGodLine', co('report.company'), 'RE', 'RWCDE'],
  [G + 'BgSubsequentCost', co('company'), 'RE', 'RWC'],
  [G + 'BgFixedAssetWriteOff', co('company'), 'RE', 'RWC'],
  [G + 'BgAssetMaterialityIncrease', co('company'), 'RE', 'RWC'],
  // configuration and reference: read-only for both tiers (writes are ACC Config, later)
  [A + 'Account', co('company'), 'R', 'R'],
  [A + 'AccountType', null, 'R', 'R'],
  [A + 'Journal', co('company'), 'R', 'R'],
  [A + 'JournalType', null, 'R', 'R'],
  [A + 'Tax', null, 'R', 'R'],
  [A + 'TaxLine', null, 'R', 'R'],
  [A + 'TaxType', null, 'R', 'R'],
  [A + 'TaxEquiv', null, 'R', 'R'],
  [A + 'AccountEquiv', null, 'R', 'R'],
  [A + 'FiscalPosition', null, 'R', 'R'],
  [A + 'PaymentMode', null, 'R', 'R'],
  [SAFT_REFERENCE[0], null, 'R', 'R'],
  [SAFT_REFERENCE[1], null, 'R', 'R'],
  [SAFT_REFERENCE[2], null, 'R', 'R'],
  [SAFT_REFERENCE[3], null, 'R', 'R'],
  [SAFT_REFERENCE[4], null, 'R', 'R'],
  [A + 'PaymentCondition', null, 'R', 'R'],
  [A + 'PaymentConditionLine', null, 'R', 'R'],
  [A + 'AccountManagement', co('company'), 'R', 'R'],
  [A + 'AccountConfig', co('company'), 'R', 'R'],
  [A + 'FinancialDiscount', null, 'R', 'R'],
  [A + 'ServiceType', null, 'R', 'R'],
  [A + 'AccountingReportType', null, 'R', 'R'],
  [A + 'FixedAssetCategory', co('company'), 'R', 'R'],
  [A + 'FixedAssetType', null, 'R', 'R'],
  [A + 'AssetDisposalReason', null, 'R', 'R'],
  [A + 'MoveTemplateType', null, 'R', 'R'],
  [A + 'PaymentDelayReason', null, 'R', 'R'],
  [A + 'DebtRecoveryMethod', null, 'R', 'R'],
  [A + 'DebtRecoveryMethodLine', null, 'R', 'R'],
  [A + 'PfpPartialReason', null, 'R', 'R'],
  [A + 'InvoiceProductStatement', null, 'R', 'R'],
  [G + 'BgAssetRemovalAccount', co('company'), 'R', 'R'],
  [B + 'Year', co('company'), 'R', 'R', 'RWC'],
  // Create as well: "Generate periods" on a fiscal year saves the new periods with the year.
  [B + 'Period', co('year.company'), 'R', 'R', 'RWC'],
  [B + 'Sequence', co('company'), 'R', 'R'],
  [B + 'CurrencyConversionLine', null, 'R', 'R', 'RWC'],
  [B + 'PrintingTemplate', null, 'R', 'R'],
  [S + 'AppAccount', null, 'R', 'R'],
  [S + 'AppInvoice', null, 'R', 'R'],
  [S + 'AppBase', null, 'R', 'R'],
  // D10: the manager picks staff users on the company-assignment screen. Write as well, only so the
  // Application Config › Users list and page offer Edit / Undo like every other screen: the user
  // self-edit guard (G12) still refuses every change to another user's row outside an explicitly
  // opened scope, and the roles themselves change only through Keycloak (TenantUserService).
  // Nyxa technical admins (group admins, never the local 'admin') are listed too, so the client
  // administrator can assign them companies; on their rows the guard lets only the companies change.
  ['com.axelor.auth.db.User', { raw: "self.group.code = 'staff' OR (self.group.code = 'admins' AND self.code <> 'admin')", check: [], params: 0 }, null, null, 'RW'],
  // Users screen: the client administrator picks the roles to grant. Never nyxa.admin, which only
  // infra grants; the screen narrows the list further to the catalogue (DirectoryRules).
  ['com.axelor.auth.db.Role', { raw: "self.name NOT IN ('nyxa.admin', 'Staff Baseline')", check: [], params: 0 },
    null, null, 'R']
];
var MD = [
  [B + 'Partner', PARTNER, 'RE', 'RWCE'],
  [B + 'PartnerAddress', VIA_PARTNER, 'R', 'RWC'],
  [B + 'Address', null, 'R', 'RWC'],
  [B + 'BankDetails', BANK, 'R', 'RWC'],
  [B + 'TradingName', co('company'), 'R', 'R'],
  [B + 'Product', null, 'R', 'R'],
  [B + 'ProductCategory', null, 'R', 'R'],
  [B + 'PartnerCategory', null, 'R', 'R'],
  [B + 'PartnerLinkType', null, 'R', 'R'],
  [B + 'PriceList', null, 'R', 'R'],
  [B + 'PriceListLine', null, 'R', 'R'],
  [B + 'ProductCompany', co('company'), 'R', 'R'],
  [B + 'Bank', null, 'R', 'R']
];


// ACC Config: write access to accounting configuration. Outside the ladder and mapped
// to no position by default; combine it with an ACC tier, which gives read on everything else.
// Global models (taxes, payment modes, types) are shared by every company of the tenant.
var CONFIG = [
  // Company setup: the declarants on the company card (printed D1/D6, SAF-T, art. 55). Read by the
  // areas that print or file with them, written by client.admin only.
  [G + 'BgDeclarant', co('company'), 'RWCD'],
  [A + 'AccountConfig', co('company'), 'RW'],
  [A + 'Account', co('company'), 'RWC'],
  [A + 'AccountType', null, 'RWC'],
  [A + 'Journal', co('company'), 'RWC'],
  [A + 'JournalType', null, 'RWC'],
  [A + 'Tax', null, 'RWC'],
  [A + 'TaxLine', null, 'RWC'],
  [A + 'TaxType', null, 'RWC'],
  [A + 'TaxEquiv', null, 'RWC'],
  [A + 'AccountEquiv', null, 'RWC'],
  [A + 'FiscalPosition', null, 'RWC'],
  [A + 'PaymentMode', null, 'RWC'],
  [A + 'PaymentCondition', null, 'RWC'],
  [A + 'PaymentConditionLine', null, 'RWCD'],
  [A + 'AccountManagement', co('company'), 'RWCD'],
  [A + 'FinancialDiscount', null, 'RWC'],
  [A + 'ServiceType', null, 'RWC'],
  [A + 'AccountingReportType', null, 'RWC'],
  [A + 'AccountingReportConfigLine', null, 'RWCD'],
  [A + 'AccountingReportAnalyticConfigLine', null, 'RWCD'],
  [A + 'FixedAssetCategory', co('company'), 'RWC'],
  [A + 'FixedAssetType', null, 'RWC'],
  [A + 'AssetDisposalReason', null, 'RWC'],
  [A + 'MoveTemplateType', null, 'RWC'],
  [A + 'PfpPartialReason', null, 'RWC'],
  [A + 'PaymentDelayReason', null, 'RWC'],
  [A + 'DebtRecoveryMethod', null, 'RWC'],
  [A + 'DebtRecoveryMethodLine', null, 'RWCD'],
  [A + 'DebtRecoveryConfigLine', co('accountConfig.company'), 'RWCD'],
  [A + 'PayerQualityConfigLine', null, 'RWCD'],
  [A + 'InterestRateHistoryLine', null, 'RWCD'],
  [A + 'InvoiceProductStatement', co('accountConfig.company'), 'RWCD'],
  [A + 'AnalyticAccount', null, 'RWC'],
  [A + 'AnalyticAxis', null, 'RWC'],
  [A + 'AnalyticAxisByCompany', co('accountConfig.company'), 'RWCD'],
  [A + 'AnalyticJournal', co('company'), 'RWC'],
  [A + 'AnalyticJournalType', null, 'RWC'],
  [A + 'AnalyticLevel', null, 'RWC'],
  [A + 'AnalyticGrouping', null, 'RWC'],
  [A + 'AnalyticGroupingValue', null, 'RWCD'],
  [A + 'AnalyticRules', co('company'), 'RWC'],
  [A + 'AnalyticDistributionTemplate', co('company'), 'RWC'],
  [A + 'AnalyticDistributionLine', co('analyticDistributionTemplate.company'), 'RWCD'],
  [A + 'TaxNumber', co('company'), 'RWC'],
  [G + 'BgAssetRemovalAccount', co('company'), 'RWC'],
  [B + 'Sequence', co('company'), 'RW'],
  [B + 'SequenceVersion', co('sequence.company'), 'RWC']
].concat(SAFT_REFERENCE.map(function (c) { return [c, null, 'RWCD']; }));

// VAT: ДДС ledgers and returns, VIES, art. 73 coefficients, annual corrections.
// Ladder: VAT Read < VAT Prepare < VAT Submit. State changes are also gated in the controllers (G3/G4).
var VAT = [
  [G + 'BgVatReturn', co('company'), 'RE', 'RWC'],
  [G + 'BgVatSalesLine', co('bgVatReturn.company'), 'RE', 'RWCD'],
  [G + 'BgVatPurchaseLine', co('bgVatReturn.company'), 'RE', 'RWCD'],
  [G + 'BgVatPackage', co('company'), 'R', 'RWC'],
  [G + 'BgVatPackageFile', co('bgVatPackage.company'), 'R', 'RWC'],
  [G + 'BgVatPackageEvent', co('bgVatReturn.company'), 'R', 'R'],
  [G + 'BgVatSubmission', co('company'), 'R', 'R', 'RWC'],
  [G + 'BgVatCoefficient', co('company'), 'RE', 'RWCD'],
  [G + 'BgVatAnnualCorrection', co('company'), 'RE', 'RWCD'],
  [G + 'BgVatAnnualCorrectionLine', co('annualCorrection.company'), 'R', 'RWCD']
];

// NAP: SAF-T and the submission pipeline, plus the non-labour income filings.
// PARALLEL roles: Prepare, Approve and Submit each include NAP Read but not each other, so that
// approving never implies preparing (segregation of duties). Columns: Read, Prepare, Approve, Submit;
// each non-Read role is emitted as its own flags united with Read's.
var N = 'com.axelor.apps.napsubmissions.db.';
var NAP_EVENT = { raw: 'self.id in (select e.id from NapAuditEvent e left join e.submission s'
  + ' left join e.saftReport r where s.company = ? or r.company = ?)', check: [], params: 2 };
var NAP = [
  [N + 'SaftReport', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [N + 'SaftValidationIssue', co('saftReport.company'), 'R', 'RWC', 'R', 'R'],
  [N + 'NapArtifact', co('company'), 'R', 'RWC', 'R', 'R'],
  [N + 'NapSubmission', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [N + 'NapSubmissionFile', co('submission.company'), 'R', 'RWC', 'R', 'RWC'],
  [N + 'NapSubmissionArtifact', co('submission.company'), 'R', 'RWC', 'R', 'RWC'],
  [N + 'NapProcessingResult', co('submission.company'), 'R', 'RWC', 'R', 'RWC'],
  [N + 'NapAuditEvent', NAP_EVENT, 'R', 'R', 'R', 'R'],
  [G + 'BgArt55Declaration', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [G + 'BgArt73Para1Report', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [G + 'BgArt73Para1Record', co('report.company'), 'R', 'RWCD', 'R', 'R'],
  // FATCA: статусът на дружеството, годишният отчет и сметките в него.
  [G + 'BgFatcaProfile', co('company'), 'R', 'RWC', 'R', 'R'],
  [G + 'BgFatcaReport', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [G + 'BgFatcaAccount', co('report.company'), 'R', 'RWCD', 'R', 'R'],
  [G + 'BgIndividualIncomePayment', co('company'), 'RE', 'RWC', 'RW', 'RW'],
  [G + 'BgIncomeType', null, 'R', 'R', 'R', 'R'],
  [G + 'BgIncomePaymentFeedWizard', co('company'), null, 'RWC', null, null]
].concat(SAFT_REFERENCE.map(function (c) { return [c, null, 'R', 'R', 'R', 'R']; }));
// TRZ: payroll. Ladder TRZ Read < TRZ Operate < TRZ Submit. Payroll records reach
// their company through the employment contract's pay company; a pay slip belongs either to a
// contract or to a self-insured registration, so it is scoped by a subquery over both (LEFT JOINs:
// a path expression would inner-join and drop the other kind). Reference data is read-only for all
// tiers; writing it stays with admins.
var HR = 'com.axelor.apps.hr.db.';
var PAYSLIPS = 'select p.id from BgPaySlip p left join p.employmentContract c'
  + ' left join p.bgSelfInsuredRegistration r left join r.employee e left join e.mainEmploymentContract m'
  + ' where c.payCompany = ? or m.payCompany = ?';
var TRZ = [
  [G + 'BgPaySlip', { raw: 'self.id in (' + PAYSLIPS + ')', check: [], params: 2 }, 'RE', 'RWC'],
  [G + 'BgPaySlipLine', { raw: 'self.paySlip.id in (' + PAYSLIPS + ')', check: [], params: 2 }, 'R', 'RWCD'],
  [G + 'BgPaySlipCapUsage', { raw: 'self.paySlip.id in (' + PAYSLIPS + ')', check: [], params: 2 }, 'R', 'RWCD'],
  [G + 'BgCompensation', co('employmentContract.payCompany'), 'RE', 'RWC'],
  [G + 'BgTerminationCompensation', co('employmentContract.payCompany'), 'RE', 'RWC'],
  [G + 'BgCompensationPayment', co('bgTerminationCompensation.employmentContract.payCompany'), 'R', 'RWCD'],
  [G + 'BgContractAmendment', co('employmentContract.payCompany'), 'R', 'RWC'],
  [G + 'BgContractTransfer', co('employmentContract.payCompany'), 'R', 'RWC'],
  // ЕТЗ — записът за прекратяване: закачалката на подаването и единствената дата.
  [G + 'BgContractTermination', co('employmentContract.payCompany'), 'R', 'RWC'],
  [G + 'BgDeclaredWorkHours', co('employmentContract.payCompany'), 'R', 'RWCD'],
  [G + 'BgSickLeave', co('employmentContract.payCompany'), 'RE', 'RWC'],
  [G + 'BgSickLeavePeriodLine', co('sickLeave.employmentContract.payCompany'), 'R', 'RWCD'],
  [G + 'BgEmployeeChild', co('employee.mainEmploymentContract.payCompany'), 'R', 'RWCD'],
  [G + 'BgSelfInsuredRegistration', co('employee.mainEmploymentContract.payCompany'), 'R', 'RWC'],
  [G + 'BgSelfInsuredActivityPeriod', co('bgSelfInsuredRegistration.employee.mainEmploymentContract.payCompany'), 'R', 'RWCD'],
  [G + 'BgContributionPreview', co('company'), 'R', 'RWC'],
  [G + 'BgSocialExpenseException', co('company'), 'R', 'RWC'],
  [G + 'BgPayrollLedgerWizard', co('company'), 'RWC', 'RWC'],
  [G + 'BgPayrollSepaWizard', co('company'), null, 'RWC'],
  [G + 'MicroinvestImportWizard', null, null, 'RWC'],
  // Declarations to НОИ / НАП: the Submit tier.
  [G + 'BgD1Declaration', co('company'), 'R', 'R', 'RWC'],
  [G + 'BgD1Record', co('bgD1Declaration.company'), 'R', 'R', 'RWCD'],
  [G + 'BgD6Declaration', co('company'), 'R', 'R', 'RWC'],
  [G + 'BgApp9Declaration', co('sickLeave.employmentContract.payCompany'), 'R', 'RWC', 'RWC'],
  [G + 'BgApp9Export', co('company'), 'R', 'R', 'RWC'],
  [G + 'BgApp9ExportLine', co('bgApp9Export.company'), 'R', 'R', 'RWCD'],
  [G + 'BgArt73Para6Report', co('company'), 'R', 'R', 'RWC'],
  [G + 'BgArt73Para6Record', co('report.company'), 'R', 'R', 'RWCD'],
  [G + 'BgArt73Para6Annual', co('report.company'), 'R', 'R', 'RWCD'],
  // HR records payroll works on.
  [HR + 'Employee', EMPLOYEE, 'RE', 'RWC'],
  [HR + 'EmploymentContract', co('payCompany'), 'RE', 'RWC'],
  [HR + 'LeaveRequest', co('company'), 'R', 'RWC'],
  [HR + 'LeaveLine', co('employee.mainEmploymentContract.payCompany'), 'R', 'RWC'], // C: opening leave balances
  [HR + 'LeaveReason', null, 'R', 'R'],
  [HR + 'EndOfContractReason', null, 'R', 'R'],
  [HR + 'ExtraHoursType', null, 'R', 'R'],
  [HR + 'EmploymentContractType', null, 'R', 'R'],
  [HR + 'EmploymentContractSubType', null, 'R', 'R'],
  [HR + 'EmploymentContractTemplate', null, 'R', 'R'],
  // Reference data: read-only for every tier.
  [G + 'BgAbsenceType', null, 'R', 'R'],
  [G + 'BgCompensationType', null, 'R', 'R'],
  [G + 'BgContributionFund', null, 'R', 'R'],
  [G + 'BgGarnishmentScale', null, 'R', 'R'],
  [G + 'BgInsuranceContribution', null, 'R', 'R'],
  [G + 'BgInsuredType', null, 'R', 'R'],
  [G + 'BgMinIncomeOverride', null, 'R', 'R'],
  [G + 'BgMinInsuranceIncome', null, 'R', 'R'],
  [G + 'BgMinInsuranceIncomeAmount', null, 'R', 'R'],
  [G + 'BgNkid', null, 'R', 'R'],
  [G + 'BgNkpd', null, 'R', 'R'],
  [G + 'BgPaySlipLineType', null, 'R', 'R'],
  [G + 'BgPayrollParameter', null, 'R', 'R'],
  [G + 'BgSocialExpenseCap', null, 'R', 'R'],
  [G + 'BgTzpbRate', null, 'R', 'R'],
  [G + 'BgIncomeType', null, 'R', 'R']
];

// OCR Upload: upload documents for the AI-OCR pipeline. Uploading first creates a
// MetaFile through RestService.upload, which checks CREATE on MetaFile: own files only. File
// ownership of an upload's attachments is enforced in AiOcrUploadManagementRepository (G7).
var OCR = [
  ['com.axelor.apps.aiocr.db.AiOcrUpload', co('company'), 'RWC'],
  ['com.axelor.meta.db.MetaFile', { raw: 'self.createdBy = ?', check: [], params: 1, value: '__user__' }, 'RWC']
];

// Menus client.admin inherits from the former ACC Finalize / ACC Config tiers.
var finalizeOnly = ['account-root-periodic-processing-annual-processing',
  'account-root-periodic-processing-annual-processing-year-closure',
  'account-root-periodic-processing-annual-move-consistency-control',
  'account-conf', 'account-conf-financial', 'account-conf-financial-year', 'account-conf-financial-period',
  'nx-company-assignment'];
var configMenus = ['account-root', 'account-conf',
  'account-conf-financial', 'account-conf-financial-account', 'account-conf-financial-account-management',
  'account-conf-financial-account-type', 'account-conf-financial-accounting-plan',
  'account-conf-financial-financial-discount', 'account-conf-financial-journal', 'account-conf-financial-journal-type',
  'account-conf-financial-period', 'account-conf-financial-service-types', 'account-conf-financial-tax',
  'account-conf-financial-tax-number', 'account-conf-financial-tax-type', 'account-conf-financial-year',
  'account-conf-fiscal-position',
  'account-conf-payment', 'account-conf-payment-payment-condition', 'account-conf-payment-payment-mode',
  'account-conf-reporting', 'account-root-accounting-conf-report-types-export', 'account-root-accounting-conf-report-types-report',
  'account-conf-analytic', 'account-analytic-grouping', 'account-analytic-grouping-value', 'account-analytic-rules',
  'account-analytic-structure', 'account-conf-analytic-account', 'account-conf-analytic-accounting-plan',
  'account-conf-analytic-axis', 'account-conf-analytic-distribution-template', 'account-conf-analytic-journal',
  'account-conf-analytic-journal-type',
  'move-template-root', 'move-template', 'move-template-type',
  'account-root-debt-recovery', 'debt-recovery-root-conf', 'debt-recovery-root-conf-debt-recovery-method',
  'debt-recovery-root-conf-payment-delay-reason',
  'account-root-asset-management', 'account-root-asset-management-conf',
  'account-root-asset-management-asset-disposal-reasons', 'account-root-asset-management-conf-asset-categories',
  'account-root-asset-management-conf-asset-type', 'bg-asset-removal-account'];

// ════════════════════════════════════════════════════════════════════════════════════════════════
// PER-AREA ROLES . The arrays above are no longer emitted per
// capability role; they are the hand-checked SOURCE of company conditions and flags. Roles are the
// Keycloak role names, mapped 1:1: <area>.read / <area>.write per root menu, sub-roles for НАП, НОИ,
// payroll and the AI chat, all.read / all.write over everything, and client.admin for configuration
// and closing. Write implies read: every .write row carries R.
// ════════════════════════════════════════════════════════════════════════════════════════════════

var MENUS = require(path.join(scratch, 'menus.json'));
var ACTIONS = require(path.join(scratch, 'actions.json'));
var COMPANY = B + 'Company';
// BgPaySlip.company and BgCompensation.company exist ONLY for the (company, reference) key — a
// copy of the payer taken when the number is given, NULL on existing rows until the migration. The
// payer has one source, the contract, and the catalogue must not read these fields
// (BgQueryFieldTest#nobodyReadsTheNumberingCompany). A hop to them uses their curated TRZ condition.
var NUMBERING_ONLY_COMPANY = [G + 'BgPaySlip', G + 'BgCompensation'];

function union() {
  var all = Array.prototype.slice.call(arguments).filter(Boolean).join('');
  var u = 'RWCDE'.split('').filter(function (f) { return all.indexOf(f) >= 0; }).join('');
  return u || null;
}

// Curated per-model slots: read (the .read role), write (the .write role), admin (client.admin).
var CUR = {};
function curate(spec, slot) {
  spec.forEach(function (s) {
    var c = CUR[s[0]] = CUR[s[0]] || { cond: undefined };
    if (c.cond === undefined && s[1] !== undefined) c.cond = s[1];
    var v = slot(s);
    ['read', 'write', 'admin'].forEach(function (k) { if (v[k] !== undefined) c[k] = union(c[k], v[k]); });
  });
}
curate(ACC, function (s) { return { read: s[2], write: s[3] === undefined ? s[2] : s[3], admin: union(s[3] === undefined ? s[2] : s[3], s[4]) }; });
curate(MD, function (s) { return { read: s[2], write: s[3] === undefined ? s[2] : s[3] }; });
curate(CONFIG, function (s) { return { admin: s[2] }; });
curate(VAT, function (s) { return { read: s[2], write: union(s[3], s[4]) }; });
curate(NAP, function (s) { return { read: s[2], write: union(s[3], s[4], s[5]) }; });
curate(TRZ, function (s) { return { read: s[2], write: union(s[3], s[4]) }; });
curate(OCR, function (s) { return { read: 'R', write: s[2] }; });
var MD_MODELS = MD.map(function (s) { return s[0]; });
var ADMIN_ONLY = {};
CONFIG.forEach(function (s) { ADMIN_ONLY[s[0]] = true; });

// Menu tree helpers.
var KIDS = {};
Object.keys(MENUS).forEach(function (n) {
  var p = MENUS[n].parent; if (p) (KIDS[p] = KIDS[p] || []).push(n);
});
// Settings and operator screens inside the area trees. No area role gets them: the first group is
// for technical admins only (module, AI and audit settings; they reach them through the admins
// group), the second is client.admin's.
var ADMIN_MENUS = ['nap-settings', 'bg-localization-settings', 'ai-accountant-settings',
  'ai-accountant-stats', 'ai-ocr-upload-audit-root'];
var CLIENT_ADMIN_MENUS = ['bg-fixed-asset-realization-settings', 'bg-microinvest-accounting-import'];
// Menus a module hides for everyone (hidden="true": an id override of a foreign menu, or a tombstone
// of our own removed screen). No role gets them; the grants they had are retired via nx_retired.csv.
// nap-saft-reports: the old SAF-T list, replaced by bg-compliance-vat-saft (G6b), hidden by
// axelor-bg-localization's Menu-nap-hide.xml.
// bg-compliance-vat-export: "Генериране на ДДС дневници", removed 2026-10-26 (BgVatReturnWizard is
// gone); its tombstone in axelor-bg-localization's Menu.xml hides the row left in existing databases.
// nap-all-submissions: "НАП › Всички подавания", retired 2026-10-30. The generic "Submit via API"
// path it exposed was never implemented (UnsupportedNapApiSubmissionClient always answered "not
// configured"); Д1/Д6 file from "Подаване Д1/Д6 към НАП" and SAF-T has its own menus. Its tombstone
// in nyxa-nap-submissions' Menu.xml hides the row left in existing databases.
// supplychain-declarations-of-exchanges: DEB/DES ("Декларации за обмен"), the French intra-EU
// exchange declarations, hidden 2026-11-05 by axelor-bg-localization's Menu-account-hide.xml; the
// DeclarationOfExchanges records stay.
// account-root-payments-cheque-deposit-slip-all ("Депозитни фишове"),
// account-root-periodic-processing-cheque-reject ("Отхвърляне на чекове"), account-root-bank-order-lcr
// (LCR, with its only child "LCR bank orders") and stock-root-logistical-forms ("Логистични
// формуляри"): French instruments, hidden 2026-11-12 by axelor-bg-localization's
// Menu-account-hide.xml and Menu-stock-hide.xml; the records and the model rights stay.
// menu-team, menu-calendar, menu-dms: the top-level "Teamwork", "Calendar" and "Documents", hidden
// 2026-11-12 by Menu-base-hide.xml (the manual "Hidden menu" tick is reset by every deploy's view
// re-import). No area grants them, so listing them changes no row.
// bg-bnb-spb6: "Accounting › Reportings › БНБ — СПБ-6А/6Б", hidden 2026-11-12 by BgBnbSpb6.xml once the
// report moved to the top-level "БНБ" (bnb-root › bnb-spb6, same action); its grants are retired.
// ⚠️ menu-project-root ("Project management") is hidden the same day by Menu-project-hide.xml but
// is deliberately NOT listed: excluding it drops the whole projects subtree from the `projects`
// area (−42 menu rows, −319/+33 permission rows, incl. all.* and client.admin losing UnitConversion,
// Frequency, Site and write on Project). That is a role decision, not a hidden menu; until it is
// taken, the grants stay on rows nobody sees.
var HIDDEN_MENUS = ['nap-saft-reports', 'bg-compliance-vat-export', 'nap-all-submissions',
  'supplychain-declarations-of-exchanges', 'account-root-payments-cheque-deposit-slip-all',
  'account-root-periodic-processing-cheque-reject', 'account-root-bank-order-lcr',
  'stock-root-logistical-forms', 'menu-team', 'menu-calendar', 'menu-dms', 'bg-bnb-spb6'];
HIDDEN_MENUS.forEach(function (n) { if (!MENUS[n]) throw new Error('unknown menu ' + n); });
// Screens no role gets at all. The French FEC import (and its import types) picks the target
// company from the file name, with the first company in the database as the fallback, and it is
// not a Bulgarian format: removed rather than fixed (decision 2026-10-21). Their earlier grants are
// retired via nx_retired.csv. Since 2026-11-12 fec-import is also hidden for everyone, the
// administrator included, by axelor-bg-localization's Menu-account-hide.xml.
var RETIRED_MENUS = ['fec-import', 'account-conf-import-fec-type'];
RETIRED_MENUS.forEach(function (n) { if (!MENUS[n]) throw new Error('unknown menu ' + n); });

function subtree(root, exclude) {
  exclude = (exclude || []).concat(ADMIN_MENUS, CLIENT_ADMIN_MENUS, HIDDEN_MENUS, RETIRED_MENUS);
  if (!MENUS[root]) throw new Error('unknown menu ' + root);
  var out = [];
  (function walk(n) {
    if (exclude.indexOf(n) >= 0) return;
    out.push(n);
    (KIDS[n] || []).forEach(walk);
  })(root);
  return out;
}

// Generic company condition: own company field, else through the parent the record belongs to.
function companyPath(fq, parentFq, parentPath) {
  var f = fields(fq);
  if (f.company && f.company.ref === COMPANY) return 'company';
  if (parentFq && parentPath) {
    for (var k in f) {
      if (f[k].kind === 'many-to-one' && f[k].ref === parentFq) return k + '.' + parentPath;
    }
  }
  // One hop further: a many-to-one to a record that has its own company (e.g. an analytic line's
  // move line, a reconciliation line's reconciliation). Audit fields never count. Partner never
  // counts either: it is hand-checked by companySet (PARTNER), and Partner.company is NULL
  // on existing partners until they are migrated.
  var names = Object.keys(f).sort();
  for (var i = 0; i < names.length; i++) {
    var g = f[names[i]], r = g.ref;
    if (g.kind !== 'many-to-one' || !r || r === COMPANY || r === B + 'Partner' || !APP_PKG.test(r) || /^(createdBy|updatedBy)$/.test(names[i])) continue;
    var rf = fields(r);
    if (rf.company && rf.company.ref === COMPANY && NUMBERING_ONLY_COMPANY.indexOf(r) < 0) return names[i] + '.company';
    if (CUR[r] && pathOf(CUR[r].cond)) return names[i] + '.' + pathOf(CUR[r].cond);
  }
  return null;
}
function condOf(fq, parentFq, parentPath) {
  if (CUR[fq] && CUR[fq].cond !== undefined) return CUR[fq].cond;
  return companyPath(fq, parentFq, parentPath);
}
function pathOf(cond) { return typeof cond === 'string' ? cond : (cond && cond.path) || null; }

var APP_PKG = /^com\.axelor\.apps\./;

// Models an area works on: what its menus open, their owned children (one-to-many, recursively),
// and read access to what those point at (many-to-one, one level) so pickers and labels work.
function areaModels(menuNames, seeds) {
  var models = {}; // fq -> {cond, kind: 'main'|'child'|'ref'}
  function add(fq, cond, kind) {
    if (!D[fq]) return false;
    if (models[fq]) {
      if (kind !== 'ref' && models[fq].kind === 'ref') models[fq].kind = kind;
      return false;
    }
    models[fq] = { cond: cond, kind: kind };
    return true;
  }
  var mains = [];
  menuNames.forEach(function (n) {
    var a = MENUS[n].action, fq = a && ACTIONS[a];
    if (fq && D[fq] && APP_PKG.test(fq) && add(fq, condOf(fq), 'main')) mains.push(fq);
  });
  (seeds || []).forEach(function (fq) { if (add(fq, condOf(fq), 'main')) mains.push(fq); });
  // Owned children.
  var queue = mains.slice();
  while (queue.length) {
    var p = queue.shift(), pf = fields(p), pp = pathOf(models[p].cond);
    Object.keys(pf).forEach(function (k) {
      var c = pf[k].ref;
      if (pf[k].kind !== 'one-to-many' || !c || !APP_PKG.test(c)) return;
      if (add(c, condOf(c, p, pp), 'child')) queue.push(c);
    });
  }
  // Referenced records, read-only.
  Object.keys(models).filter(function (fq) { return models[fq].kind !== 'ref'; }).forEach(function (fq) {
    var f = fields(fq);
    Object.keys(f).forEach(function (k) {
      var r = f[k].ref;
      if (f[k].kind === 'many-to-one' && r && APP_PKG.test(r) && r !== COMPANY) add(r, condOf(r), 'ref');
    });
  });
  return models;
}

var GENERIC = { read: 'RE', write: 'RWCDE' };
// Models no area may write because they are shared configuration; client.admin writes them.
var ADMIN_GENERIC = {};
function flagsFor(fq, kind, tier, area, cond) {
  var c = CUR[fq];
  if (kind === 'ref') return 'R';
  if (c && (c.read !== undefined || c.write !== undefined)) {
    var f = tier === 'read' ? c.read : union(c.read, c.write);
    return f || null;
  }
  if (ADMIN_ONLY[fq]) return 'R';
  // In the installed, hand-checked areas, a record that belongs to no company is configuration
  // shared by every company (types, formats, templates, codes): read here, written by client.admin.
  if (area && area.curated && !cond) { ADMIN_GENERIC[fq] = true; return 'RE'; }
  return GENERIC[tier];
}

// ── The areas ────────────────────────────────────────────────────────────────────────────────────
var PAYROLL_DECLARATION_MENUS = ['nyxa-payroll-d1', 'nyxa-payroll-d6', 'nyxa-payroll-art73-para6'];
// AOS's own pay screens under HR: they show salaries, so they are payroll, not HR.
var STOCK_PAYROLL_MENUS = ['all-payroll-preparation', 'all-lunch-voucher-mgt', 'hr-conf-payroll-year',
  'hr-conf-payroll-period'];
// What plain HR must never reach, even as a child of an employee or contract: everything the
// payroll catalogue and AOS's pay screens work on, apart from the HR records themselves.
var PAYROLL_MODELS = {};
TRZ.forEach(function (s) { if (s[0].indexOf(HR) !== 0) PAYROLL_MODELS[s[0]] = true; });
var AREAS = [
  // „НСИ“ (nsi-root) е корен до „НАП“ и „НОИ“, но правата са на счетоводството (решение 27.10.2026).
  // „БНБ“ (bnb-root, СПБ-6А/6Б) — същото, след „НСИ“.
  { role: 'accounting', curated: true, title: 'Accounting',
    menus: subtree('account-root').concat(subtree('nsi-root'), subtree('bnb-root')),
    seeds: ACC.map(function (s) { return s[0]; }).concat(OCR.map(function (s) { return s[0]; })), md: true },
  { role: 'invoicing', curated: true, title: 'Invoicing', menus: subtree('invoice-root'), md: true },
  { role: 'accounting.nap', curated: true, title: 'НАП (VAT returns, SAF-T, filings)',
    menus: subtree('nap-root', PAYROLL_DECLARATION_MENUS),
    seeds: VAT.concat(NAP).map(function (s) { return s[0]; }) },
  { role: 'accounting.ai', curated: true, title: 'AI Accountant', menus: subtree('ai-accountant-root') },
  { role: 'hr', curated: true, title: 'HR (without payroll)',
    menus: subtree('hr-root', ['nyxa-payroll'].concat(STOCK_PAYROLL_MENUS)).concat(subtree('my-hr-root')),
    withoutModels: PAYROLL_MODELS },
  { role: 'hr.payroll', curated: true, title: 'Payroll',
    menus: ['hr-root', 'hr-root-emp-mgt', 'hr-root-emp-mgt-conf', 'nap-root'].concat(subtree('nyxa-payroll'),
      [].concat.apply([], PAYROLL_DECLARATION_MENUS.concat(STOCK_PAYROLL_MENUS).map(function (m) { return subtree(m); }))),
    seeds: TRZ.map(function (s) { return s[0]; }),
    // Payroll files D1/D6 and art. 73(6) (decision 17–18.10.2026): it enters the incoming number and
    // the outcome on the submission row, so its write role writes that row (no delete), in the active
    // company. Which area may act on a given submission is decided by its kind in code (NapFilingGate).
    grants: { write: { 'com.axelor.apps.napsubmissions.db.NapSubmission': 'RW' } } },
  { role: 'hr.noi', curated: true, title: 'НОИ', menus: subtree('noi-root') },
  { role: 'budget', title: 'Budget management', menus: subtree('budget-root-budget-management') },
  { role: 'cash', title: 'Cash management', menus: subtree('forecast-root') },
  { role: 'contracts', title: 'Contracts', menus: subtree('contracts-root') },
  { role: 'crm', title: 'CRM', menus: subtree('crm-root'), md: true },
  { role: 'sales', title: 'Sales', menus: subtree('sc-root-sale'), md: true },
  { role: 'purchases', title: 'Purchases', menus: subtree('sc-root-purchase'), md: true },
  { role: 'stock', title: 'Stock management', menus: subtree('stock-root') },
  { role: 'manufacturing', title: 'Manufacturing', menus: subtree('manufacturing-root') },
  { role: 'projects', title: 'Project management',
    menus: subtree('menu-project-root').concat(subtree('menu-my-project-root')) },
  { role: 'helpdesk', title: 'Helpdesk', menus: subtree('helpdesk-root') },
  { role: 'maintenance', title: 'Maintenance', menus: subtree('maintenance-root') },
  { role: 'intervention', title: 'Intervention', menus: subtree('intervention-root') },
  { role: 'assets', title: 'Assets management', menus: subtree('assets-management-root') },
  { role: 'quality', title: 'Quality', menus: subtree('menu-quality-root') },
  { role: 'marketing', title: 'Marketing', menus: subtree('marketing-root') },
  { role: 'fleet', title: 'Fleet', menus: subtree('fleet-root') },
  { role: 'gdpr', title: 'GDPR', menus: subtree('gdpr-root') }
];

// ── Rows ─────────────────────────────────────────────────────────────────────────────────────────
// Permission.condition is @Size(max = 1024) with no column length. Hibernate sizes the column from
// @Size only while bean validation is on (the application: VARCHAR(1024)); with
// javax.persistence.validation.mode=none, as in the HSQLDB test units, it is VARCHAR(255) and a longer
// condition fails its row at import. conditionParams has no @Size at all: 255 everywhere.
// RoleCatalogueFilesTest#conditionsFitTheColumn checks the committed CSV against the same limits.
var MAX_CONDITION = 255, MAX_CONDITION_PARAMS = 255;
var rows = [], rowNames = {};
function conditionOf(fq, cond) {
  if (!cond) return ['', ''];
  if (typeof cond === 'string') { checkPath(fq, cond); return fit(fq, ['self.' + cond + ' = ?', ACTIVE]); }
  cond.check.forEach(function (p) { checkPath(fq, p); });
  var np = cond.params === undefined ? 1 : cond.params;
  if ((cond.raw.match(/\?/g) || []).length !== np) throw new Error(fq + ': placeholder/param count mismatch');
  if (cond.values && cond.values.length !== np) throw new Error(fq + ': values/param count mismatch');
  return fit(fq, [cond.raw, (cond.values || Array(np).fill(cond.value || ACTIVE)).join(',')]);
}
function fit(fq, c) {
  if (c[0].length > MAX_CONDITION) {
    throw new Error(fq + ': condition is ' + c[0].length + ' characters, the column takes ' + MAX_CONDITION
      + ': ' + c[0]);
  }
  if (c[1].length > MAX_CONDITION_PARAMS) {
    throw new Error(fq + ': conditionParams is ' + c[1].length + ' characters, the column takes '
      + MAX_CONDITION_PARAMS + ': ' + c[1]);
  }
  return c;
}
function row(role, fq, cond, flags) {
  if (!flags) return;
  if (flags.indexOf('R') < 0) flags = 'R' + flags; // write implies read
  var short = fq.split('.').pop(), name = 'nx.' + role + '.' + short.charAt(0).toLowerCase() + short.slice(1);
  if (rowNames[name]) name += '.' + fq.split('.').slice(-3, -2)[0];
  if (rowNames[name]) throw new Error('duplicate permission name ' + name);
  rowNames[name] = true;
  var c = conditionOf(fq, cond);
  rows.push([name, fq, flags.indexOf('R') >= 0 ? 'x' : '', flags.indexOf('W') >= 0 ? 'x' : '',
    flags.indexOf('C') >= 0 ? 'x' : '', flags.indexOf('D') >= 0 ? 'x' : '', flags.indexOf('E') >= 0 ? 'x' : '',
    c[0], c[1], role]);
}

var ALL = { read: {}, write: {} }; // fq -> {cond, flags} merged across areas, for all.read/all.write
var roleTitles = [];
var menuGrants = {};
function grantMenus(names, roles) {
  names.forEach(function (n) {
    if (!MENUS[n]) throw new Error('unknown menu ' + n);
    (menuGrants[n] = menuGrants[n] || []).push.apply(menuGrants[n], roles);
  });
}

AREAS.forEach(function (a) {
  var models = areaModels(a.menus, a.seeds);
  if (a.withoutModels) {
    // Payroll stays out of plain HR: drop its models and what AOS's pay screens open (and own).
    var pay = areaModels(STOCK_PAYROLL_MENUS);
    Object.keys(models).forEach(function (fq) {
      if (a.withoutModels[fq] || (pay[fq] && pay[fq].kind !== 'ref')) delete models[fq];
    });
  }
  if (a.md) MD_MODELS.forEach(function (fq) { if (!models[fq]) models[fq] = { cond: condOf(fq), kind: 'main' }; });
  else MD_MODELS.forEach(function (fq) { if (!models[fq]) models[fq] = { cond: condOf(fq), kind: 'ref' }; });
  a.count = Object.keys(models).length;
  Object.keys(models).sort().forEach(function (fq) {
    var m = models[fq];
    ['read', 'write'].forEach(function (tier) {
      var flags = flagsFor(fq, m.kind, tier, a, m.cond);
      if (tier === 'write' && MD_MODELS.indexOf(fq) >= 0 && !a.md) flags = flagsFor(fq, m.kind, 'read', a, m.cond);
      if (a.grants && a.grants[tier] && a.grants[tier][fq]) flags = union(flags, a.grants[tier][fq]);
      row(a.role + '.' + tier, fq, m.cond, flags);
      if (!flags) return;
      var prev = ALL[tier][fq];
      ALL[tier][fq] = { cond: prev ? prev.cond : m.cond, flags: union(prev && prev.flags, flags) };
    });
  });
  grantMenus(a.menus, [a.role + '.read', a.role + '.write', 'all.read', 'all.write', 'client.admin']);
  roleTitles.push([a.role + '.read', 'Read ' + a.title + ' of the active company.']);
  roleTitles.push([a.role + '.write', 'Read and edit ' + a.title + ' of the active company (includes .read).']);
});

Object.keys(ALL.read).sort().forEach(function (fq) { row('all.read', fq, ALL.read[fq].cond, ALL.read[fq].flags); });
Object.keys(ALL.write).sort().forEach(function (fq) { row('all.write', fq, ALL.write[fq].cond, ALL.write[fq].flags); });
roleTitles.push(['all.read', 'Read every area and sub-area of the active company. Not configuration.']);
roleTitles.push(['all.write', 'Read and edit every area and sub-area of the active company (includes all.read). Not configuration or closing: that is client.admin.']);

// client.admin: everything all.write has, plus configuration, closing, posting to Accounted and
// company assignment. Still not the technical admins group, and still limited to the active company.
var ADMIN = {};
function adminAdd(fq, cond, flags) {
  var prev = ADMIN[fq];
  ADMIN[fq] = { cond: prev ? prev.cond : cond, flags: union(prev && prev.flags, flags) };
}
Object.keys(ALL.write).forEach(function (fq) { adminAdd(fq, ALL.write[fq].cond, ALL.write[fq].flags); });
Object.keys(CUR).forEach(function (fq) { if (CUR[fq].admin) adminAdd(fq, CUR[fq].cond, CUR[fq].admin); });
// ...and the shared configuration of the installed areas that no area role may write.
Object.keys(ADMIN_GENERIC).forEach(function (fq) { if (!(CUR[fq] && CUR[fq].admin)) adminAdd(fq, null, 'RWCDE'); });
// Every company of the tenant (all the firm's clients): to assign any of them on the Company
// assignments screen (D10) and to maintain their settings. Safe because the G12 guard keeps
// everyone's active company inside their assigned companies.
// ⚠️ An always-true CONDITION, not none: Axelor builds a list's filter from the CONDITIONAL
// permissions only (AuthSecurity.getFilter) and ORs them, so an unconditional row adds nothing and
// the Staff Baseline's "self.id in (my companies)" alone decided what client.admin saw — a company
// it had just created (not yet assigned to it) disappeared from the list. Same as the User and Role
// rows above.
var ALL_COMPANIES = { raw: 'self.id IS NOT NULL', check: [], params: 0 };
adminAdd(COMPANY, ALL_COMPANIES, 'RW');
(function () {
  var own = areaModels(CLIENT_ADMIN_MENUS);
  Object.keys(own).forEach(function (fq) {
    adminAdd(fq, own[fq].cond, own[fq].kind === 'ref' ? 'R' : flagsFor(fq, own[fq].kind, 'write'));
  });
})();
// Application Config, the models: full rights on what its menus open, except the sections in
// APP_CONFIG_HIDDEN (see the menu part below). Before client.admin's rows are written.
var APP_CONFIG_HIDDEN = [];
APP_CONFIG_HIDDEN.forEach(function (n) { if (!MENUS[n]) throw new Error('unknown menu ' + n); });
(function () {
  function below(n) {
    return (KIDS[n] || []).reduce(function (acc, k) {
      return APP_CONFIG_HIDDEN.indexOf(k) >= 0 ? acc : acc.concat([k], below(k));
    }, []);
  }
  var own = areaModels(below('admin-root'));
  Object.keys(own).forEach(function (fq) {
    adminAdd(fq, own[fq].cond, own[fq].kind === 'ref' ? 'R' : flagsFor(fq, own[fq].kind, 'write'));
  });
  // The company form's per-module configuration buttons (Accounting config, HR config, ...): each
  // opens the company's configuration record, a new one when the company has none yet, so creating
  // must be allowed too. Taken from the actions themselves, so a module added later is covered.
  if (APP_CONFIG_HIDDEN.indexOf('admin-root-user') < 0) {
    Object.keys(ACTIONS).filter(function (n) { return /^action-company-view-.*config/.test(n); })
      .forEach(function (n) {
        var fq = ACTIONS[n];
        if (fq && D[fq]) adminAdd(fq, condOf(fq), 'RWCDE');
      });
  }
  // Apps management opens the App cards through an action of the axelor-studio library
  // (menu.admin.app.management), which the index, built from this repo's sources, doesn't see.
  // Install and uninstall save the App record; Configure opens the app's settings record (an App*
  // model linked to it by `app`). The cards create and delete nothing. No admin check in code.
  if (APP_CONFIG_HIDDEN.indexOf('menu-admin-app-management') < 0) {
    if (!D[S + 'App']) throw new Error('unknown model ' + S + 'App');
    adminAdd(S + 'App', null, 'RW');
    Object.keys(D).filter(function (fq) {
      var app = fq.indexOf(S + 'App') === 0 && D[fq].fields.app;
      return app && app.ref === S + 'App';
    }).forEach(function (fq) { adminAdd(fq, null, 'RW'); });
  }
})();
Object.keys(ADMIN).sort().forEach(function (fq) { row('client.admin', fq, ADMIN[fq].cond, ADMIN[fq].flags); });
grantMenus(configMenus.concat(finalizeOnly), ['client.admin']);
// client.admin's own screens: the menus, their parents, and what they open.
CLIENT_ADMIN_MENUS.forEach(function (n) { if (!MENUS[n]) throw new Error('unknown menu ' + n); });
grantMenus(CLIENT_ADMIN_MENUS, ['client.admin']);

// Application Config (admin-root) is all client.admin's for now (2026-10-20): every menu, and full
// rights on what those menus open, until the SME reviews it section by section. That includes the
// sections that act on the whole tenant rather than one company (Technical maintenance: backup and
// restore, demo data, pseudonymization; Apps management), deliberately.
// To withdraw a section, add its menu name to APP_CONFIG_HIDDEN: it is then granted to nyxa.admin,
// which hides it from client.admin (a child menu with no roles shows to anyone who sees its
// parent), and its models are no longer granted from here. Already-imported rows still need an
// nx_retired.csv entry, since the import only adds.
// The path to the client administrator's own screens is granted by name, so it stays visible
// whatever the parents' grants: Users, Roles and the companies.
var APP_CONFIG_CLIENT_ADMIN = ['admin-root', 'admin-root-user', 'admin-root-user-user', 'nx-tenant-roles',
  'admin-root-user-company', 'admin-root-user-company-tree'];
APP_CONFIG_CLIENT_ADMIN.forEach(function (n) { if (!MENUS[n]) throw new Error('unknown menu ' + n); });
grantMenus(APP_CONFIG_CLIENT_ADMIN, ['client.admin']);
APP_CONFIG_HIDDEN.forEach(function (n) { grantMenus([n], ['nyxa.admin']); });
roleTitles.push(['client.admin', 'Client administrator: everything all.write has, plus accounting configuration, shared setup, closing periods, posting entries as Accounted and assigning companies to users. Not a technical admin.']);

// ── Output ───────────────────────────────────────────────────────────────────────────────────────
function q(v) { return v === '' ? '' : '"' + String(v).replace(/"/g, '""') + '"'; }
// Keep the header and the hand-written baseline rows; everything else is generated.
var permFile = path.join(out, 'nx_permission.csv');
var kept = fs.readFileSync(permFile, 'utf8').replace(/\r/g, '').split('\n').filter(function (l, i) {
  return l && (i === 0 || /^"nx\.baseline\./.test(l));
});
fs.writeFileSync(permFile, kept.concat(rows.map(function (r) { return r.map(q).join(';'); })).join('\n') + '\n');

var roleFile = path.join(out, 'nx_role.csv');
var keptRoles = fs.readFileSync(roleFile, 'utf8').replace(/\r/g, '').split('\n').filter(function (l, i) {
  return l && (i === 0 || /^"(Staff Baseline|nyxa.admin)"/.test(l));
});
fs.writeFileSync(roleFile, keptRoles.concat(roleTitles.map(function (r) {
  return q(r[0]) + ';' + q(r[1]);
})).join('\n') + '\n');

var menuRows = Object.keys(menuGrants).sort().map(function (n) {
  return [n, menuGrants[n].filter(function (r, i, a) { return a.indexOf(r) === i; }).join('|')];
});
fs.writeFileSync(path.join(out, 'nx_menu.csv'),
  ['"menu";"roles"'].concat(menuRows.map(function (r) { return r.map(q).join(';'); })).join('\n') + '\n');

console.log('roles: ' + roleTitles.length + ', permission rows: ' + rows.length + ', menu rows: ' + menuRows.length);
AREAS.forEach(function (a) { console.log('  ' + a.role + ': ' + a.count + ' models, ' + a.menus.length + ' menus'); });

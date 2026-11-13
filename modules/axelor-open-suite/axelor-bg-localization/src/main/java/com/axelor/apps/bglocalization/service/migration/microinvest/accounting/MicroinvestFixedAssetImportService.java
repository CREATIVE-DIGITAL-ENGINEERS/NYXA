package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Imports Microinvest assets into the stock Axelor FixedAsset and FixedAssetLine models. */
final class MicroinvestFixedAssetImportService {
  private MicroinvestFixedAssetImportService() {}

  static String sourceCurrency(JsonNode asset) {
    return asset.path("isEuro").asBoolean() ? "EUR" : "BGN";
  }

  static boolean applicable(JsonNode asset, ExportRoot root) {
    return sourceCurrency(asset).equals(root.financialYear.baseCurrency);
  }

  static void preflight(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    if (root.counts.fixedAssets != root.fixedAssets.size()) {
      report.blocked("активи", "Броят fixedAssets не съответства на съдържанието.");
    }
    int applicable = 0;
    for (JsonNode source : root.fixedAssets) {
      if (!source.path("sourceId").isIntegralNumber() || !source.path("isEuro").isBoolean()) {
        report.blocked("актив", "Липсва sourceId/isEuro: валутата не може да се отгатва.");
        continue;
      }
      if (!applicable(source, root)) {
        continue;
      }
      applicable++;
      String label = "актив " + source.path("sourceId").asInt();
      if (text(source, "name") == null) {
        report.blocked(label, "Липсва име; стандартният FixedAsset изисква име.");
      }
      validatePlan(source.path("accountingDepreciation"), "счетоводен", source, report);
      validatePlan(source.path("taxDepreciation"), "данъчен", source, report);

      FixedAsset existing = find(company, root, source.path("sourceId").asInt());
      if (existing != null && !hash(source).equals(existing.getBgMicroinvestSourceHash())) {
        report.blocked(label, "Изходният актив/план е променен след импорта.");
        continue;
      }
      if (existing == null) {
        List<FixedAssetCategory> categories = categories(company, text(source, "categoryName"));
        if (categories.size() != 1) {
          String approved =
              APPROVED_CATEGORIES.get(normalizeCategory(text(source, "categoryName")));
          report.blocked(
              label,
              "Microinvest категория '"
                  + text(source, "categoryName")
                  + "' се мапва към "
                  + (approved == null
                      ? "стандартна FixedAssetCategory чрез bgMicroinvestSourceCategory"
                      : "одобрената категория '" + approved + "'")
                  + ", която трябва да съществува точно веднъж за компанията; намерени: "
                  + categories.size()
                  + " -- категории не се създават автоматично.");
          continue;
        }
        FixedAssetCategory category = categories.get(0);
        if (category.getJournal() == null) {
          report.blocked(label, "Стандартната категория няма Journal.");
        }
        if (hasSupplierReference(source) && supplier(root, source) == null) {
          report.blocked(
              label,
              "Source доставчикът на актива не е импортиран като партньор; доставчик не се измисля.");
        }
        if (!hasSupplierReference(source) && company.getPartner() == null) {
          report.blocked(
              label,
              "Активът няма source доставчик, затова е нужен собственик: дружеството трябва да има"
                  + " собствен партньор (Company.partner); партньор не се измисля.");
        }
        Account debitAccount =
            exactAccount(company, text(source, "debitAccountNumber"), "дебитна", label, report);
        Account creditAccount =
            exactAccount(company, text(source, "creditAccountNumber"), "кредитна", label, report);
        exactAccount(company, text(source, "inventoryAccountNumber"), "инвентарна", label, report);
        // Microinvest posts depreciation as Dt 241 / Ct 603; in Nyxa 241 is the accumulated
        // depreciation account and 603 the charge account, so source debit == Depreciation account
        // and source credit == Charge account.
        if (debitAccount != null
            && (category.getDepreciationAccount() == null
                || !debitAccount.getId().equals(category.getDepreciationAccount().getId()))) {
          report.blocked(
              label,
              "Стандартната категория трябва да сочи source дебитната сметка "
                  + debitAccount.getCode()
                  + " като Depreciation account.");
        }
        if (creditAccount != null
            && (category.getChargeAccount() == null
                || !creditAccount.getId().equals(category.getChargeAccount().getId()))) {
          report.blocked(
              label,
              "Стандартната категория трябва да сочи source кредитната сметка "
                  + creditAccount.getCode()
                  + " като Charge account.");
        }
      }
    }
    report.note(
        "Активи: "
            + applicable
            + " записа в "
            + root.financialYear.baseCurrency
            + " ще се пазят като стандартни FixedAsset/FixedAssetLine; няма отделен Microinvest"
            + " регистър.");
  }

  private static void validatePlan(
      JsonNode plan, String planLabel, JsonNode asset, MicroinvestAccountingImportReport report) {
    String assetLabel = "актив " + asset.path("sourceId");
    if (!plan.isObject() || !plan.path("sourceId").isIntegralNumber()) {
      report.blocked(assetLabel, "Липсва sourceId на " + planLabel + " амортизационен план.");
      return;
    }
    validatePlanLines(plan.path("schedules"), "schedules", "month", planLabel, assetLabel, report);
    validatePlanLines(plan.path("yearPlans"), "yearPlans", "year", planLabel, assetLabel, report);
  }

  private static void validatePlanLines(
      JsonNode lines,
      String collection,
      String dateField,
      String planLabel,
      String assetLabel,
      MicroinvestAccountingImportReport report) {
    if (!lines.isArray()) {
      report.blocked(assetLabel, "Липсва " + collection + " в " + planLabel + " план.");
      return;
    }
    for (JsonNode line : lines) {
      if (!line.path("sourceId").isIntegralNumber()) {
        report.blocked(
            assetLabel, "Липсва sourceId на ред в " + planLabel + " план (" + collection + ").");
      }
      try {
        if (text(line, dateField) == null) {
          throw new DateTimeParseException("missing", "", 0);
        }
        LocalDate.parse(text(line, dateField));
      } catch (DateTimeParseException e) {
        report.blocked(
            assetLabel,
            "Липсва/невалидна дата "
                + dateField
                + " в "
                + planLabel
                + " план ("
                + collection
                + ").");
      }
    }
  }

  static void importAssets(
      ExportRoot root, Company company, MicroinvestAccountingImportReport report) {
    Currency bookCurrency =
        Query.of(Currency.class)
            .filter("self.codeISO = :code")
            .bind("code", root.financialYear.baseCurrency)
            .fetchOne();
    for (JsonNode source : root.fixedAssets) {
      if (!applicable(source, root)) {
        continue;
      }
      int sourceId = source.path("sourceId").asInt();
      FixedAsset asset = find(company, root, sourceId);
      if (asset == null) {
        FixedAssetCategory category = categories(company, text(source, "categoryName")).get(0);
        asset = new FixedAsset();
        asset.setCompany(company);
        asset.setName(text(source, "name"));
        asset.setFixedAssetCategory(category);
        asset.setJournal(category.getJournal());
        // A real per-asset source supplier, else the company's own partner as owner; never the
        // category-level bgMicroinvestSupplier and never an invented partner.
        asset.setPartner(resolvePartner(supplier(root, source), company, sourceId));
        asset.setPurchaseAccount(resolveAccount(company, text(source, "inventoryAccountNumber")));
        asset.setStatusSelect(FixedAssetRepository.STATUS_VALIDATED);
        asset.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_IMPORT);
        asset.setImportId(assetImportId(root, sourceId));
        asset.setImportOrigin(root.source.database);
        asset.setBgBookCurrency(bookCurrency);
        asset.setBgMicroinvestInventoryNumber(text(source, "inventoryNumber"));
        asset.setBgMicroinvestSourceCurrencyCode(sourceCurrency(source));
        asset.setBgMicroinvestSourceHash(hash(source));
        asset.setBgMicroinvestLocation(text(source, "locationName"));
        asset.setBgMicroinvestTerminationStatus(text(source, "terminationStatus"));
        asset.setBgMicroinvestTerminationAccountingId(integer(source, "terminationAccountingId"));
        asset.setBgMicroinvestDebitAccountNumber(text(source, "debitAccountNumber"));
        asset.setBgMicroinvestCreditAccountNumber(text(source, "creditAccountNumber"));
        asset.setBgMicroinvestInventoryAccountNumber(text(source, "inventoryAccountNumber"));
        asset.setBgMicroinvestSourceJson(source.toString());
        JsonNode accounting = source.path("accountingDepreciation");
        JsonNode tax = source.path("taxDepreciation");
        asset.setAcquisitionDate(date(accounting, "acquisitionDate"));
        asset.setFirstServiceDate(date(accounting, "startDate"));
        asset.setFirstDepreciationDate(firstScheduleDate(accounting));
        asset.setFiscalFirstDepreciationDate(firstScheduleDate(tax));
        asset.setGrossValue(zeroIfNull(money(accounting, "depreciationAmount")));
        asset.setResidualValue(zeroIfNull(money(accounting, "nonDepreciableAmount")));
        asset.setAccountingValue(asset.getGrossValue());
        asset.setCorrectedAccountingValue(asset.getGrossValue());
        asset.setSerialNumber(text(accounting, "serialNumber"));
        asset.setComments(text(accounting, "note"));
        copyCategoryConfiguration(category, asset);
        asset = Beans.get(FixedAssetRepository.class).save(asset);
        report.created("стандартен актив " + sourceId + " " + sourceCurrency(source));
      } else {
        report.reused("стандартен актив " + sourceId);
      }
      importPlan(source.path("accountingDepreciation"), false, asset, root, bookCurrency, report);
      importPlan(source.path("taxDepreciation"), true, asset, root, bookCurrency, report);
    }
  }

  private static void copyCategoryConfiguration(FixedAssetCategory category, FixedAsset asset) {
    asset.setDepreciationPlanSelect("1,2");
    asset.setComputationMethodSelect(category.getComputationMethodSelect());
    asset.setDegressiveCoef(category.getDegressiveCoef());
    asset.setPeriodicityInMonth(category.getPeriodicityInMonth());
    asset.setPeriodicityTypeSelect(category.getPeriodicityTypeSelect());
    asset.setNumberOfDepreciation(category.getNumberOfDepreciation());
    asset.setDurationInMonth(category.getDurationInMonth());
    asset.setFiscalComputationMethodSelect(category.getComputationMethodSelect());
    asset.setFiscalDegressiveCoef(category.getDegressiveCoef());
    asset.setFiscalPeriodicityInMonth(category.getPeriodicityInMonth());
    asset.setFiscalPeriodicityTypeSelect(category.getPeriodicityTypeSelect());
    asset.setFiscalNumberOfDepreciation(category.getNumberOfDepreciation());
    asset.setFiscalDurationInMonth(category.getDurationInMonth());
    asset.setAnalyticDistributionTemplate(category.getAnalyticDistributionTemplate());
  }

  private static void importPlan(
      JsonNode plan,
      boolean fiscal,
      FixedAsset asset,
      ExportRoot root,
      Currency bookCurrency,
      MicroinvestAccountingImportReport report) {
    BigDecimal cumulative = BigDecimal.ZERO;
    for (JsonNode source : plan.path("schedules")) {
      BigDecimal amount = zeroIfNull(money(source, "amount"));
      cumulative = cumulative.add(amount);
      LocalDate planDate = date(source, "month");
      if (planDate == null
          || planDate.getYear() != root.financialYear.year
          || !BgAccountingCurrencyPolicy.expectedCurrency(planDate)
              .equals(asset.getBgMicroinvestSourceCurrencyCode())) {
        continue;
      }
      int sourceId = source.path("sourceId").asInt();
      String importId =
          MicroinvestAccountingSourceKey.of(
              root.company.bulstat,
              root.source.database,
              MicroinvestAccountingSourceKey.TYPE_ASSET_LINE,
              (fiscal ? "tax-" : "accounting-") + sourceId);
      if (Query.of(FixedAssetLine.class).filter("self.importId = :id").bind("id", importId).count()
          > 0) {
        continue;
      }
      FixedAssetLine line = new FixedAssetLine();
      if (fiscal) {
        line.setFiscalFixedAsset(asset);
        line.setTypeSelect(FixedAssetLineRepository.TYPE_SELECT_FISCAL);
        asset.addFiscalFixedAssetLineListItem(line);
      } else {
        line.setFixedAsset(asset);
        line.setTypeSelect(FixedAssetLineRepository.TYPE_SELECT_ECONOMIC);
        asset.addFixedAssetLineListItem(line);
      }
      line.setDepreciationDate(planDate);
      line.setDepreciation(amount);
      line.setCumulativeDepreciation(cumulative);
      line.setGrossValue(asset.getGrossValue());
      line.setDepreciationBase(asset.getGrossValue().subtract(asset.getResidualValue()));
      line.setResidualValue(asset.getGrossValue().subtract(cumulative));
      line.setAccountingValue(line.getResidualValue());
      line.setCorrectedAccountingValue(line.getResidualValue());
      line.setStatusSelect(
          source.path("isProcessed").asBoolean()
              ? FixedAssetLineRepository.STATUS_REALIZED
              : FixedAssetLineRepository.STATUS_PLANNED);
      line.setImportId(importId);
      line.setImportOrigin(root.source.database);
      line.setBgMicroinvestSourceId(sourceId);
      line.setBgMicroinvestSourcePlanId(integer(plan, "sourceId"));
      line.setBgSourceAccountingId(integer(source, "accountingId"));
      line.setBgBookCurrency(bookCurrency);
      JsonNode yearPlan = yearPlan(plan, planDate.getYear());
      if (yearPlan != null) {
        line.setBgMicroinvestYearPlanId(integer(yearPlan, "sourceId"));
        line.setBgMicroinvestPercentage(money(yearPlan, "percentage"));
        line.setBgMicroinvestYearPlanAmount(money(yearPlan, "amount"));
        line.setBgMicroinvestRevaluationReserve(money(yearPlan, "revaluationReserve"));
      }
      Integer accountingId = integer(source, "accountingId");
      if (accountingId != null) {
        Move move =
            Query.of(Move.class)
                .filter("self.company = :company AND self.importId = :id")
                .bind("company", asset.getCompany())
                .bind(
                    "id",
                    MicroinvestAccountingSourceKey.of(
                        root.company.bulstat,
                        root.source.database,
                        MicroinvestAccountingSourceKey.TYPE_ENTRY,
                        accountingId))
                .fetchOne();
        line.setDepreciationAccountMove(move);
      }
      JPA.em().persist(line);
      report.created(
          (fiscal ? "данъчен" : "счетоводен")
              + " амортизационен ред "
              + sourceId
              + " на актив "
              + asset.getName());
    }
  }

  /** Accountant-approved Microinvest category → Nyxa FixedAssetCategory name. */
  static final Map<String, String> APPROVED_CATEGORIES =
      Map.of(
          "Категория II - машини", "Машини и оборудване",
          "Категория IV - компютри", "Компютърна техника",
          "Категория V - автомобили", "Автомобили",
          "Категория VI - ДДМА и ДДНА", "Автомобили",
          "Категория VII - други", "Офис обзавеждане");

  static String normalizeCategory(String name) {
    return name == null ? null : name.replace('–', '-').replace('—', '-').trim();
  }

  private static boolean hasSupplierReference(JsonNode source) {
    return source.path("supplierSourceId").isIntegralNumber();
  }

  /** The asset's own source supplier as an already-imported partner; null when it has none. */
  static Partner supplier(ExportRoot root, JsonNode source) {
    if (!hasSupplierReference(source)) {
      return null;
    }
    return Query.of(Partner.class)
        .filter("self.importId = :key")
        .bind(
            "key",
            MicroinvestAccountingSourceKey.of(
                root.company.bulstat,
                root.source.database,
                MicroinvestAccountingSourceKey.TYPE_PARTNER,
                source.path("supplierSourceId").asInt()))
        .fetchOne();
  }

  /**
   * The partner the stock FixedAsset requires: the asset's own source supplier when the source has
   * one, otherwise the target company's own Partner as the owner fallback. Microinvest does not
   * emit a supplier for the historical assets, and a fake supplier is never created.
   */
  static Partner resolvePartner(Partner sourceSupplier, Company company, int sourceId) {
    if (sourceSupplier != null) {
      return sourceSupplier;
    }
    Partner owner = company == null ? null : company.getPartner();
    if (owner == null) {
      throw new MicroinvestAccountingAbortException(
          "актив "
              + sourceId
              + ": източникът няма доставчик, затова е нужен собственик, но дружеството няма"
              + " собствен партньор (Company.partner); партньор не се измисля.");
    }
    return owner;
  }

  private static List<FixedAssetCategory> categories(Company company, String sourceCategory) {
    if (sourceCategory == null) {
      return List.of();
    }
    String approved = APPROVED_CATEGORIES.get(normalizeCategory(sourceCategory));
    if (approved != null) {
      return Query.of(FixedAssetCategory.class)
          .filter("self.company = :company AND self.name = :name")
          .bind("company", company)
          .bind("name", approved)
          .fetch(2);
    }
    return Query.of(FixedAssetCategory.class)
        .filter("self.company = :company AND self.bgMicroinvestSourceCategory = :source")
        .bind("company", company)
        .bind("source", sourceCategory)
        .fetch(2);
  }

  /** Exact-number lookup of the approved target account (see {@link MicroinvestAccountMapping}). */
  private static Account resolveAccount(Company company, String number) {
    if (number == null) {
      return null;
    }
    List<Account> matches =
        Query.of(Account.class)
            .filter("self.company = :company AND self.code = :code")
            .bind("company", company)
            .bind("code", MicroinvestAccountMapping.targetCode(number, null, null))
            .fetch(2);
    return matches.size() == 1 ? matches.get(0) : null;
  }

  private static Account exactAccount(
      Company company,
      String number,
      String kind,
      String assetLabel,
      MicroinvestAccountingImportReport report) {
    if (number == null) {
      return null;
    }
    Account account = resolveAccount(company, number);
    if (account == null) {
      report.blocked(
          assetLabel,
          kind
              + " сметка "
              + number
              + " няма точно съответствие в сметкоплана; мапването остава за колегата.");
    }
    return account;
  }

  private static FixedAsset find(Company company, ExportRoot root, int id) {
    return Query.of(FixedAsset.class)
        .filter("self.company = :company AND self.importId = :id")
        .bind("company", company)
        .bind("id", assetImportId(root, id))
        .fetchOne();
  }

  private static String assetImportId(ExportRoot root, int id) {
    return MicroinvestAccountingSourceKey.of(
        root.company.bulstat, root.source.database, MicroinvestAccountingSourceKey.TYPE_ASSET, id);
  }

  static String hash(JsonNode source) {
    return MicroinvestAccountingExportFileParser.sha256Utf8(source.toString());
  }

  private static String text(JsonNode source, String name) {
    return source.path(name).isMissingNode() || source.path(name).isNull()
        ? null
        : source.path(name).asText();
  }

  private static Integer integer(JsonNode source, String name) {
    return source.path(name).isIntegralNumber() ? source.path(name).asInt() : null;
  }

  private static LocalDate date(JsonNode source, String name) {
    String value = text(source, name);
    return value == null ? null : LocalDate.parse(value);
  }

  private static LocalDate firstScheduleDate(JsonNode plan) {
    LocalDate first = null;
    for (JsonNode line : plan.path("schedules")) {
      LocalDate value = date(line, "month");
      if (value != null && (first == null || value.isBefore(first))) {
        first = value;
      }
    }
    return first;
  }

  private static JsonNode yearPlan(JsonNode plan, int year) {
    for (JsonNode line : plan.path("yearPlans")) {
      LocalDate value = date(line, "year");
      if (value != null && value.getYear() == year) {
        return line;
      }
    }
    return null;
  }

  private static BigDecimal money(JsonNode source, String name) {
    String value = text(source, name);
    return value == null || value.isBlank() ? null : new BigDecimal(value);
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}

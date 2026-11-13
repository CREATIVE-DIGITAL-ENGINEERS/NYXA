package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetDisposalService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineMoveService;
import com.axelor.apps.account.service.fixedasset.FixedAssetValidateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ДЪЛГОТРАЙНИТЕ АКТИВИ — {@code assets.csv}: {@code
 * code;kind;name;partner;category;account;date;yearShift;gross;depreciated;pastMonths;doc;accMonths;
 * reason}} ({@code partner} — доставчикът на заварения актив).
 *
 * <ul>
 *   <li>{@code IMPORT} — заварен актив (във встъпителната статия са брутната стойност и натрупаната
 *       амортизация): внесен с вече начислената амортизация ({@code originSelect = 99}, {@code
 *       importAlreadyDepreciatedAmount}), без статия за миналото.
 *   <li>{@code INVOICE} — активът, създаден при осчетоводяване на фактурата {@code doc} (редът ѝ е
 *       с „ДМА“ и категория). {@code accMonths} — друг счетоводен срок от данъчния (данъчната норма
 *       е тази на категорията по чл. 55 ЗКПО, както я носи Nyxa); после „Валидирай“.
 *   <li>{@code DISPOSE} — продажба на актива {@code code} на датата (приходът е отделна фактура в
 *       {@code invoices.csv}, както изисква Nyxa — {@code BgFixedAssetDisposalServiceImpl}).
 * </ul>
 *
 * <p>Амортизацията се осчетоводява в края на всеки месец 01–10 — всички неосчетоводени редове на
 * счетоводния план с дата до края на месеца ({@link FixedAssetLineMoveService#realize}); данъчният
 * план се отчита заедно с тях, без статия. AOS ражда статиите за амортизация и за извеждане като
 * чернови — тук те се осчетоводяват ({@link #accountAssetMoves}), както ги осчетоводява
 * счетоводителят в края на месеца; иначе ОПР е без амортизации, а изведеният актив остава в
 * баланса.
 */
final class BgDemoAssetStep {

  private BgDemoAssetStep() {}

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (BgDemoCsv.Row r : ctx.rows("assets")) {
      switch (r.get("kind")) {
        case "IMPORT":
          out.add(
              new BgDemoEvent(
                  ctx.dates().first(),
                  BgDemoEvent.RANK_RATES + 2,
                  "заварен актив " + r.get("code"),
                  () -> importAsset(ctx, r)));
          break;
        case "INVOICE":
          // След фактурата (същия ден, по-късен ранг) — активът ѝ вече е създаден като чернова.
          out.add(
              new BgDemoEvent(
                  ctx.dates().of(r.get("date")),
                  BgDemoEvent.RANK_SALE + 1,
                  "актив по фактура " + r.get("doc"),
                  () -> fromInvoice(ctx, r)));
          break;
        case "DISPOSE":
          out.add(
              new BgDemoEvent(
                  ctx.dates().of(r.get("date")),
                  BgDemoEvent.RANK_ASSET,
                  "продажба на актив " + r.get("code"),
                  () -> dispose(ctx, r)));
          break;
        default:
          throw new IllegalArgumentException(r.where() + ": непознат вид " + r.get("kind"));
      }
    }
    if (!out.isEmpty()) {
      for (int m = 1; m <= BgDemoDates.LAST_MONTH.getValue(); m++) {
        LocalDate end = ctx.dates().endOfMonth(m);
        out.add(
            new BgDemoEvent(
                end, BgDemoEvent.RANK_MONTH_END, "амортизация " + m, () -> depreciate(ctx, end)));
      }
    }
    return out;
  }

  static FixedAssetCategory category(Company company, String name) {
    FixedAssetCategory c =
        JPA.all(FixedAssetCategory.class)
            .filter("self.company = ?1 AND self.name = ?2", company, name)
            .fetchOne();
    if (c == null) {
      throw new IllegalArgumentException("Няма категория ДМА „" + name + "“ в BG_COA");
    }
    return c;
  }

  private static void importAsset(BgDemoContext ctx, BgDemoCsv.Row r) throws Exception {
    Long id =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              FixedAssetCategory cat = category(company, r.get("category"));
              LocalDate acquired = ctx.dates().of(r.get("date")).plusYears(r.integer("yearShift"));
              int past = r.integer("pastMonths");
              FixedAsset a = new FixedAsset();
              a.setCompany(company);
              a.setName(r.get("name"));
              a.setPartner(ctx.find(Partner.class, "partner", r.get("partner")));
              a.setFixedAssetCategory(cat);
              a.setJournal(cat.getJournal());
              a.setPurchaseAccount(BgDemoPartnerStep.account(company, r.get("account")));
              a.setGrossValue(r.decimal("gross"));
              a.setQty(BigDecimal.ONE);
              a.setAcquisitionDate(acquired);
              a.setFirstServiceDate(acquired);
              a.setFirstDepreciationDate(acquired);
              a.setDepreciationPlanSelect(
                  FixedAssetRepository.DEPRECIATION_PLAN_FISCAL
                      + ","
                      + FixedAssetRepository.DEPRECIATION_PLAN_ECONOMIC);
              a.setIsEqualToFiscalDepreciation(true);
              a.setOriginSelect(FixedAssetRepository.ORIGINAL_SELECT_IMPORT);
              a.setImportId(company.getCode() + "-DEMO-" + r.get("code"));
              a.setImportDepreciationDate(ctx.dates().first().minusDays(1));
              a.setImportAlreadyDepreciatedAmount(r.decimal("depreciated"));
              a.setImportNbrOfPastDepreciations(past);
              a.setImportFiscalAlreadyDepreciatedAmount(r.decimal("depreciated"));
              a.setImportFiscalNbrOfPastDepreciations(past);
              a.setCurrency(company.getCurrency());
              a.setCompanyCurrency(company.getCurrency());
              a.setStatusSelect(FixedAssetRepository.STATUS_DRAFT);
              return Beans.get(FixedAssetRepository.class).save(a).getId();
            });
    BgDemoTx.run(
        () -> Beans.get(FixedAssetValidateService.class).validate(JPA.find(FixedAsset.class, id)));
    ctx.put("asset", r.get("code"), JPA.find(FixedAsset.class, id));
  }

  private static void fromInvoice(BgDemoContext ctx, BgDemoCsv.Row r) throws Exception {
    Invoice invoice = ctx.find(Invoice.class, "invoice", r.get("doc"));
    List<Long> ids =
        BgDemoTx.call(
            () -> {
              List<Long> out = new ArrayList<>();
              for (FixedAsset a :
                  JPA.all(FixedAsset.class)
                      .filter(
                          "self.invoiceLine.invoice.id = ?1 AND self.statusSelect = ?2",
                          invoice.getId(),
                          FixedAssetRepository.STATUS_DRAFT)
                      .fetch()) {
                if (r.has("accMonths")) {
                  a.setIsEqualToFiscalDepreciation(false);
                  a.setNumberOfDepreciation(r.integer("accMonths"));
                  a.setDurationInMonth(r.integer("accMonths"));
                  a.setPeriodicityInMonth(1);
                  Beans.get(FixedAssetRepository.class).save(a);
                }
                out.add(a.getId());
              }
              return out;
            });
    if (ids.isEmpty()) {
      throw new IllegalStateException("Фактурата " + r.get("doc") + " не е създала актив");
    }
    int n = 0;
    for (Long id : ids) {
      BgDemoTx.run(
          () ->
              Beans.get(FixedAssetValidateService.class).validate(JPA.find(FixedAsset.class, id)));
      ctx.put("asset", r.get("code") + (n++ == 0 ? "" : "-" + n), JPA.find(FixedAsset.class, id));
    }
  }

  private static void dispose(BgDemoContext ctx, BgDemoCsv.Row r) throws Exception {
    LocalDate date = ctx.dates().of(r.get("date"));
    depreciate(ctx, date.withDayOfMonth(1).minusDays(1));
    BgDemoTx.run(
        () -> {
          FixedAsset a = ctx.find(FixedAsset.class, "asset", r.get("code"));
          AssetDisposalReason reason =
              JPA.all(AssetDisposalReason.class)
                  .filter("self.code = ?1", r.get("reason"))
                  .fetchOne();
          Beans.get(FixedAssetDisposalService.class)
              .fullDisposal(
                  a,
                  date,
                  FixedAssetRepository.DISPOSABLE_QTY_SELECT_TOTAL,
                  BigDecimal.ONE,
                  false,
                  null,
                  FixedAssetRepository.DISPOSABLE_TYPE_SELECT_CESSION,
                  r.decimal("gross"),
                  reason,
                  "Демо: продажба");
        });
    accountAssetMoves(ctx);
  }

  /** Осчетоводява черновите статии на активите (амортизация, извеждане) на дружеството. */
  static void accountAssetMoves(BgDemoContext ctx) throws Exception {
    List<Long> drafts =
        BgDemoTx.call(
            () -> {
              List<Long> out = new ArrayList<>();
              for (Move m :
                  JPA.all(Move.class)
                      .filter(
                          "self.company.id = ?1 AND self.statusSelect = ?2"
                              + " AND self.functionalOriginSelect = ?3",
                          ctx.companyId(),
                          MoveRepository.STATUS_NEW,
                          MoveRepository.FUNCTIONAL_ORIGIN_FIXED_ASSET)
                      .order("date")
                      .order("id")
                      .fetch()) {
                out.add(m.getId());
              }
              return out;
            });
    for (Long id : drafts) {
      BgDemoTx.run(() -> Beans.get(MoveValidateService.class).accounting(JPA.find(Move.class, id)));
    }
  }

  /** Всички неосчетоводени редове на счетоводния план до датата — по ред. */
  static void depreciate(BgDemoContext ctx, LocalDate upTo) throws Exception {
    List<Long> lines =
        BgDemoTx.call(
            () -> {
              List<Long> out = new ArrayList<>();
              for (FixedAssetLine l :
                  JPA.all(FixedAssetLine.class)
                      .filter(
                          "self.fixedAsset.company.id = ?1 AND self.fixedAsset.statusSelect = ?2"
                              + " AND self.typeSelect = ?3 AND self.statusSelect = ?4"
                              + " AND self.depreciationDate <= ?5",
                          ctx.companyId(),
                          FixedAssetRepository.STATUS_VALIDATED,
                          FixedAssetLineRepository.TYPE_SELECT_ECONOMIC,
                          FixedAssetLineRepository.STATUS_PLANNED,
                          upTo)
                      .order("fixedAsset.id")
                      .order("depreciationDate")
                      .fetch()) {
                out.add(l.getId());
              }
              return out;
            });
    for (Long id : lines) {
      BgDemoTx.run(
          () ->
              Beans.get(FixedAssetLineMoveService.class)
                  .realize(JPA.find(FixedAssetLine.class, id), false, true, false));
    }
    accountAssetMoves(ctx);
  }
}

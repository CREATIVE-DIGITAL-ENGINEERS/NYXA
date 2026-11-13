package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesReader;
import com.axelor.apps.bglocalization.service.saft.BgSaftMoveScope;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „ПЕРИОД НА ОТРАЗЯВАНЕ ПО ЗДДС“ — ИСТИНСКИТЕ ЗАЯВКИ над записани статии и фактури (HSQLDB).
 *
 * <p>Пускат се точно низовете на кода ({@link BgVatDocumentCollector#POSTED_MOVES} и съседите им,
 * заявките на {@link BgVatAdvanceService}, двете заявки на SAF-T) — с {@code SELECT self.id},
 * защото пълният SELECT върху {@code Move} и {@code Invoice} не върви на HSQLDB (формулите {@code
 * ocrUploadIds}, {@code paymentDelay}). Затова тук се проверява подборът, не обектите.
 *
 * <p>Свидетелят на неявното съединяване: статия БЕЗ фактура в периода остава (протокол, ръчна).
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgVatReportingPeriodIntegrationTest extends JpaSupport {

  private static final Pattern PARAMETER = Pattern.compile(":(\\w+)");

  private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
  private static final LocalDate MAY_31 = LocalDate.of(2026, 5, 31);
  private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
  private static final LocalDate JUNE_30 = LocalDate.of(2026, 6, 30);
  private static final LocalDate JULY_1 = LocalDate.of(2026, 7, 1);
  private static final LocalDate JULY_31 = LocalDate.of(2026, 7, 31);

  private static int seq;

  private Company company;
  private Company other;

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static Company company(String tag) {
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("R" + tag);
    currency = JPA.save(currency);
    Company company = new Company();
    company.setName("Отразяване " + tag);
    company.setCode("RP-" + tag);
    company.setCurrency(currency);
    return JPA.save(company);
  }

  @BeforeEach
  void fixture() {
    String tag = String.valueOf(++seq) + System.nanoTime() % 100000;
    company = tx(() -> company(tag));
    other = tx(() -> company(tag + "X"));
  }

  private Invoice invoice(
      int operation, int subType, int status, LocalDate date, String reportingPeriod) {
    return tx(
        () -> {
          Invoice invoice = new Invoice();
          invoice.setCompany(JPA.find(Company.class, company.getId()));
          invoice.setOperationTypeSelect(operation);
          invoice.setOperationSubTypeSelect(subType);
          invoice.setStatusSelect(status);
          invoice.setInvoiceId("RP-" + (++seq));
          invoice.setInvoiceDate(date);
          invoice.setBgVatReportingPeriod(reportingPeriod);
          return JPA.save(invoice);
        });
  }

  private Invoice sale(LocalDate date, String reportingPeriod) {
    return invoice(
        InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
        InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT,
        InvoiceRepository.STATUS_VENTILATED,
        date,
        reportingPeriod);
  }

  private Move move(Company owner, Invoice invoice, LocalDate date, int status) {
    return tx(
        () -> {
          Move move = new Move();
          move.setCompany(JPA.find(Company.class, owner.getId()));
          move.setDate(date);
          move.setStatusSelect(status);
          move.setTechnicalOriginSelect(1);
          if (invoice != null) {
            Invoice attached = JPA.find(Invoice.class, invoice.getId());
            move.setInvoice(attached);
            move = JPA.save(move);
            if (attached.getOperationSubTypeSelect()
                != InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE) {
              attached.setMove(move);
              JPA.save(attached);
            }
            return move;
          }
          return JPA.save(move);
        });
  }

  private Move posted(Invoice invoice, LocalDate date) {
    return move(company, invoice, date, MoveRepository.STATUS_ACCOUNTED);
  }

  /** id-тата по заявката; параметрите — само онези, които тя носи. */
  private static Set<Long> ids(String jpql, Map<String, Object> parameters) {
    return tx(
        () -> {
          Query query = JPA.em().createQuery(jpql);
          Matcher m = PARAMETER.matcher(jpql);
          Set<String> names = new HashSet<>();
          while (m.find()) {
            names.add(m.group(1));
          }
          for (String name : names) {
            query.setParameter(name, parameters.get(name));
          }
          @SuppressWarnings("unchecked")
          List<Object> rows = query.getResultList();
          return rows.stream()
              .map(r -> r instanceof Object[] ? ((Object[]) r)[0] : r)
              .map(r -> ((Number) r).longValue())
              .collect(Collectors.toSet());
        });
  }

  private Map<String, Object> collector(LocalDate from, LocalDate to) {
    Map<String, Object> p = new HashMap<>(BgVatPeriodScope.parameters(from, to));
    p.put("companyId", company.getId());
    p.put("company", JPA.find(Company.class, company.getId()));
    p.put("statuses", List.of(MoveRepository.STATUS_ACCOUNTED));
    p.put("canceled", MoveRepository.STATUS_CANCELED);
    return p;
  }

  private Set<Long> moves(String filter, LocalDate from, LocalDate to) {
    return ids("SELECT self.id FROM Move self WHERE " + filter, collector(from, to));
  }

  private Set<Long> invoices(String filter, Map<String, Object> parameters) {
    return ids("SELECT self.id FROM Invoice self WHERE " + filter, parameters);
  }

  private static Set<Long> of(Move... moves) {
    Set<Long> ids = new HashSet<>();
    for (Move move : moves) {
      ids.add(move.getId());
    }
    return ids;
  }

  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Колекторът: пропусната от май → юни; статия без фактура остава; отложена → юли")
  void колекторът() {
    Move mayPlain = posted(sale(LocalDate.of(2026, 5, 28), null), LocalDate.of(2026, 5, 28));
    Move mayToJune = posted(sale(LocalDate.of(2026, 5, 29), "202606"), LocalDate.of(2026, 5, 29));
    Move noInvoice = posted(null, LocalDate.of(2026, 6, 10));
    Move juneSame = posted(sale(LocalDate.of(2026, 6, 15), "202606"), LocalDate.of(2026, 6, 15));
    Move juneToJuly = posted(sale(LocalDate.of(2026, 6, 20), "202607"), LocalDate.of(2026, 6, 20));
    move(other, null, LocalDate.of(2026, 6, 11), MoveRepository.STATUS_ACCOUNTED);
    move(company, null, LocalDate.of(2026, 6, 12), MoveRepository.STATUS_DAYBOOK);

    assertEquals(of(mayPlain), moves(BgVatDocumentCollector.POSTED_MOVES, MAY_1, MAY_31));
    assertEquals(
        of(mayToJune, noInvoice, juneSame),
        moves(BgVatDocumentCollector.POSTED_MOVES, JUNE_1, JUNE_30));
    assertEquals(of(juneToJuly), moves(BgVatDocumentCollector.POSTED_MOVES, JULY_1, JULY_31));
    // Изпадналите: в май се вижда, че е в юни; в юни — че е в юли.
    assertEquals(
        of(mayToJune), moves(BgVatDocumentCollector.REPORTED_ELSEWHERE_MOVES, MAY_1, MAY_31));
    assertEquals(
        of(juneToJuly), moves(BgVatDocumentCollector.REPORTED_ELSEWHERE_MOVES, JUNE_1, JUNE_30));
  }

  @Test
  @DisplayName("Съседните заявки: неосчетоводена, извън периода без изричен, друго дружество")
  void съседните() {
    Invoice late = sale(LocalDate.of(2026, 5, 30), "202606");
    Move daybook = move(company, late, LocalDate.of(2026, 5, 30), MoveRepository.STATUS_DAYBOOK);
    // Данъчно събитие в юни, статия в май, без изричен период — „извън периода“ за юни.
    Invoice event =
        tx(
            () -> {
              Invoice i = JPA.find(Invoice.class, sale(LocalDate.of(2026, 5, 31), null).getId());
              i.setBgVatTaxEventDate(LocalDate.of(2026, 6, 2));
              return JPA.save(i);
            });
    Move outside = posted(event, LocalDate.of(2026, 5, 31));
    // Същото, но отразена в юли — не е „извън периода“ за юни, а „отразена другаде“.
    Invoice eventJuly =
        tx(
            () -> {
              Invoice i =
                  JPA.find(Invoice.class, sale(LocalDate.of(2026, 5, 31), "202607").getId());
              i.setBgVatTaxEventDate(LocalDate.of(2026, 6, 3));
              return JPA.save(i);
            });
    Move elsewhere = posted(eventJuly, LocalDate.of(2026, 5, 31));
    Invoice ours = sale(LocalDate.of(2026, 5, 15), "202606");
    Move inOther = move(other, ours, LocalDate.of(2026, 5, 15), MoveRepository.STATUS_ACCOUNTED);

    assertEquals(of(daybook), moves(BgVatDocumentCollector.UNPOSTED_MOVES, JUNE_1, JUNE_30));
    assertEquals(of(), moves(BgVatDocumentCollector.UNPOSTED_MOVES, MAY_1, MAY_31));
    assertEquals(of(outside), moves(BgVatDocumentCollector.OUTSIDE_PERIOD_MOVES, JUNE_1, JUNE_30));
    assertEquals(
        of(elsewhere), moves(BgVatDocumentCollector.REPORTED_ELSEWHERE_MOVES, JUNE_1, JUNE_30));
    assertEquals(of(inOther), moves(BgVatDocumentCollector.OTHER_COMPANY_MOVES, JUNE_1, JUNE_30));
    assertEquals(of(), moves(BgVatDocumentCollector.OTHER_COMPANY_MOVES, MAY_1, MAY_31));
  }

  @Test
  @DisplayName("Авансите: окончателните, авансовите и приспадащите — по периода на отразяване")
  void авансите() {
    Invoice advance =
        invoice(
            InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
            InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE,
            InvoiceRepository.STATUS_VALIDATED,
            LocalDate.of(2026, 5, 20),
            "202607");
    posted(advance, LocalDate.of(2026, 5, 20));
    Invoice purchaseAdvance =
        invoice(
            InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE,
            InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE,
            InvoiceRepository.STATUS_VALIDATED,
            LocalDate.of(2026, 6, 3),
            null);
    posted(purchaseAdvance, LocalDate.of(2026, 6, 3));
    Invoice fin = sale(LocalDate.of(2026, 6, 10), null);
    posted(fin, LocalDate.of(2026, 6, 10));
    Invoice deferredFin = sale(LocalDate.of(2026, 6, 11), "202608");
    posted(deferredFin, LocalDate.of(2026, 6, 11));
    tx(
        () -> {
          Invoice f = JPA.find(Invoice.class, fin.getId());
          InvoiceLine line = new InvoiceLine();
          line.setInvoice(f);
          line.setBgAdvanceDeduction(true);
          line.setBgAdvanceInvoice(JPA.find(Invoice.class, advance.getId()));
          return JPA.save(line);
        });

    Map<String, Object> june = collector(JUNE_1, JUNE_30);
    june.put("op", InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    assertEquals(Set.of(fin.getId()), invoices(BgVatAdvanceService.PERIOD_FINALS, june));
    assertEquals(
        Set.of(purchaseAdvance.getId()), invoices(BgVatAdvanceService.PERIOD_ADVANCES, june));
    assertEquals(
        Set.of(purchaseAdvance.getId()),
        invoices(BgVatAdvanceService.PERIOD_PURCHASE_ADVANCES, june));
    Map<String, Object> july = collector(JULY_1, JULY_31);
    assertEquals(Set.of(advance.getId()), invoices(BgVatAdvanceService.PERIOD_ADVANCES, july));
    Map<String, Object> may = collector(MAY_1, MAY_31);
    assertEquals(Set.of(), invoices(BgVatAdvanceService.PERIOD_ADVANCES, may));

    Map<String, Object> deducting = new HashMap<>();
    deducting.put("advance", JPA.find(Invoice.class, advance.getId()));
    assertEquals(Set.of(fin.getId()), invoices(BgVatAdvanceService.DEDUCTING_FINALS, deducting));

    // Старите авансови без ДДС статия — по периода на отразяване.
    Invoice old =
        tx(
            () -> {
              Invoice i =
                  JPA.find(
                      Invoice.class,
                      invoice(
                              InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
                              InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE,
                              InvoiceRepository.STATUS_VALIDATED,
                              LocalDate.of(2026, 5, 2),
                              "202606")
                          .getId());
              i.setCompanyTaxTotal(new BigDecimal("20"));
              return JPA.save(i);
            });
    assertEquals(Set.of(old.getId()), invoices(BgVatAdvanceService.PERIOD_WITHOUT_VAT_MOVE, june));
    assertEquals(Set.of(), invoices(BgVatAdvanceService.PERIOD_WITHOUT_VAT_MOVE, may));
  }

  @Test
  @DisplayName("SAF-T: фактурата е в месеца на статията; предупреждението вижда и двете страни")
  void saft() {
    Invoice mayToJune = sale(LocalDate.of(2026, 5, 29), "202606");
    posted(mayToJune, LocalDate.of(2026, 5, 29));
    posted(sale(LocalDate.of(2026, 5, 27), null), LocalDate.of(2026, 5, 27));
    // Отразена в своя месец изрично — не е разминаване.
    posted(sale(LocalDate.of(2026, 6, 15), "202606"), LocalDate.of(2026, 6, 15));

    assertEquals(
        Set.of(mayToJune.getId()),
        ids(BgSaftInvoicesReader.REPORTED_ELSEWHERE, saft(MAY_1, MAY_31)));
    assertEquals(Set.of(), ids(BgSaftInvoicesReader.REPORTED_HERE, saft(MAY_1, MAY_31)));
    assertEquals(
        Set.of(mayToJune.getId()), ids(BgSaftInvoicesReader.REPORTED_HERE, saft(JUNE_1, JUNE_30)));
    assertEquals(Set.of(), ids(BgSaftInvoicesReader.REPORTED_ELSEWHERE, saft(JUNE_1, JUNE_30)));
  }

  private Map<String, Object> saft(LocalDate from, LocalDate to) {
    Map<String, Object> p = new HashMap<>();
    p.put("companyId", company.getId());
    p.put("posted", BgSaftMoveScope.POSTED);
    p.put("openingOrigin", BgSaftMoveScope.OPENING_ORIGIN);
    p.put("fromDate", from);
    p.put("toDate", to);
    p.put("periodFrom", BgVatReportingPeriod.of(from));
    p.put("periodTo", BgVatReportingPeriod.of(to));
    return p;
  }
}

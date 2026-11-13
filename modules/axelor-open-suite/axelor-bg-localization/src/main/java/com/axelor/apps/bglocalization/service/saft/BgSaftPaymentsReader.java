package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.db.BgSafPaymentMapping;
import com.axelor.apps.bglocalization.db.repo.BgSafPaymentMappingRepository;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * ЕДИНСТВЕНОТО МЯСТО, КОЕТО ЧЕТЕ БАЗАТА ЗА Payments (G4) — всичко надолу работи върху стойности.
 *
 * <p><b>⚠️ ЧЕТЕЦЪТ НА ДНЕВНИКА, С ДРУГ СПИСЪК id-та.</b> Парчетата по {@link #CHUNK}, изчистването
 * на сесията след всяко и самите стойности на реда са тези на {@link BgSaftGlEntriesReader}
 * (моделът на FEC); различен е само списъкът — {@link BgSaftMoveScope#PAYMENT_LINES}, тоест
 * редовете на статиите-плащания измежду статиите на дневника. Така плащането и транзакцията му в
 * GeneralLedgerEntries се сглобяват от едни и същи редове и TransactionID (SD.P.8) съвпада.
 *
 * <p>Мапингът на режимите към метод и механизъм на НАП ({@code BgSafPaymentMapping}) се чете
 * веднъж, по код на режима.
 */
@Singleton
public class BgSaftPaymentsReader extends BgSaftGlEntriesReader {

  /** Редовете на статиите-плащания — {@link BgSaftMoveScope#PAYMENT_LINES} и нищо повече. */
  public static final String PAYMENT_LINE_IDS =
      "SELECT self.id FROM MoveLine self WHERE "
          + BgSaftMoveScope.PAYMENT_LINES
          + " ORDER BY self.id";

  protected final BgSafPaymentMappingRepository mappingRepo;

  @Inject
  public BgSaftPaymentsReader(BgSafPaymentMappingRepository mappingRepo) {
    this.mappingRepo = mappingRepo;
  }

  /** Само id-тата на редовете на плащанията. */
  @Override
  protected List<Long> lineIds(BgSaftMoveScope scope) {
    TypedQuery<Long> query = JPA.em().createQuery(PAYMENT_LINE_IDS, Long.class);
    scope.parameters(PAYMENT_LINE_IDS).forEach(query::setParameter);
    return query.getResultList();
  }

  /** {@code BgSafPaymentMapping} по код на режима. */
  public Map<String, BgSaftPaymentsAssembler.MappingRow> paymentMappings() {
    Map<String, BgSaftPaymentsAssembler.MappingRow> map = new HashMap<>();
    for (BgSafPaymentMapping mapping : mappingRepo.all().fetch()) {
      String code = mapping.getPaymentModeCode();
      if (code == null || code.trim().isEmpty()) {
        continue;
      }
      map.put(
          code.trim(),
          new BgSaftPaymentsAssembler.MappingRow(
              mapping.getMethodCode(),
              mapping.getMechanismCode(),
              Boolean.TRUE.equals(mapping.getIsExcluded())));
    }
    return map;
  }
}

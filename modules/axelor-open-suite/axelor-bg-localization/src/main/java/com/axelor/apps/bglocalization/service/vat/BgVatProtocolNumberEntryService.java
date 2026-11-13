package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.db.BgVatProtocolNumberEntry;
import com.axelor.apps.bglocalization.db.repo.BgVatProtocolNumberEntryRepository;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;

/**
 * Въвежда номера на РЕАЛНО ИЗДАДЕН ДДС протокол на заварена осчетоводена статия без номер — и нищо
 * друго.
 *
 * <p>Номерът е реквизит на издадения протокол (ЗДДС чл. 117 ал. 2 т. 1) и не се дава автоматично
 * назад: в дневника е ГРЕШКА, докато не се пренесе от самия документ. Действието е тясно:
 *
 * <ul>
 *   <li>пита {@link BgVatProtocolNumbering#entryRefusal} — осчетоводен ДДС протокол, още без номер;
 *   <li>отказва номер, който вече е на друг протокол на дружеството;
 *   <li>пише САМО {@code Move.bgVatProtocolNumber}, с насочен UPDATE, който минава само докато
 *       номерът е празен. Хранилището на статиите и пазачите му за осчетоводена статия (периодът,
 *       балансът) не се викат — останалата статия не се пипа;
 *   <li>оставя {@link BgVatProtocolNumberEntry} — кой, кога, какъв номер и защо.
 * </ul>
 */
@Singleton
public class BgVatProtocolNumberEntryService {

  protected final BgVatProtocolNumberEntryRepository entryRepository;

  @Inject
  public BgVatProtocolNumberEntryService(BgVatProtocolNumberEntryRepository entryRepository) {
    this.entryRepository = entryRepository;
  }

  @Transactional(rollbackOn = {Exception.class})
  public BgVatProtocolNumberEntry record(
      Move move, String number, String note, User user, LocalDateTime at) throws AxelorException {
    String refusal = BgVatProtocolNumbering.entryRefusal(move, number);
    if (refusal != null) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, refusal);
    }
    String protocolNumber = number.trim();

    long taken =
        JPA.em()
            .createQuery(
                "SELECT COUNT(self) FROM Move self WHERE self.company = :company"
                    + " AND self.bgVatProtocolNumber = :number AND self.id <> :id",
                Long.class)
            .setParameter("company", move.getCompany())
            .setParameter("number", protocolNumber)
            .setParameter("id", move.getId())
            .getSingleResult();
    if (taken > 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "Номер „" + protocolNumber + "“ вече е на друг ДДС протокол на дружеството.");
    }

    int updated =
        JPA.em()
            .createQuery(
                "UPDATE Move self SET self.bgVatProtocolNumber = :number WHERE self.id = :id"
                    + " AND (self.bgVatProtocolNumber IS NULL OR self.bgVatProtocolNumber = '')")
            .setParameter("number", protocolNumber)
            .setParameter("id", move.getId())
            .executeUpdate();
    if (updated != 1) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "Протоколът междувременно е получил номер — номерът не се мени (ЗДДС чл. 117а ал. 1).");
    }

    // Управляваната инстанция в тази транзакция да вижда същото, което вече е в базата.
    move.setBgVatProtocolNumber(protocolNumber);

    BgVatProtocolNumberEntry entry = new BgVatProtocolNumberEntry();
    entry.setMove(move);
    entry.setProtocolNumber(protocolNumber);
    entry.setEnteredBy(user);
    entry.setEnteredOn(at);
    entry.setNote(note == null || note.trim().isEmpty() ? null : note.trim());
    return entryRepository.save(entry);
  }
}

package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiocr.db.repo.OcrMoveRepository;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolNumbering;
import com.axelor.inject.Beans;
import javax.persistence.PersistenceException;

/**
 * Статията дава номер на ДДС протокола си при осчетоводяване ({@link BgVatProtocolNumbering}).
 *
 * <p>Последна е във веригата на хранилищата: Account → BankPayment → Budget → Ocr → тук. {@code
 * MoveValidateServiceImpl.accounting} слага статуса и записва през {@code MoveRepository}, тоест
 * минава оттук.
 *
 * <p>Без поредица за дружеството номер не се дава и осчетоводяването НЕ спира: протоколът е верен
 * документ, а липсващата настройка се вижда в дневника като предупреждение. Грешка на самата
 * поредица обаче спира записа, за да не излезе протокол без номер при настроена поредица.
 */
public class BgMoveRepository extends OcrMoveRepository {

  @Override
  public Move save(Move move) {
    assignProtocolNumber(move);
    return super.save(move);
  }

  /**
   * Дава номер на протокола, ако му се полага ({@link BgVatProtocolNumbering#isDue}); иначе не пипа
   * нищо. Даденият номер не се презаписва.
   */
  public void assignProtocolNumber(Move move) {
    if (!BgVatProtocolNumbering.isDue(move)) {
      return;
    }
    String number = nextProtocolNumber(move);
    if (number != null && !number.trim().isEmpty()) {
      move.setBgVatProtocolNumber(number.trim());
    }
  }

  /** Следващият номер от поредицата на дружеството; {@code null}, когато поредица няма. */
  protected String nextProtocolNumber(Move move) {
    SequenceService sequenceService = Beans.get(SequenceService.class);
    Sequence sequence =
        sequenceService.getSequence(BgVatProtocolNumbering.SEQUENCE_CODE, move.getCompany());
    if (sequence == null) {
      return null;
    }
    try {
      return sequenceService.getSequenceNumber(
          sequence, move.getDate(), Move.class, "bgVatProtocolNumber", move);
    } catch (AxelorException e) {
      throw new PersistenceException(e.getMessage(), e);
    }
  }
}

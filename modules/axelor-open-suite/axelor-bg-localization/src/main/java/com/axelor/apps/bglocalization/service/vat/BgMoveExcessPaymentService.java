package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.service.move.MoveExcessPaymentService;
import com.axelor.apps.account.service.move.MoveToolService;
import com.axelor.apps.account.service.moveline.MoveLineToolService;
import com.axelor.apps.base.AxelorException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * Авансът, приспаднат с ред в окончателната фактура, не се приспада втори път като плащане.
 *
 * <p>AOS при осчетоводяване на окончателната търси „надплатеното“ ({@code getExcessPayment}) — вкл.
 * плащанията на закачените авансови фактури — и прави О.Д. Дт 412 / Кт 411. При схема А авансът
 * вече е приспаднат с реда за приспадане ({@code InvoiceLine.bgAdvanceDeduction}; Дт 412 в статията
 * на окончателната), тоест О.Д. би намалила вземането и 412 втори път. Тук плащанията на
 * приспаднатите с ред авансови изпадат; 412 се лентира от {@link BgVatAdvanceService#reconcile}.
 */
public class BgMoveExcessPaymentService extends MoveExcessPaymentService {

  protected final BgVatAdvanceService advanceService;

  @Inject
  public BgMoveExcessPaymentService(
      MoveLineRepository moveLineRepository,
      MoveToolService moveToolService,
      MoveLineToolService moveLineToolService,
      BgVatAdvanceService advanceService) {
    super(moveLineRepository, moveToolService, moveLineToolService);
    this.advanceService = advanceService;
  }

  @Override
  public List<MoveLine> getExcessPayment(Invoice invoice) throws AxelorException {
    return withoutDeducted(
        super.getExcessPayment(invoice), advanceService.deductedAdvancePaymentMoves(invoice));
  }

  /** Редовете без тези от статиите на плащанията на приспаднатите с ред авансови. */
  static List<MoveLine> withoutDeducted(List<MoveLine> lines, Set<Move> deducted) {
    if (deducted.isEmpty()) {
      return lines;
    }
    return lines.stream().filter(l -> !deducted.contains(l.getMove())).collect(Collectors.toList());
  }
}

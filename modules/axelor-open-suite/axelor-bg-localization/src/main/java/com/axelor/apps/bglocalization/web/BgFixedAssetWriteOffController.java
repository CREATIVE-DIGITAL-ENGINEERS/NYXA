package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79ProtocolService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;

/**
 * Отписването по ЗКПО — бутонът, който начислява ДДС корекцията.
 *
 * <p><b>Защо изобщо е бутон, а не част от отписването.</b> Отписването само ИЗЧИСЛЯВА корекцията по
 * чл. 79 ал. 3 (съответно приспадането по ал. 5) и я записва върху регистъра; начисляването е свое
 * действие със своя транзакция. Създаването на {@code Move} хвърля при затворен период и при
 * непозволен тип сметка, при това ПРИ ЗАПИС — вградено във {@code fullDisposal}, едно такова
 * хвърляне би върнало ЦЯЛОТО отписване, включително верните ЗКПО редове, заради ДДС настройка.
 *
 * <p>И правно: чл. 79 ал. 4 иска начисляване „в данъчния период, през който е възникнало
 * съответното обстоятелство“. Затворен ли е вече този период, кой период да носи протокола решава
 * съставителят.
 */
public class BgFixedAssetWriteOffController {

  /**
   * Съставя протокола по чл. 79 ал. 4 (или ал. 7 при приспадане) за това отписване.
   *
   * <p>⚠️ Липсваща настройка НЕ е срив: услугата връща {@code null} и записва причината в
   * забележките на отписването, а тук излиза съобщение. Недовършен ДДС журнал не бива да изглежда
   * като счетоводна грешка.
   */
  public void generateArt79Protocol(ActionRequest request, ActionResponse response) {
    try {
      BgFixedAssetWriteOff context = request.getContext().asType(BgFixedAssetWriteOff.class);
      BgFixedAssetWriteOff writeOff =
          Beans.get(BgFixedAssetWriteOffRepository.class).find(context.getId());
      if (writeOff == null) {
        return;
      }
      if (writeOff.getVatCorrectionMove() != null) {
        response.setNotify(
            "Протокол вече е съставен — двоен протокол значи двойно начислен данък.");
        return;
      }
      Move move = Beans.get(BgArt79ProtocolService.class).generate(writeOff);
      response.setReload(true);
      if (move == null) {
        response.setNotify(
            "Протокол не е съставен. Вижте „Забележки“ на отписването за причината —"
                + " типично липсваща настройка на журнала или на сметките за ДДС.");
        return;
      }
      response.setNotify(I18n.get("Протоколът по чл. 79 е съставен и отразен в дневника."));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

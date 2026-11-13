package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import com.axelor.apps.bglocalization.db.repo.BgAssetMaterialityIncreaseRepository;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetMaterialityIncreaseService;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Бутонът „Приложи вдигането“ — маркира активите, засегнати от увеличението на прага.
 *
 * <p>Отделен бутон, а не автоматика при запис: маркирането пипа ЧУЖДИ записи (активите), а такова
 * действие не бива да се случва като страничен ефект от записване на конфигурационен ред. Освен
 * това съставителят трябва да види КОЛКО актива е засегнало вдигането, преди да го приеме — числото
 * е първата проверка дали праговете са въведени вярно.
 */
@Singleton
public class BgAssetMaterialityIncreaseController {

  public void apply(ActionRequest request, ActionResponse response) {
    try {
      BgAssetMaterialityIncrease increase =
          request.getContext().asType(BgAssetMaterialityIncrease.class);
      if (increase.getId() != null) {
        increase = Beans.get(BgAssetMaterialityIncreaseRepository.class).find(increase.getId());
      }
      int count = Beans.get(BgAssetMaterialityIncreaseService.class).applyToAssets(increase);
      response.setReload(true);
      if (count == 0) {
        // Нулата е информация, не успех: или праговете не са вдигнати достатъчно, или нито един
        // актив в ДАП не пада под новия праг. И в двата случая основанието по чл. 60 ал. 3 т. 2
        // няма предмет и това трябва да се види, преди някой да разчита на него.
        response.setInfo(
            "Вдигането не засяга нито един актив. Проверете дали новите прагове са над старите и"
                + " дали в данъчния амортизационен план има активи под новия праг — маркировка без"
                + " засегнати активи не създава основание по чл. 60 ал. 3 т. 2.");
        return;
      }
      response.setInfo(
          String.format(
              "Маркирани са %d актива. Те ОСТАВАТ в данъчния амортизационен план (чл. 60 ал. 3"
                  + " т. 2) и излизат от него по реда на ал. 1 — при пълно данъчно амортизиране"
                  + " (ал. 4). Данъчната амортизация им продължава да се начислява.",
              count));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

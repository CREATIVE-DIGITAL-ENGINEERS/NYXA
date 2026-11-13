package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vies.BgVatCheckService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Проверката на идент. № по ДДС ({@code Partner.taxNbr}) на партньорската форма.
 *
 * <p>Тънък слой нарочно — нито едно правило не живее тук. Рутирането, контролната цифра и живата
 * справка са в {@link BgVatCheckService}, който се тества без Guice и без мрежа.
 *
 * <p>⚠️ <b>Само {@code setNotify}, никога {@code setAlert} или изключение.</b> Съобщението е
 * непрекъсващо: записът вече е минал и остава минал, каквото и да каже проверката. Регистрацията по
 * ДДС е правен статут на трето лице и системата няма основание да отказва запис заради нея — още
 * повече че от 01.01.2026 чл. 94, ал. 2 ЗДДС дори не извежда номера от ЕИК.
 *
 * <p>⚠️ <b>Закачено на ДВЕ места: {@code onChange} на полето и запис.</b> Възражението „заявка на
 * всяко натискане на клавиш е бавна и невъзпитана към чужда услуга“ почива на грешна представа за
 * {@code onChange}. Сверено в изходния код на клиента ({@code axelor-front}, {@code
 * views/form/builder/hooks.ts}): {@code onChangeTrigger} е {@code "blur"} по подразбиране и {@code
 * onBlur} стреля само {@code if (changed)}. Тоест една заявка на РЕАЛНА редакция, а не на клавиш.
 *
 * <p>⚠️ И двете задействания остават, защото покриват различни случаи. Живото е онова, което
 * потребителят очаква — сгрешен номер лъсва веднага, както и останалата БГ преправка на формата.
 * Задействането при запис е резервата: поставен с мишката номер и натиснат веднага „Запази“ не
 * минава през {@code blur}.
 */
@Singleton
public class BgPartnerVatNumberController {

  /**
   * Проверява идент. № по ДДС и показва съобщение, ако има какво да се каже.
   *
   * <p>⚠️ Името е без „OnSave“ нарочно: същият метод се вика и от {@code onChange} на полето, и от
   * onSave веригата. Име, което обещава едното, би заблудило следващия четец за другото.
   *
   * <p>⚠️ Хваща всичко. Проверката е УДОБСТВО върху партньорската форма; ако някога счупи нещо в
   * себе си, това не бива да се превърне нито в невъзможност да се запише партньор, нито в
   * невъзможност да се въведе номер.
   */
  public void check(ActionRequest request, ActionResponse response) {
    try {
      Partner partner = request.getContext().asType(Partner.class);
      BgVatCheckService.Outcome outcome =
          Beans.get(BgVatCheckService.class).check(partner.getTaxNbr());
      if (outcome.hasMessage()) {
        response.setNotify(I18n.get(outcome.getMessage()));
      }
    } catch (Exception e) {
      // Мълчи: непроверен номер е далеч по-доброто от отказан запис или блокирано поле.
    }
  }
}

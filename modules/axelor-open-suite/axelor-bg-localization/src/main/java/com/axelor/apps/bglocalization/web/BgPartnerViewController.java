/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.LocalizationRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.BgPartnerRegistrationViewRules;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Българският вид на партньорската форма, задвижен от полето <b>Локализация</b>.
 *
 * <p>Заварената верига стига до държавата само през {@code partner.mainAddress.country}, тоест до
 * първия записан адрес екранът няма как да знае, че партньорът е български: регистрационното поле
 * се води „Регистрационен номер“, siren и nic стоят на екрана, а идент. № по ДДС го няма. Тук
 * държавата се взема първо от локализацията и едва после от адреса, затова същото поведение излиза
 * на живо — на {@code onChange} на локализацията, без запис и без въведен адрес.
 *
 * <p>⚠️ <b>КОНТЕКСТЪТ, А НЕ БАЗАТА — И ТОВА Е ЦЯЛАТА ПРИЧИНА ЗА НОВ МЕТОД.</b> Заварената врата
 * {@code PartnerController#getHideFieldOnPartnerTypeSelect} на втория си ред прави {@code
 * Beans.get(PartnerRepository.class).find(partner.getId())}, и това я прави негодна за жив тригер
 * по две независими причини: на НОВ запис {@code getId()} е {@code null}, {@code find} хвърля и
 * собственият ѝ {@code catch} изяда всичките четири {@code setAttr} мълчаливо; а на ЗАПИСАН запис
 * тя чете локализацията от БАЗАТА, тоест вижда старата стойност, а не току-що избраната. Оттук
 * нататък партньорът идва само от {@code request.getContext()} и нищо не се презарежда по id.
 *
 * <p>⚠️ <b>ЛОКАЛИЗАЦИЯТА БИЕ АДРЕСА, НО НЕ ГО ЗАМЕСТВА.</b> Заварените партньори нямат локализация
 * и продължават да падат на адреса — точно затова българският доставчик с български адрес, който
 * работи днес, работи и утре. Локализация БЕЗ държава (ред само с език) също пропада към адреса: тя
 * не е твърдение за държава.
 *
 * <p>⚠️ <b>{@code taxNbr} СЕ ОТВАРЯ БЕЗУСЛОВНО.</b> Базовият изглед го обявява {@code
 * hidden="true"} и заварената верига го отваря само при (клиент ИЛИ доставчик) И шаблон с {@code
 * useTaxNbr} — тоест чуждестранен доставчик остава без поле, в което да се въведе чуждият му ДДС
 * номер, а проверката през VIES няма какво да провери. Редът в групата е съществен: това изпълнение
 * стои СЛЕД заварения метод, затова неговото {@code hidden} печели.
 */
@Singleton
public class BgPartnerViewController {

  /**
   * Задава заглавието на регистрационното поле и видимостта на siren, nic и taxNbr спрямо
   * държавата, изведена от локализацията (резервно — от основния адрес).
   *
   * <p>Закачено на три места: {@code onChange} на локализацията (живото изискване), последната
   * стъпка на onLoad веригата (за да оцелее презареждането) и onNew веригата (за да важи и за
   * партньор, който идва с локализация по подразбиране — стойност, зададена от {@code
   * action-record}, не поражда {@code onChange}).
   */
  public void applyLocalizationAttrs(ActionRequest request, ActionResponse response) {
    // Първо и извън try: видимостта на ДДС номера е безусловна и не бива да зависи от това дали
    // нещо по-нататък е успяло да намери държава.
    response.setAttr("taxNbr", "hidden", false);
    try {
      Partner partner = request.getContext().asType(Partner.class);
      Country country = resolveCountry(partner);
      BgPartnerRegistrationViewRules.Attrs attrs =
          BgPartnerRegistrationViewRules.resolve(country, partner.getPartnerTypeSelect());

      String title = attrs.getRegistrationCodeTitle();
      response.setAttr(
          "registrationCode", "title", title != null ? title : I18n.get("Registration number"));
      response.setAttr("siren", "hidden", attrs.isSirenHidden());
      response.setAttr("nic", "hidden", attrs.isNicHidden());
    } catch (Exception e) {
      TraceBackService.trace(e);
    }
  }

  /**
   * Държавата на партньора: локализацията първа, основният адрес — резервно.
   *
   * <p>⚠️ Локализацията се преснима по id от репозиторията, вместо да се вярва на контекстния
   * обект. На {@code onChange} клиентът праща избраната връзка като частична карта и няма гаранция,
   * че тя носи {@code country} — а това е единственото, което ни трябва от нея.
   */
  protected Country resolveCountry(Partner partner) {
    Localization localization = partner.getLocalization();
    if (localization != null) {
      Country country = localization.getCountry();
      if (country == null && localization.getId() != null) {
        Localization stored = Beans.get(LocalizationRepository.class).find(localization.getId());
        country = stored != null ? stored.getCountry() : null;
      }
      if (country != null) {
        return country;
      }
    }
    Address address = partner.getMainAddress();
    return address != null ? address.getCountry() : null;
  }
}

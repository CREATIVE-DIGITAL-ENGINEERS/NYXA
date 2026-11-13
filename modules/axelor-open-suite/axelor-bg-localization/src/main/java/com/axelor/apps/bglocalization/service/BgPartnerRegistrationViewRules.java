/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.RegistrationNumberTemplate;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.common.StringUtils;

/**
 * Кои три атрибута на партньорската форма зависят от ДЪРЖАВАТА: заглавието на {@code
 * registrationCode} и видимостта на {@code siren} и {@code nic}.
 *
 * <p><b>Чиста функция, нарочно.</b> Модулът няма mock-подпора в тестовете, а това е правило с
 * изход, който трябва да има свидетел — затова решението живее тук, а {@code
 * BgPartnerViewController} е само адаптерът, който му подава държава и му връща {@code setAttr}.
 *
 * <p>⚠️ <b>ПРАВИЛОТО Е ЗАВАРЕНОТО, ПРОМЕНЕНА Е ВРАТАТА КЪМ ДЪРЖАВАТА.</b> Трите клона по-долу са
 * пренесени едно към едно от {@code PartnerRegistrationCodeViewServiceImpl} (axelor-base) —
 * включително това, че при <i>липсваща</i> държава siren и nic остават ВИДИМИ. Заварената услуга
 * стига до държавата само през {@code partner.mainAddress.country}; тук държавата идва отвън, за да
 * може повикващият да я вземе първо от локализацията. Нищо в сметката не се променя — само кой я
 * захранва.
 *
 * <p>⚠️ <b>ЗАЩО {@code null} ЗАГЛАВИЕ, А НЕ „Регистрационен номер“.</b> {@code null} значи
 * „шаблонът няма мнение“, а не „заглавието е празно“. Превеждането на липсата в текст е работа на
 * повикващия — той има {@code I18n} и той знае езика на потребителя; тук няма как да се тества низ,
 * който зависи от активния език.
 */
public final class BgPartnerRegistrationViewRules {

  /** Отговорът: трите атрибута, които държавата решава. */
  public static final class Attrs {

    private final String registrationCodeTitle;
    private final boolean sirenHidden;
    private final boolean nicHidden;

    private Attrs(String registrationCodeTitle, boolean sirenHidden, boolean nicHidden) {
      this.registrationCodeTitle = registrationCodeTitle;
      this.sirenHidden = sirenHidden;
      this.nicHidden = nicHidden;
    }

    /** Заглавието от шаблона на държавата, или {@code null}, когато шаблон няма. */
    public String getRegistrationCodeTitle() {
      return registrationCodeTitle;
    }

    public boolean isSirenHidden() {
      return sirenHidden;
    }

    public boolean isNicHidden() {
      return nicHidden;
    }
  }

  private BgPartnerRegistrationViewRules() {}

  /**
   * @param country държавата, спрямо която се чете шаблонът; {@code null} е законен вход и значи
   *     „партньорът още не е казал къде е“
   * @param partnerTypeSelect {@code PartnerRepository.PARTNER_TYPE_*}; {@code null} се чете като
   *     „не е физическо лице“, точно както заварената услуга чете липсващата стойност
   */
  public static Attrs resolve(Country country, Integer partnerTypeSelect) {
    boolean isIndividual =
        partnerTypeSelect != null && partnerTypeSelect == PartnerRepository.PARTNER_TYPE_INDIVIDUAL;

    // Без държава заварената услуга излиза рано и НЕ крие нищо. Точно този клон изкара siren и nic
    // на екрана на доставчик #25 — но да се „поправи“ тук значи да се смени поведението на всеки
    // партньор без адрес и без локализация, а обхватът на задачата е КОГА се задейства БГ видът.
    if (country == null) {
      return new Attrs(null, false, false);
    }

    RegistrationNumberTemplate template = country.getRegistrationNumberTemplate();
    if (template == null) {
      return new Attrs(null, isIndividual, isIndividual);
    }

    String title =
        StringUtils.isBlank(template.getTitleToDisplay()) ? null : template.getTitleToDisplay();
    return new Attrs(
        title, isIndividual || !template.getUseSiren(), isIndividual || !template.getUseNic());
  }
}

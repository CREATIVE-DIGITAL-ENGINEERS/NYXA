/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.RegistrationNumberTemplate;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.service.BgPartnerRegistrationViewRules.Attrs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Свидетелят на правилото зад българския вид на партньорската форма.
 *
 * <p>Тестът е чист нарочно: модулът няма mock-подпора, а тук има изход, който трябва да се сверява
 * с думи и стойности — заглавие и две отметки за видимост. Затова решението е извадено в чиста
 * функция, а контролерът остава непроверимо тънък.
 *
 * <p>⚠️ Шаблонът за БЪЛГАРИЯ не се измисля тук: {@code useSiren=false}, {@code useNic=false} и
 * заглавие „ЕИК/Булстат“ са редът RNT-BG от нашия init-data ({@code
 * apps/init-data/en/base_registrationNumberTemplate.csv}). Ако seed-ът се смени, този тест описва
 * старото и трябва да се смени с него.
 */
class BgPartnerRegistrationViewRulesTest {

  private static Country bulgaria() {
    RegistrationNumberTemplate template = new RegistrationNumberTemplate();
    template.setTitleToDisplay("ЕИК/Булстат");
    template.setUseSiren(false);
    template.setUseNic(false);
    Country country = new Country();
    country.setAlpha2Code("BG");
    country.setRegistrationNumberTemplate(template);
    return country;
  }

  /** Държава, чийто шаблон ПОЛЗВА siren и nic — еталонът France. */
  private static Country withSirenAndNic() {
    RegistrationNumberTemplate template = new RegistrationNumberTemplate();
    template.setTitleToDisplay("SIRET");
    template.setUseSiren(true);
    template.setUseNic(true);
    Country country = new Country();
    country.setAlpha2Code("FR");
    country.setRegistrationNumberTemplate(template);
    return country;
  }

  @Test
  @DisplayName("България + юридическо лице → „ЕИК/Булстат“, siren и nic скрити")
  void bulgariaCompany() {
    Attrs attrs =
        BgPartnerRegistrationViewRules.resolve(bulgaria(), PartnerRepository.PARTNER_TYPE_COMPANY);

    assertEquals("ЕИК/Булстат", attrs.getRegistrationCodeTitle());
    assertTrue(attrs.isSirenHidden(), "Кратък регистрационен номер няма място на българска форма");
    assertTrue(attrs.isNicHidden(), "Вътрешен класификационен номер няма място на българска форма");
  }

  @Test
  @DisplayName(
      "България + физическо лице → siren и nic пак скрити (заглавието го дава K3 стъпката)")
  void bulgariaIndividual() {
    Attrs attrs =
        BgPartnerRegistrationViewRules.resolve(
            bulgaria(), PartnerRepository.PARTNER_TYPE_INDIVIDUAL);

    // ⚠️ Тук заглавието СЪЩО е „ЕИК/Булстат“, и това не е пропуск: шаблонът е един низ на ДЪРЖАВА
    // и не познава вида партньор. „ЕГН/ЛНЧ“ идва от
    // action-bg-partner-attrs-registration-code-title,
    // която се изпълнява СЛЕД тази сметка и е последната дума. Разделението е нарочно — K3 остава
    // непокътната.
    assertEquals("ЕИК/Булстат", attrs.getRegistrationCodeTitle());
    assertTrue(attrs.isSirenHidden());
    assertTrue(attrs.isNicHidden());
  }

  @Test
  @DisplayName("Държава с useSiren/useNic → полетата остават видими за юридическо лице")
  void foreignCompanyKeepsSirenAndNic() {
    Attrs attrs =
        BgPartnerRegistrationViewRules.resolve(
            withSirenAndNic(), PartnerRepository.PARTNER_TYPE_COMPANY);

    assertEquals("SIRET", attrs.getRegistrationCodeTitle());
    assertFalse(attrs.isSirenHidden(), "чуждото поведение не се променя от тази задача");
    assertFalse(attrs.isNicHidden());
  }

  @Test
  @DisplayName("Физическото лице крие siren и nic дори там, където шаблонът ги ползва")
  void foreignIndividualHidesBoth() {
    Attrs attrs =
        BgPartnerRegistrationViewRules.resolve(
            withSirenAndNic(), PartnerRepository.PARTNER_TYPE_INDIVIDUAL);

    assertTrue(attrs.isSirenHidden());
    assertTrue(attrs.isNicHidden());
  }

  @Test
  @DisplayName("Държава БЕЗ шаблон → няма заглавие, а видимостта пада на вида партньор")
  void countryWithoutTemplate() {
    Country country = new Country();
    country.setAlpha2Code("DE");

    Attrs company =
        BgPartnerRegistrationViewRules.resolve(country, PartnerRepository.PARTNER_TYPE_COMPANY);
    assertNull(
        company.getRegistrationCodeTitle(), "без шаблон няма какво да се каже за заглавието");
    assertFalse(company.isSirenHidden());
    assertFalse(company.isNicHidden());

    Attrs individual =
        BgPartnerRegistrationViewRules.resolve(country, PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    assertTrue(individual.isSirenHidden());
    assertTrue(individual.isNicHidden());
  }

  @Test
  @DisplayName("Без държава → базовото поведение: няма заглавие и НИЩО не се крие")
  void noCountryFallsBackToBase() {
    // ⚠️ Това е клонът, който изкарва siren и nic на екрана — и е ЗАВАРЕН
    // (PartnerRegistrationCodeViewServiceImpl излиза рано при липсващ адрес). Пренесен е дословно,
    // за да се върне точно базовият вид, когато потребителят изчисти локализацията.
    Attrs company =
        BgPartnerRegistrationViewRules.resolve(null, PartnerRepository.PARTNER_TYPE_COMPANY);
    assertNull(company.getRegistrationCodeTitle());
    assertFalse(company.isSirenHidden());
    assertFalse(company.isNicHidden());

    Attrs individual =
        BgPartnerRegistrationViewRules.resolve(null, PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    assertFalse(individual.isSirenHidden(), "без държава дори физическото лице не крие нищо");
    assertFalse(individual.isNicHidden());
  }

  @Test
  @DisplayName("Празно заглавие в шаблона се чете като липсващо, а не като празен низ")
  void blankTitleIsNull() {
    RegistrationNumberTemplate template = new RegistrationNumberTemplate();
    template.setTitleToDisplay("   ");
    Country country = new Country();
    country.setRegistrationNumberTemplate(template);

    assertNull(
        BgPartnerRegistrationViewRules.resolve(country, PartnerRepository.PARTNER_TYPE_COMPANY)
            .getRegistrationCodeTitle(),
        "празният низ би изтрил етикета на полето вместо да го върне на базовия");
  }

  @Test
  @DisplayName("Липсващ вид партньор се чете като „не е физическо лице“")
  void nullPartnerTypeIsNotIndividual() {
    Attrs attrs = BgPartnerRegistrationViewRules.resolve(withSirenAndNic(), null);

    assertFalse(attrs.isSirenHidden());
    assertFalse(attrs.isNicHidden());
  }
}

/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service.vies;

/**
 * Живата проверка в VIES.
 *
 * <p>Шев, а не удобство: това е ЕДИНСТВЕНОТО място в проверката на ДДС номера, което пипа мрежата.
 * Отделянето му позволява на {@code BgVatCheckService} да се тества със стъб, вместо тестът да удря
 * истинската услуга на Европейската комисия — заявка в CI би била и бавна, и чуплива, и
 * невъзпитана.
 *
 * <p>⚠️ Реализацията НИКОГА не хвърля: всяко изключение се превежда на {@link
 * BgViesResult.Status#UNAVAILABLE}. Договорът е нарочно такъв, за да не може повикващ да пропусне
 * catch и да оставе мрежова грешка да отмени запис на партньор.
 */
public interface BgViesClient {

  /**
   * @param countryCode двубуквен код за целите на ДДС (Гърция е {@code EL}), вече нормализиран
   * @param vatNumber номерът БЕЗ кода на държавата
   */
  BgViesResult check(String countryCode, String vatNumber);
}

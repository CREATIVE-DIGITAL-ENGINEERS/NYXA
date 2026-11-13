package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Прагът за данъчен амортизируем актив по чл. 50 и чл. 51 ЗКПО, като чисти функции.
 *
 * <p><b>чл. 50 ал. 1</b>, дословно: „Данъчни дълготрайни материални активи са сумите, които
 * отговарят на изискванията за амортизируеми дълготрайни материални активи съгласно Националните
 * счетоводни стандарти, <b>чиято стойност е равна или превишава по-ниската стойност от</b>: 1.
 * стойностния праг на същественост за дълготрайния материален актив, определен в счетоводната
 * политика на данъчно задълженото лице; 2. (изм. - ДВ, бр. 110 от 2007 г., в сила от 01.01.2008 г.)
 * <b>седемстотин лева</b>.“
 *
 * <p><b>чл. 51 ал. 1 т. 1</b> прави същото за нематериалните, но с различна конструкция: прагът е
 * буква <b>„г“</b> от четири КУМУЛАТИВНИ условия — „а) нямат физическа субстанция; б) се ползват
 * през период, по-дълъг от 12 месеца; в) имат ограничен полезен живот; г) са със стойност, равна
 * или превишаваща по-ниската стойност от: аа) стойностния праг на същественост за <b>дълготрайния
 * нематериален актив</b>, определен в счетоводната политика…; бб) (изм. - ДВ, бр. 110 от 2007 г.)
 * <b>седемстотин лева</b>“.
 *
 * <p>⚠️ Оттам следват две неща, които лесно се пропускат. Първо, политиката има ДВА прага — „за
 * дълготрайния материален“ и „за дълготрайния нематериален“ — и законът допуска да са различни.
 * Второ, не всяка позиция минава през праг: <b>чл. 50 ал. 2</b> (активи с право на ползване по
 * финансов лизинг съгласно МСС) и <b>чл. 51 ал. 1 т. 3</b> (суми, увеличаващи икономическата изгода
 * от наети или предоставени за ползване активи, за които законът добавя „тези суми не формират
 * данъчен дълготраен материален актив“) нямат препратка към стойностен праг.
 *
 * <p>Сверено дословно на 09.09.2026 г. срещу консолидирания ЗКПО (посл. изм. и доп. ДВ бр. 30 от 27
 * март 2026 г.).
 */
public final class BgAssetThresholdRules {

  private BgAssetThresholdRules() {}

  /**
   * По-ниската от двете стойности — тази, която решава.
   *
   * <p>„По-ниската стойност от“ е буквалният текст, тъй че функцията е min, а не max и не „прагът
   * на политиката, ако е зададен“. Разликата има значение: политика с праг 1 000 EUR НЕ вдига
   * летвата над законовите 357,90 — активът за 400 EUR пак е данъчен амортизируем актив.
   *
   * <p>Незададена стойност (null или нула) означава „този източник не обявява праг“ и се подминава,
   * вместо да смъкне минимума до нула. Нулевият праг би значел „всичко е данъчен ДА“ — тоест тих
   * отказ от проверката.
   *
   * @return решаващият праг, или {@code null} когато НИТО един източник не обявява праг — тогава
   *     гейт не се прилага
   */
  public static BigDecimal decisiveThreshold(BigDecimal policyThreshold, BigDecimal statutory) {
    BigDecimal policy = positiveOrNull(policyThreshold);
    BigDecimal law = positiveOrNull(statutory);
    if (policy == null) {
      return law;
    }
    if (law == null) {
      return policy;
    }
    return policy.compareTo(law) <= 0 ? policy : law;
  }

  /**
   * Под прага ли е активът, тоест НЕ е данъчен амортизируем актив.
   *
   * <p>⚠️ Границата е ВКЛЮЧВАЩА в полза на актива: „равна или превишава“. Актив точно на прага Е
   * данъчен ДА, тъй че „под прага“ е строго по-малко. Сгрешен знак тук изхвърля от ДАП точно
   * активите на границата.
   *
   * <p>Липсващ праг значи, че гейт не се прилага — връща се {@code false}, защото отсъствието на
   * конфигурация не е основание активът да излезе от плана.
   */
  public static boolean isBelowThreshold(BigDecimal grossValue, BigDecimal threshold) {
    if (threshold == null || threshold.signum() <= 0) {
      return false;
    }
    BigDecimal value = grossValue == null ? BigDecimal.ZERO : grossValue;
    return value.compareTo(threshold) < 0;
  }

  /**
   * Пада ли активът под НОВИЯ праг — проверката за събитието по чл. 60 ал. 3 т. 2.
   *
   * <p>Отделна функция от {@link #isBelowThreshold}, макар аритметиката да е същата, защото
   * отговаря на друг въпрос: не „влиза ли в ДАП“, а „засяга ли го вдигането“. Тук се пита за актив,
   * който ВЕЧЕ е в плана — вж. защо в {@code BgAssetMaterialityIncrease}.
   */
  public static boolean isCaughtByIncrease(
      BigDecimal grossValue, BigDecimal previousThreshold, BigDecimal newThreshold) {
    if (!isIncrease(previousThreshold, newThreshold)) {
      return false;
    }
    return isBelowThreshold(grossValue, newThreshold);
  }

  /**
   * Увеличение ли е промяната.
   *
   * <p>Чл. 60 ал. 3 т. 2 говори за отписване „в резултат на <b>увеличение</b> в стойностния праг на
   * същественост“. Свалянето на прага не поражда отписване и не бива да минава за такова — то
   * по-скоро ВКАРВА активи в обхвата, а не ги изважда.
   */
  public static boolean isIncrease(BigDecimal previousThreshold, BigDecimal newThreshold) {
    if (newThreshold == null || newThreshold.signum() <= 0) {
      return false;
    }
    BigDecimal previous = previousThreshold == null ? BigDecimal.ZERO : previousThreshold;
    return newThreshold.compareTo(previous) > 0;
  }

  /**
   * Сваля данъчния план от multi-select низа, без да закачи останалите.
   *
   * <p>⚠️ Не се пипа с {@code replace("1", "")}. {@code depreciationPlanSelect} е низ от кодове,
   * съединени със запетая („1,2“), а кодовете са единични цифри — тъй че текстова замяна върху
   * „1,2“ оставя „,2“, а върху „2,1“ — „2,“. Стойност с водеща или висяща запетая минава мълчаливо
   * през {@code contains(...)} на базата и се появява като повреден избор в изгледа. Затова низът
   * се разцепва и се сглобява наново.
   *
   * <p>Празният резултат става {@code DEPRECIATION_PLAN_NONE}, а не празен низ: базовият генератор
   * чете {@code contains(...)} и празният низ би минал, но изгледът показва избор без стойност.
   */
  public static String withoutFiscalPlan(String plan) {
    if (plan == null || plan.isEmpty()) {
      return plan;
    }
    List<String> kept = new ArrayList<>();
    for (String code : plan.split(",")) {
      String trimmed = code.trim();
      if (!trimmed.isEmpty() && !FixedAssetRepository.DEPRECIATION_PLAN_FISCAL.equals(trimmed)) {
        kept.add(trimmed);
      }
    }
    return kept.isEmpty() ? FixedAssetRepository.DEPRECIATION_PLAN_NONE : String.join(",", kept);
  }

  private static BigDecimal positiveOrNull(BigDecimal value) {
    return value == null || value.signum() <= 0 ? null : value;
  }
}

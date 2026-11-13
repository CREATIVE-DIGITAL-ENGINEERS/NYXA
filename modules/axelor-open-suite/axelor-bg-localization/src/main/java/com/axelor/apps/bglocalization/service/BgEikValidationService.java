/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * ЕИК / БУЛСТАТ control-digit validation.
 */
package com.axelor.apps.bglocalization.service;

/**
 * Контролната цифра на ЕИК (Единен идентификационен код) по ЗРБУЛСТАТ.
 *
 * <p><b>Две дължини, две различни степени на доказаност — и това решава как се ползва класът.</b>
 *
 * <ul>
 *   <li><b>9-значен</b> (юридически лица, чл. 6, ал. 1): алгоритъмът е ДОКАЗАН срещу шест реални
 *       кода от Търговския регистър, плюс два подправени, които падат. Затова повикващият има право
 *       да БЛОКИРА запис на невалиден 9-значен код.
 *   <li><b>13-значен</b> (клонове и поделения, чл. 6, ал. 2): СТРУКТУРАТА е законово потвърдена —
 *       първите 9 са кодът на главния субект — но самата опашкова контролна цифра НЕ Е сверена
 *       срещу реален код. Затова повикващият само ПРЕДУПРЕЖДАВА, никога не блокира. Виж {@link
 *       #validateEik13(String)}.
 * </ul>
 *
 * <p><b>⚠️ Стойността се чете КАКТО Е ВЪВЕДЕНА, само с отрязани крайни интервали.</b> Нарочно не се
 * маха нито префикс „BG", нито вътрешни интервали или точки. Канонизацията в {@code
 * SupplierEik#canonical} (axelor-ai-ocr) съществува, за да СВЕРЯВА разпознат от скенер текст срещу
 * заварени редове — там мърлявата стойност вече е в базата. Тук е обратното: полето трябва да
 * ПРИЕМЕ само чист ЕИК, защото точно това SQL сверяването после чисти с REPLACE. Изчистено на входа
 * значи едно място по-малко, където двата пътя могат да се разминат.
 *
 * <p>⚠️ Класът НЕ пише нищо. Заварената врата на Axelor ({@code
 * RegistrationNumberValidator#setRegistrationCodeValidationValues}) ПРЕЗАПИСВА {@code taxNbr} от
 * кода, а за България ДДС регистрацията е правен статут, а не производна на ЕИК. Затова тази
 * проверка е отделна и е чиста функция.
 */
public class BgEikValidationService {

  /** Тегла 1..8 върху първите осем цифри — основната схема за 9-значния код. */
  private static final int[] EIK9_WEIGHTS = {1, 2, 3, 4, 5, 6, 7, 8};

  /** Тегла 3..10 — резервната схема, когато основната даде остатък 10. */
  private static final int[] EIK9_WEIGHTS_FALLBACK = {3, 4, 5, 6, 7, 8, 9, 10};

  /** Тегла върху цифри 9–12 (0-indexed 8–11) — основната схема за опашката на 13-значния код. */
  private static final int[] EIK13_WEIGHTS = {2, 7, 3, 5};

  /** Резервните тегла за опашката, когато основната даде остатък 10. */
  private static final int[] EIK13_WEIGHTS_FALLBACK = {4, 9, 5, 7};

  /**
   * Валиден ли е кодът като ЕИК — 9-значен ИЛИ 13-значен.
   *
   * <p>⚠️ Всяка друга дължина е {@code false}. Чл. 6 познава точно две: 9 за юридическите лица и 13
   * за клоновете по ал. 2. Десет или дванайсет цифри не са „почти ЕИК", а друго нещо.
   */
  public boolean validateEik(String value) {
    int[] digits = toDigits(value);
    if (digits == null) {
      return false;
    }
    if (digits.length == 9) {
      return validateEik9(digits);
    }
    if (digits.length == 13) {
      return validateEik13(digits);
    }
    return false;
  }

  /**
   * 9-значният код. ДОКАЗАН срещу реални кодове от Търговския регистър.
   *
   * <p>Сума на първите осем цифри с тегла 1..8, остатък по модул 11. Остатък 10 не е цифра, затова
   * сметката се прави наново с тегла 3..10; ако и тя даде 10, контролната цифра е 0. Валиден, ако
   * полученото съвпада с деветата цифра.
   *
   * <p>⚠️ Резервната схема НЕ е мъртъв код и това е измерено: еталонът {@code 130175000} минава
   * точно през нея (основната сметка дава остатък 10).
   */
  public boolean validateEik9(String value) {
    int[] digits = toDigits(value);
    return digits != null && digits.length == 9 && validateEik9(digits);
  }

  /**
   * 13-значният код (клон или поделение по чл. 6, ал. 2): валиден 9-значен код плюс опашка.
   *
   * <p>⚠️ <b>ДА СЕ ПОТВЪРДИ.</b> Структурата е законово потвърдена, но опашковата контролна цифра
   * НЕ е сверена срещу нито един реален 13-значен код — тестовете ѝ са построени от самата сметка и
   * затова доказват само вътрешна съгласуваност. Докато това не се смени, повикващият
   * ПРЕДУПРЕЖДАВА, а не блокира: грешен алгоритъм тук би отказал запис на законен клон.
   */
  public boolean validateEik13(String value) {
    int[] digits = toDigits(value);
    return digits != null && digits.length == 13 && validateEik13(digits);
  }

  protected boolean validateEik9(int[] digits) {
    int control = weightedRemainder(digits, 0, EIK9_WEIGHTS);
    if (control == 10) {
      control = weightedRemainder(digits, 0, EIK9_WEIGHTS_FALLBACK);
      if (control == 10) {
        control = 0;
      }
    }
    return control == digits[8];
  }

  protected boolean validateEik13(int[] digits) {
    if (!validateEik9(java.util.Arrays.copyOfRange(digits, 0, 9))) {
      return false;
    }
    int control = weightedRemainder(digits, 8, EIK13_WEIGHTS);
    if (control == 10) {
      control = weightedRemainder(digits, 8, EIK13_WEIGHTS_FALLBACK);
      if (control == 10) {
        control = 0;
      }
    }
    return control == digits[12];
  }

  /**
   * Претеглена сума по модул 11 върху {@code weights.length} цифри, започвайки от {@code offset}.
   */
  protected int weightedRemainder(int[] digits, int offset, int[] weights) {
    int sum = 0;
    for (int i = 0; i < weights.length; i++) {
      sum += digits[offset + i] * weights[i];
    }
    return sum % 11;
  }

  /**
   * Само цифри, с отрязани крайни интервали; {@code null} при празно или при какъвто и да е друг
   * знак — включително интервал или префикс „BG" ВЪТРЕ в стойността (виж бележката в класа).
   */
  protected int[] toDigits(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int[] digits = new int[trimmed.length()];
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
      digits[i] = c - '0';
    }
    return digits;
  }
}

package com.axelor.apps.bglocalization.service.d1;

/**
 * КАКВО СЕ ПОДАВА — Д1, Д6 или Д1+Д6 — като ЧИСТО правило, отделено от записването.
 *
 * <p>Входът са четири булеви факта на екрана „Подаване Д1/Д6 към НАП“: двата чекбокса „няма
 * основание за подаване на Д1/Д6“ и дали е избрана генерирана Д1/Д6. Изходът е едно решение с
 * НАЗОВАНА причина, проверимо без база и без Guice — по образеца на {@link BgD1SubmissionPlan} и
 * {@link BgD1PeriodLockRules}.
 *
 * <p><b>⚠️ ЧЕКБОКСЪТ „НЯМА ОСНОВАНИЕ“ НЕ Е НАЧИН ДА СЕ ЗАОБИКОЛИ СЪВМЕСТНОТО ПОДАВАНЕ.</b> Подаване
 * само с Д1 се допуска ЕДИНСТВЕНО когато операторът ИЗРИЧНО е отбелязал „няма основание за Д6“ —
 * тоест е заявил, че за месеца няма Д6, а не просто е пропуснал да я избере. Документацията на
 * Declarations API потвърждава комбинацията „само Д1“ (когато се подава само Д1, файл за Д6 не се
 * праща); затова тя е РАЗРЕШЕНА тук.
 *
 * <p><b>⚠️ ПОДАВАНЕ САМО С Д6 НЕ Е ПОТВЪРДЕНО И СЕ БЛОКИРА.</b> Дали НАП приема Д6 без Д1 е ОТВОРЕН
 * въпрос (документацията описва „само Д1“, но не и „само Д6“), затова той остава за счетоводно/
 * техническо потвърждение на по-късен етап. До потвърждение решението е отказ, а не допускане по
 * догадка — същият принцип като при матрицата {@code DeclarationsDocumentTypeMatrix}: регистрира се
 * само ИЗРИЧНО потвърденото.
 */
public final class BgD1D6FilingRules {

  private BgD1D6FilingRules() {}

  /** Решението на правилото плюс неговата причина (за екрана). */
  public enum Kind {
    /** Подава се ЕДНО подаване с два файла — Д1 (позиция 0) и Д6 (позиция 1). */
    FILE_D1_AND_D6(true),
    /** Подава се само Д1 (операторът изрично е отбелязал „няма основание за Д6“). */
    FILE_D1_ONLY(true),
    /** Само Д6 — непотвърдена комбинация, блокира се до потвърждение. */
    BLOCKED_D6_ONLY(false),
    /** Нищо за подаване (и двете са без основание или нищо не е избрано). */
    NOTHING_TO_FILE(false),
    /** „Няма основание за Д1“, но е избрана Д1 — противоречие. */
    CONTRADICTION_D1(false),
    /** „Няма основание за Д6“, но е избрана Д6 — противоречие. */
    CONTRADICTION_D6(false),
    /** Избрана е само Д1, но Д6 нито е избрана, нито е отбелязана без основание. */
    NEED_D6_DECISION(false);

    private final boolean proceed;

    Kind(boolean proceed) {
      this.proceed = proceed;
    }

    /** Може ли да се продължи към създаване на подаване. */
    public boolean canProceed() {
      return proceed;
    }
  }

  /** Решение с текст, годен за {@code response.setError/ setInfo}. */
  public static final class Decision {
    private final Kind kind;
    private final String message;

    Decision(Kind kind, String message) {
      this.kind = kind;
      this.message = message;
    }

    public Kind getKind() {
      return kind;
    }

    public String getMessage() {
      return message;
    }
  }

  /**
   * Решава какво да се подаде.
   *
   * @param noBasisD1 отбелязано „няма основание за подаване на Д1“
   * @param hasD1 избрана е генерирана Декларация обр. 1
   * @param noBasisD6 отбелязано „няма основание за подаване на Д6“
   * @param hasD6 избрана е генерирана Декларация обр. 6
   */
  public static Decision decide(
      boolean noBasisD1, boolean hasD1, boolean noBasisD6, boolean hasD6) {

    if (noBasisD1 && hasD1) {
      return new Decision(
          Kind.CONTRADICTION_D1,
          "“No grounds for filing D1” is ticked, but a Form 1 declaration is selected. Clear one of"
              + " them: either untick the box or remove the selection.");
    }
    if (noBasisD6 && hasD6) {
      return new Decision(
          Kind.CONTRADICTION_D6,
          "“No grounds for filing D6” is ticked, but a Form 6 declaration is selected. Clear one of"
              + " them: either untick the box or remove the selection.");
    }

    boolean fileD1 = hasD1 && !noBasisD1;
    boolean fileD6 = hasD6 && !noBasisD6;

    if (!fileD1 && !fileD6) {
      return new Decision(
          Kind.NOTHING_TO_FILE,
          "Nothing to file: neither D1 nor D6 is selected (or both are marked as having no grounds"
              + " for filing).");
    }
    if (fileD1 && fileD6) {
      return new Decision(
          Kind.FILE_D1_AND_D6, "Filing with two files: Form 1 and Form 6 declarations (DEC_1_6).");
    }
    if (fileD1) {
      // Само Д1 — иска ИЗРИЧНО „няма основание за Д6“, не просто липсващ избор.
      if (!noBasisD6) {
        return new Decision(
            Kind.NEED_D6_DECISION,
            "Only D1 is selected. To file D1 on its own, explicitly tick “No grounds for filing"
                + " D6”; otherwise also select a generated Form 6 declaration. Filing D1 only must not"
                + " silently bypass the joint filing.");
      }
      return new Decision(
          Kind.FILE_D1_ONLY,
          "Filing D1 only (“No grounds for filing D6” is ticked). The documentation confirms this"
              + " combination.");
    }
    // Остава: само Д6.
    return new Decision(
        Kind.BLOCKED_D6_ONLY,
        "Filing D6 only (without D1) is NOT among the confirmed combinations. The NAP documentation"
            + " describes “D1 only” but not “D6 only” — whether it is allowed awaits accounting/technical"
            + " confirmation (Stage 2). Until confirmed, the filing is blocked rather than allowed on a"
            + " guess.");
  }
}

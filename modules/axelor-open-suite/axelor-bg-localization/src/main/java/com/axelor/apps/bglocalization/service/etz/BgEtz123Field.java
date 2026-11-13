package com.axelor.apps.bglocalization.service.etz;

/**
 * ПОЛЕТАТА НА {@code etz123employer} — промяната на работодател по чл. 10 ал. 5.
 *
 * <p>Следва точно {@code etz_123_employer.xsd}; номерата са от Приложение № 2 към Заповед {@value
 * BgEtzXsd#ORDER_NUMBER}.
 *
 * <p><b>⚠️ ЕДНАКВИ ТАГОВЕ, РАЗЛИЧНИ НОМЕРА.</b> {@code egn} е т. 5 в {@code etzemploy} и т. 6 тук;
 * {@code contracttype} е т. 12 и в двете, но {@code changedate} е т. 16 там и т. 13 тук. Затова
 * каталозите са два, а не един с флаг: един каталог би сбъркал точно номерата в отказите и в
 * отчета, тоест точно там, където човек ги чете.
 *
 * <p><b>⚠️ И ЗАТОВА ТУК НЯМА ПОЛЕ „ТИП НА ДОКУМЕНТА“.</b> При чл. 123 няма сключване/изменение/
 * прекратяване — има само промяна, а нейният вид е в {@code correctiontype} (т. 3), който е ДРУГА
 * номенклатура с 11 стойности ({@link BgEtzCorrectionType}).
 */
public enum BgEtz123Field {

  // ── заглавна част (извън rowenum) ────────────────────────────────────────
  /** 4. ЕИК на ПРЕДИШНИЯ работодател — стои на корена. */
  FORMEREIK("formereik", "4", true, "ЕИК на предишния работодател"),
  /** 5. ЕИК на НОВИЯ работодател — стои на корена. */
  EIK("eik", "5", true, "ЕИК на новия работодател"),

  // ── редът (rowenum) ──────────────────────────────────────────────────────
  /** 1. Вид правоотношение. */
  EMPLOYTYPE("employtype", "1", true, "Вид правоотношение"),
  /** 2. Код корекция. */
  CODECORRECTION("codecorrection", "2", true, "Код корекция"),
  /** 3. Основание на промяната — 1..11 по Приложение № 2. */
  CORRECTIONTYPE("correctiontype", "3", true, "Основание на промяната"),
  /** 6. ЕГН/ЛН/ЛНЧ. */
  EGN("egn", "6", true, "ЕГН (ЛН, ЛНЧ)"),
  /** 7. Код за идентификатора. */
  EGNTYPE("egntype", "7", true, "Код за ЕГН"),
  /** 8. Име. */
  FIRSTNAME("firstname", "8", true, "Име"),
  /** 9. Презиме. */
  SECONDNAME("secondname", "9", false, "Презиме"),
  /** 10. Фамилия. */
  THIRDNAME("thirdname", "10", true, "Фамилия"),
  /** 11. Дата на сключване на договора / възникване на служебното правоотношение. */
  CONTRACTDATE("contractdate", "11", true, "Дата на сключване"),
  /** 12. Основание на договора — СЪЩАТА номенклатура като т. 12 на {@code etzemploy}. */
  CONTRACTTYPE("contracttype", "12", true, "Основание на договора"),
  /** 13. Дата на промяна на работодателя — задължителна и по схема, и по приложение. */
  CHANGEDATE("changedate", "13", true, "Дата на промяна на работодателя"),
  /** 14. Код по ЕКАТТЕ. */
  CODEEKATTE("codeekatte", "14", false, "Код по ЕКАТТЕ");

  private final String tag;
  private final String number;
  private final boolean schemaRequired;
  private final String title;

  BgEtz123Field(String tag, String number, boolean schemaRequired, String title) {
    this.tag = tag;
    this.number = number;
    this.schemaRequired = schemaRequired;
    this.title = title;
  }

  public String getTag() {
    return tag;
  }

  public String getNumber() {
    return number;
  }

  /** Дали СХЕМАТА го иска — не е задължителността на приложението. */
  public boolean isSchemaRequired() {
    return schemaRequired;
  }

  public String getTitle() {
    return title;
  }

  public String label() {
    return "т. " + number + " (" + title + ")";
  }
}

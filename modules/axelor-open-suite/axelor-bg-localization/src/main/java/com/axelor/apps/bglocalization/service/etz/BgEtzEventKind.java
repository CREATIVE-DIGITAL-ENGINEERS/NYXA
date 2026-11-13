package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;

/**
 * СЪБИТИЕТО ПО ТРУДОВОТО/СЛУЖЕБНОТО ПРАВООТНОШЕНИЕ, И ТИПЪТ ДОКУМЕНТ, КОЙТО ГО НОСИ.
 *
 * <p><b>Разделението е по ДОКУМЕНТ, не по удобство.</b> Трите събития по правоотношението —
 * сключване, изменение, прекратяване — вървят по {@code DEC_ETZ62}; промяната на работодател по чл.
 * 123 ал. 1 или чл. 123а ал. 1 или ал. 4 КТ върви по {@code DEC_ETZ123}. Това не е наша подредба, а
 * договорът на API-то: двата типа имат РАЗЛИЧНИ XSD схеми, публикувани поотделно (виж {@link
 * BgEtzXsd}).
 *
 * <p><b>⚠️ И ЗАТОВА „ИЗМЕНЕНИЕ“ НЕ ПОГЛЪЩА „ПРОМЯНА НА РАБОТОДАТЕЛ“.</b> И двете са изменение на
 * правоотношението в житейския смисъл, но по чл. 123 правоотношението се ЗАПАЗВА и се сменя
 * страната по него — затова регистърът го третира като отделен документ с отделна схема. Слети в
 * един вид, те биха се подали по грешната схема и биха паднали на структурна проверка.
 *
 * <p><b>⚠️ СЛУЖЕБНОТО ПРАВООТНОШЕНИЕ НЕ Е ЧЕТВЪРТИ ВИД СЪБИТИЕ.</b> Възникването, изменението и
 * прекратяването на служебно правоотношение вървят по СЪЩИТЕ три стойности — разликата е във вида
 * на правоотношението (01/02, {@code bg.relationship.kind.select}), който е отделен реквизит на
 * записа. Отделни стойности тук биха удвоили изброяването без нито едно различие в поведението.
 */
public enum BgEtzEventKind {

  /** Сключване на трудов договор / възникване на служебно правоотношение. */
  CONCLUSION(ServiceDocumentType.DEC_ETZ62, FileDocumentType.DEC_ETZ62, "сключване"),

  /** Изменение на трудовия договор / на служебното правоотношение (допълнително споразумение). */
  AMENDMENT(ServiceDocumentType.DEC_ETZ62, FileDocumentType.DEC_ETZ62, "изменение"),

  /** Прекратяване на трудовия договор / на служебното правоотношение. */
  TERMINATION(ServiceDocumentType.DEC_ETZ62, FileDocumentType.DEC_ETZ62, "прекратяване"),

  /** Промяна на работодателя по чл. 123 ал. 1 / чл. 123а ал. 1 или ал. 4 КТ, или на органа. */
  EMPLOYER_CHANGE(
      ServiceDocumentType.DEC_ETZ123, FileDocumentType.DEC_ETZ123, "промяна на работодател");

  private final ServiceDocumentType service;
  private final FileDocumentType file;
  private final String label;

  BgEtzEventKind(ServiceDocumentType service, FileDocumentType file, String label) {
    this.service = service;
    this.file = file;
    this.label = label;
  }

  /** Услугата, по която се подава събитието. */
  public ServiceDocumentType getService() {
    return service;
  }

  /** Типът на ФАЙЛА вътре в подаването. */
  public FileDocumentType getFile() {
    return file;
  }

  /** Наименование за човек — влиза в отказите и в името на подаването. */
  public String getLabel() {
    return label;
  }
}

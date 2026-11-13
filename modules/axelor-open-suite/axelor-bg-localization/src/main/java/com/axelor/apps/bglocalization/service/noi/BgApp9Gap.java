package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;

/**
 * ЕДНА ЛИПСА, КОЯТО СПИРА ЕМИСИЯТА — коя точка, кой документ, и какво точно да се направи.
 *
 * <p><b>⚠️ ЗАЩО ОТДЕЛЕН КЛАС ДО {@link BgApp9Outcome}, А НЕ ВТОРО ЗНАЧЕНИЕ НА НЕГО.</b> Присъдата
 * на Ф1 отговаря на въпроса „можа ли резолверът да ИЗВЕДЕ тази точка“. Липсата тук отговаря на
 * друг: „може ли ФАЙЛЪТ да тръгне“. Двата въпроса имат различни отговори за едни и същи полета — т.
 * 10 е {@code MANUAL} за резолвера (той няма право да я пълни) и напълно допустимо празна за НОИ,
 * докато т. 4 е {@code REFUSED} за резолвера и СПИРА файла. Слети в един тип, „резолверът не можа“
 * и „НОИ няма да го приеме“ щяха да се броят заедно и операторът щеше да гони двайсет реда, за да
 * намери трите, които го спират.
 *
 * <p><b>⚠️ И ЗАТОВА ЛИПСАТА НОСИ ДОКУМЕНТ.</b> Един файл носи много удостоверения ({@code
 * NumberPril9} е {@code maxOccurs="unbounded"}); „не е попълнен код за вид осигурен“ без името на
 * листа е съобщение, което не може да се изпълни.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и от базата пази само id-то на удостоверението ({@link #declarationId}).
 */
public final class BgApp9Gap {

  /** Стойността на {@link #document} за липса в ЗАГЛАВНАТА част, обща за целия файл. */
  public static final String FILE_LEVEL = "заглавна част на файла";

  /** Кой документ във файла — номерът на болничния лист и лицето, или {@link #FILE_LEVEL}. */
  public final String document;

  /** Точката от образеца: „т. 4“, „т. 9“, „ТП на НОИ“. Никога празно. */
  public final String clause;

  /**
   * Името на елемента по схемата — {@code TypeOfInsured}, {@code NSSI_RO}, {@code
   * PersonalLegalWorkHours}.
   *
   * <p>⚠️ Носи се, за да може съобщението да се сравни с отчета на уеб-валидатора на НОИ, който
   * говори с ИМЕНА НА ТАГОВЕ, а не с номера на точки. Без него човек трябва да превежда наум.
   */
  public final String tag;

  /** ЦЯЛО ИЗРЕЧЕНИЕ: какво липсва И къде се попълва. Адресат: операторът. */
  public final String reason;

  /**
   * Id-то на удостоверението, чиято е липсата — {@code null} за заглавната част и за липса, която
   * още не е закачена към запис.
   *
   * <p>⚠️ КЛЮЧЪТ Е ТОЗИ, НЕ {@link #document}. Етикетът „лист № · име“ е за човека и НЕ е уникален:
   * лице с два договора има по едно удостоверение за ВСЕКИ договор, със същия лист и същото име. По
   * етикета липсите на едното изключваха от месечния износ и изрядното.
   */
  public final Long declarationId;

  private BgApp9Gap(String document, String clause, String tag, String reason, Long declarationId) {
    this.document = document;
    this.clause = clause;
    this.tag = tag;
    this.reason = reason;
    this.declarationId = declarationId;
  }

  /** Липса в конкретно удостоверение. */
  public static BgApp9Gap of(String document, String clause, String tag, String reason) {
    return new BgApp9Gap(document, clause, tag, reason, null);
  }

  /** Липса в заглавната част — тя спира ЦЕЛИЯ файл, не един документ. */
  public static BgApp9Gap header(String clause, String tag, String reason) {
    return new BgApp9Gap(FILE_LEVEL, clause, tag, reason, null);
  }

  /** Същата липса, закачена към удостоверението по id — виж {@link #declarationId}. */
  public BgApp9Gap about(BgApp9Declaration declaration) {
    return new BgApp9Gap(
        document, clause, tag, reason, declaration == null ? null : declaration.getId());
  }

  /** Отнася ли се липсата за ТОВА удостоверение — по id; запис без id не съвпада с нищо. */
  public boolean isAbout(BgApp9Declaration declaration) {
    return declaration != null
        && declaration.getId() != null
        && declaration.getId().equals(declarationId);
  }

  /** Редът, който операторът чете в списъка „какво липсва, за да е валидна“. */
  public String asNote() {
    return "[! ЛИПСВА] " + document + " · " + clause + " (" + tag + ") — " + reason;
  }

  @Override
  public String toString() {
    return asNote();
  }
}

package com.axelor.apps.bglocalization.service.art73.adapter;

import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileLayout;
import com.google.inject.Singleton;

/**
 * ФИЗИЧЕСКИЯТ ФОРМАТ НА ОБРАЗЕЦ 5-2001 — XML по схемата {@code dec731}.
 *
 * <p>Утвърден със <b>{@link BgArt73Xsd#ORDER заповед № З-ЦУ-1944/17.12.2019 г.}</b> на
 * изпълнителния директор на НАП; схемата е закована в {@link BgArt73Xsd}.
 *
 * <p><b>⚠️ КОИ КОЛОНИ МЪЛЧАТ, РЕШАВА КАТАЛОГЪТ, А НЕ СПИСЪК ТУК — И ТАКА ДВАТА ИЗТОЧНИКА НЕ СЕ
 * СБЛЪСКВАТ В КОД.</b> Заповедта (2019) изброява 8141, 820 и 821; указание т. 5 към образеца за
 * 2025 г. изброява 5031, 8141 и 820. Приложени са УКАЗАНИЯТА: те са по-новите, а 5031 сам се
 * определя като сбор („Общ размер на доходите … до 10 лв.“) — ред, който сумира много лица, не може
 * да носи едно име.
 *
 * <p>Нито един от двата списъка НЕ е преписан тук. Изброени в този клас, кодовете биха оцелели
 * следващата редакция мълчаливо — тоест писачът щеше да пропуска имената на код, който вече ги
 * иска. Затова признакът идва от {@code BgIncomeType.isAggregate} през реда, а нов сборен код е
 * ЕДИН seed ред.
 *
 * <p><b>⚠️ ПРОПУСНАТ ≠ ПРАЗЕН.</b> Заповедта не казва „остави празно“, а „задължително да НЕ е
 * попълнено“. {@code <foreign></foreign>} е попълнен елемент с празна стойност и пада на схемата
 * ({@code xs:integer}); липсващият елемент е отговорът. Виж {@link BgArt73XmlWriter}.
 *
 * <p><b>⚠️ {@code isterm} СЕ ПИШЕ КАТО 0, А НЕ СЕ ПРОПУСКА.</b> Схемата го дава като {@code
 * minOccurs="0"}, но документацията ѝ описва чекбокс с две състояния: „1 - при отбелязан чекбокс,
 * иначе 0“. Този модул не строи справки при заличаване/прекратяване по чл. 162 ЗКПО, тоест 0 е
 * ВЯРНОТО твърдение, а не подразбираща се стойност. Пропуснат, елементът оставя въпроса без
 * отговор.
 */
@Singleton
public class BgArt73Para1FileLayout implements BgDeclarationFileLayout<BgArt73Para1FileContent> {

  public static final String FORM_NAME = "образец 5-2001 (справка по чл. 73, ал. 1 ЗДДФЛ)";

  /** Този модул не подава при заличаване/прекратяване по чл. 162 ЗКПО. */
  private static final int NOT_A_TERMINATION_FILING = 0;

  @Override
  public String formName() {
    return FORM_NAME;
  }

  /** ⚠️ Форматът Е потвърден — заповед {@link BgArt73Xsd#ORDER}. */
  @Override
  public boolean isConfirmed() {
    return true;
  }

  @Override
  public String fileName() {
    return BgArt73Xsd.FILE_NAME;
  }

  @Override
  public String encoding() {
    return BgArt73Xsd.ENCODING;
  }

  @Override
  public String document(BgArt73Para1FileContent content) {
    requireConfirmed();
    BgArt73XmlWriter xml = new BgArt73XmlWriter();

    xml.open(BgArt73Xsd.ROOT_ELEMENT);
    xml.integer("isterm", NOT_A_TERMINATION_FILING);
    xml.integer("year", content.getTaxYear());

    xml.open("part1");
    xml.text("eik", content.getPayerIdentifier());
    xml.text("name", content.getPayerName());
    xml.text("phone", content.getContactPhone());
    xml.text("mail", content.getContactEmail());
    xml.text("reprname", content.getRepresentativeName());
    xml.text("reprident", content.getRepresentativeIdentifier());
    xml.close("part1");

    xml.open("part2");
    for (BgArt73Para1FileRow row : content.getRows()) {
      writeRow(xml, row);
    }
    xml.close("part2");

    xml.close(BgArt73Xsd.ROOT_ELEMENT);
    return xml.document();
  }

  /**
   * ЕДИН РЕД НА ЧАСТ ІІ — колони 1 до 15, в реда на образеца.
   *
   * <p>⚠️ Схемата ползва {@code xs:all}, тоест редът на елементите е без значение ЗА НЕЯ. Тук той е
   * редът на КОЛОНИТЕ, защото файлът се чете и от хора, които го сверяват срещу хартията.
   */
  private void writeRow(BgArt73XmlWriter xml, BgArt73Para1FileRow row) {
    xml.open("rowsenum");

    xml.integer("correctioncode", digit(row.getCorrectionCode()));

    // ⚠️ Колони 2–9 НЕ се попълват за сборните кодове — заповедта, поле foreign/identtype/имена.
    if (!row.isAggregateRow()) {
      xml.integer("foreign", row.isForeignPerson() ? 1 : 0);
      xml.integer("identtype", digit(row.getIdentifierType()));
      xml.text("ident", row.getIdentifier());
      xml.date("birthdate", row.getBirthDate());
      xml.text("firstname", row.getFirstName());
      xml.text("secondname", row.getMiddleName());
      xml.text("thirdname", row.getLastName());
      xml.text("countrycode", row.getCountryCode());
    }

    xml.text("incomecode", row.getIncomeCode());
    // ⚠️ income е minOccurs="1": нула се ПИШЕ, не се пропуска.
    xml.amount("income", row.getGrossAmount(), true);
    xml.amount("healthins", row.getInsuranceAmount(), false);
    xml.amount("tax", row.getTaxAmount(), false);
    xml.integer("persons", row.getPersonCount());
    xml.text("eiktransfrom", row.getPredecessorIdentifier());

    xml.close("rowsenum");
  }

  /**
   * Низовият код → цифра.
   *
   * <p>⚠️ {@code null} при невалиден вход, а не изключение и не подразбираща се нула: валидаторът
   * вече е отказал такъв ред: стигне ли дотук, пропуснатият елемент пада на СХЕМАТА с назована
   * причина, вместо тихо да се превърне в „0 — основни данни“.
   */
  private static Integer digit(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.db.BgArt73Para1Record;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ЗАПИСАНАТА СПРАВКА → СЪДЪРЖАНИЕ ЗА ФАЙЛА.
 *
 * <p><b>⚠️ ЧЕТЕ ЗАПИСАНИТЕ РЕДОВЕ, НЕ АГРЕГИРА НАНОВО — И ТОВА Е ИНВАРИАНТЪТ „ЕКРАН == ФАЙЛ“.</b>
 * {@link BgArt73Para1Aggregator} вече е слял регистъра веднъж и {@link
 * BgArt73Para1ReportService#build} е записал резултата като {@code BgArt73Para1Record}. Оттам
 * нататък човек може да е поправил ред на ръка — точно за това редовете са на екран. Втора обиколка
 * през агрегатора би върнала ДРУГО съдържание и файлът щеше да носи числа, каквито никой не е
 * виждал. Същият инвариант, който Блок Б пази с „report == TXT“ ({@code BgVatExportService}: една
 * колекция, едни и същи редове за отчета и за файла).
 *
 * <p><b>⚠️ РЕДЪТ НА РЕДОВЕТЕ Е ТОЗИ НА ЗАПИСВАНЕТО (по {@code id}), И ТОВА Е ОБЯВЕНА ГРАНИЦА.</b>
 * Коя подредба иска заповедта — по лице, по код на дохода, по код корекция — не се знае. Подреден
 * „разумно“ тук, файлът щеше да носи избор, който никой не е проверил срещу НАП. Затова подредбата
 * е ДЕТЕРМИНИРАНА (за да е възпроизводим един и същ файл), но нарочно не е СЪДЪРЖАТЕЛНА: {@code
 * o2m} колекцията на JPA няма гарантиран ред, а два еднакви пробега трябва да дават едни и същи
 * байтове.
 *
 * <p>Чист клас: без Guice и без хранилища, тоест проверим без Postgres.
 */
public final class BgArt73Para1FileCollector {

  private BgArt73Para1FileCollector() {}

  /**
   * Събира Част І и редовете на Част ІІ от ЗАПИСАНАТА справка.
   *
   * @param report справката; {@code null} дава празно съдържание, а не изключение — празнотата се
   *     назовава от валидатора, не се хвърля от събирача
   */
  public static BgArt73Para1FileContent collect(BgArt73Para1Report report) {
    BgArt73Para1FileContent content = new BgArt73Para1FileContent();
    if (report == null) {
      return content;
    }
    content.setTaxYear(report.getTaxYear());
    content.setPayerIdentifier(trim(report.getPayerIdentifier()));
    content.setPayerName(trim(report.getPayerName()));
    content.setContactPhone(trim(report.getContactPhone()));
    content.setContactEmail(trim(report.getContactEmail()));
    content.setRepresentativeName(trim(report.getRepresentativeName()));
    content.setRepresentativeIdentifier(trim(report.getRepresentativeIdentifier()));

    for (BgArt73Para1Record record : ordered(report.getRecordList())) {
      content.getRows().add(row(record));
    }
    return content;
  }

  /**
   * ЕДИН ЗАПИС → ЕДИН РЕД, колона по колона.
   *
   * <p>⚠️ Колони 2–9 се ПРЕПИСВАТ както са, включително празни. Указание за попълване т. 5 ги
   * оставя празни за сборните кодове 5031, 8141 и 820, а {@code BgArt73Para1ReportService#record}
   * вече ги е оставил празни. Изчислени наново тук („щом има лице, попълни имената“), те биха
   * попълнили колони, които образецът изрично оставя празни за тези кодове.
   */
  static BgArt73Para1FileRow row(BgArt73Para1Record record) {
    BgArt73Para1FileRow row = new BgArt73Para1FileRow();
    row.setCorrectionCode(trim(record.getCorrectionCode()));
    row.setForeignPerson(Boolean.TRUE.equals(record.getIsForeign()));
    row.setIdentifierType(trim(record.getIdentifierType()));
    row.setIdentifier(trim(record.getIdentifier()));
    row.setBirthDate(record.getBirthDate());
    row.setFirstName(trim(record.getFirstName()));
    row.setMiddleName(trim(record.getMiddleName()));
    row.setLastName(trim(record.getLastName()));
    row.setCountryCode(trim(record.getCountryCode()));
    row.setIncomeCode(codeOf(record.getIncomeType()));
    BgIncomeType incomeType = record.getIncomeType();
    row.setAggregateRow(incomeType != null && Boolean.TRUE.equals(incomeType.getIsAggregate()));
    row.setReportsPersonCount(
        incomeType != null && Boolean.TRUE.equals(incomeType.getReportsPersonCount()));

    // ⚠️ Сумите се пренасят САМО когато редът ги носи — указание за попълване т. 2. Axelor връща
    // ZERO и за непопълнено decimal, тоест „нула лева“ и „не се попълва“ изглеждат еднакво в
    // базата;
    // разликата се чете от колона 1 и се пази ТУК, преди оформлението да я е изгубило.
    if (row.carriesAmounts()) {
      row.setGrossAmount(record.getGrossAmount());
      row.setInsuranceAmount(record.getInsuranceAmount());
      row.setTaxAmount(record.getTaxAmount());
      row.setPersonCount(personCount(record));
      row.setPredecessorIdentifier(trim(record.getPredecessorIdentifier()));
    }
    return row;
  }

  /**
   * КОЛОНА 14 — <b>капанът на генерирания getter</b>.
   *
   * <p><b>⚠️ {@code BgArt73Para1Record.getPersonCount()} ВРЪЩА 0, А НЕ {@code null}.</b> Axelor
   * генерира {@code return personCount == null ? 0 : personCount} за всяко {@code Integer} поле —
   * включително за това, което домейнът нарочно остави БЕЗ {@code default}, точно за да може „не се
   * попълва“ да се различава от „нула лица“. Разликата се губи в getter-а, не в базата.
   *
   * <p>⚠️ Пренесена едно към едно, колона 14 щеше да получи „0“ на ВСЕКИ ред — тоест попълнена
   * колона там, където указание за попълване т. 5 я дава само на кодовете за брой лица. Затова
   * нулата се чете като празно: ред със СБОРЕН код и нула лица не съществува (виж javadoc-а на
   * полето в {@code BgArt73Para1Record.xml}).
   *
   * <p>TODO: сверѝ колона 14 срещу XSD-то/заповедта на НАП, когато дойдат. Три въпроса, на които
   * указанията НЕ отговарят и на които тук е даден РАБОТЕН отговор:
   *
   * <ol>
   *   <li>колоната е ПО РЕД ли е, или се сумира в ЗАГЛАВЕН запис — ако е хедър, преводът тук е на
   *       грешното ниво (същият капан като т. 9 на Приложение № 9 на НОИ, където броят няма
   *       платец);
   *   <li>ВАЛИДНА ли е стойност „0“ за кодовете, които колоната искат — ако НАП я приема, „празно“
   *       и „нула“ са РАЗЛИЧНИ твърдения и свеждането им до едно губи информация;
   *   <li>как се записва ПРАЗНОТО във файла — интервали, нула, или пропуснато поле.
   * </ol>
   */
  static Integer personCount(BgArt73Para1Record record) {
    int stored = record.getPersonCount();
    return stored > 0 ? stored : null;
  }

  /** Детерминирана подредба по {@code id}; незаписаните редове отиват най-отзад. */
  private static List<BgArt73Para1Record> ordered(List<BgArt73Para1Record> records) {
    List<BgArt73Para1Record> out = new ArrayList<>();
    if (records == null) {
      return out;
    }
    for (BgArt73Para1Record record : records) {
      if (record != null) {
        out.add(record);
      }
    }
    out.sort(Comparator.comparing(BgArt73Para1Record::getId, Comparator.nullsLast(Long::compare)));
    return out;
  }

  private static String codeOf(BgIncomeType incomeType) {
    return incomeType == null ? null : trim(incomeType.getCode());
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

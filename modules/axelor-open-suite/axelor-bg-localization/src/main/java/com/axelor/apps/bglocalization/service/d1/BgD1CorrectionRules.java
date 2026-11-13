package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Record;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * КОРЕКЦИЯТА И ЗАЛИЧАВАНЕТО — трите правила на чл. 9, като проверки. Чиста функция: без база, без
 * Guice.
 *
 * <p><b>⚠️ „К“ И „З“ НЕ СА ДВА РЕЖИМА НА ЕДНО НЕЩО.</b> Указание т. 1 ги описва различно и това
 * мени КОИ ПОЛЕТА се пълнят:
 *
 * <ul>
 *   <li><b>„К“</b> — „освен коригираните се попълват и ВСИЧКИ ОСТАНАЛИ реквизити с данните от
 *       подадената преди това декларация“. Тоест коригиращата декларация е ПЪЛНА: тя ЗАМЕСТВА
 *       предишната, а не я допълва. Подадена само с променените точки, тя изтрива останалите.
 *   <li><b>„З“</b> — „в тези случаи се попълват т. 1, 2, 3, 4, 5, 5.1, 6, 7 и 12“. ДЕВЕТ точки, и
 *       нищо повече: заличаването е твърдение „този запис не съществува“, а не запис с нули.
 * </ul>
 *
 * <p><b>⚠️ И ТРЕТОТО ПРАВИЛО Е ЗАБРАНА, НЕ ФОРМА.</b> Чл. 9 ал. 1 т. 1: „С код за корекция НЕ се
 * коригират данните … месец и година, код на задълженото лице, единният граждански номер … и код за
 * вид осигурен“. Ал. 3 дава изхода: „Подава се декларация с попълнен код за ЗАЛИЧАВАНЕ, след което
 * се подава НОВА декларация с коректни данни.“
 *
 * <p>⚠️ ЗАЩО ТОВА НЕ Е ПЕДАНТИЗЪМ: петте реквизита са КЛЮЧЪТ, по който НАП намира предишната
 * декларация. Коригираща декларация с променен ЕГН не поправя нищо — тя създава ВТОРИ запис за
 * второ лице, а първият остава жив. Тоест грешката не се вижда от подателя и се открива като
 * несъответствие месеци по-късно.
 */
public final class BgD1CorrectionRules {

  /**
   * ДЕВЕТТЕ ТОЧКИ на заличаващата декларация — дословно от указание т. 1.
   *
   * <p>⚠️ Т. 1 Е В СПИСЪКА, за разлика от списъка на код 28. Тук тя е ЧАСТ ОТ ТВЪРДЕНИЕТО: без
   * буквата „З“ записът не е заличаващ, а редовен. При код 28, обратно, наредбата изброява полетата
   * с ДАННИ и кодът за корекция стои извън тях.
   */
  public static final Set<String> DELETION_FIELDS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.asList("1", "2", "3", "4", "5", "5.1", "6", "7", "12")));

  /**
   * ПЕТТЕ РЕКВИЗИТА, КОИТО НЕ СЕ КОРИГИРАТ — чл. 9 ал. 1 т. 1.
   *
   * <p>Месец, година, код на задълженото лице, ЕГН/ЛН/ЛНЧ и код за вид осигурен. ⚠️ Т. 5.1 (кодът
   * за вида идентификатор) НЕ е сред тях, и това не е пропуск в преписа: нормата изброява САМА
   * ИДЕНТИФИКАЦИЯТА на лицето, не описанието ѝ. Смяна на 5.1 при непроменен номер е поправка на
   * метаданни и минава като корекция.
   */
  public static final Set<String> IMMUTABLE_FIELDS =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("2", "3", "4", "5", "12")));

  private BgD1CorrectionRules() {}

  /** Заличаващ ли е записът — буква „З“ (КИРИЛИЦА, U+0417). */
  public static boolean isDeletion(BgD1Record record) {
    return record != null
        && BgD1RecordBuilder.DELETION_Z.equals(trim(record.getT1CorrectionCode()));
  }

  /** Коригиращ ли е записът — буква „К“ (КИРИЛИЦА, U+041A). */
  public static boolean isCorrection(BgD1Record record) {
    return record != null
        && BgD1RecordBuilder.CORRECTION_K.equals(trim(record.getT1CorrectionCode()));
  }

  /** Редовен ли е записът — БЕЗ код за корекция. Празното е ЛИПСА на код, не трети код. */
  public static boolean isRegular(BgD1Record record) {
    String code = record == null ? null : trim(record.getT1CorrectionCode());
    return code == null || code.isEmpty();
  }

  /**
   * ПЕТТЕ КЛЮЧОВИ РЕКВИЗИТА на един запис, в реда на {@link #IMMUTABLE_FIELDS} — за сравнение.
   *
   * <p>⚠️ Връща СПИСЪК, не низ. Слепени в „05|2026|831919536|7501014567|01“, две различни разлики
   * дават едно и също съобщение („ключът се разминава“) и човек не знае КОЯ от петте е сменена — а
   * поправката е различна за всяка.
   */
  public static List<String> keyOf(BgD1Record record) {
    if (record == null) {
      return Arrays.asList(null, null, null, null, null);
    }
    return Arrays.asList(
        trim(record.getT2Month()),
        trim(record.getT3Year()),
        trim(record.getT4ObligedPersonCode()),
        trim(record.getT5PersonalId()),
        trim(record.getT12InsuredTypeCode()));
  }

  /**
   * ПРИЧИНАТА ЕДНА КОРЕКЦИЯ ДА НЕ Е ДОПУСТИМА, или {@code null}, когато е.
   *
   * <p>Сравнява петте реквизита на коригиращия запис срещу онези на предишния. Разминаване в който
   * и да е от тях значи, че по чл. 9 ал. 3 трябва ЗАЛИЧАВАНЕ и НОВА декларация — не корекция.
   *
   * @param corrected записът от ПОДАДЕНАТА преди това декларация. {@code null} значи „предишният не
   *     е намерен“ — и това е ОТДЕЛНА причина, не разминаване: корекция без предишна декларация е
   *     твърдение за нещо, което НАП не познава (чл. 9 ал. 4: „След ПРИЕМАНЕТО на някоя от
   *     декларациите по ал. 1 може да се подаде само съответната декларация с попълнен код…“).
   */
  public static String refuseCorrection(BgD1Record correcting, BgD1Record corrected) {
    if (correcting == null || !isCorrection(correcting)) {
      return null;
    }
    if (corrected == null) {
      return "записът носи код за корекция „К“, но предишна декларация за същото лице и месец НЕ Е"
          + " НАМЕРЕНА. По чл. 9 ал. 4 корекция се подава само СЛЕД приемането на декларация —"
          + " инак тя е твърдение за запис, който НАП не познава, и се приема като нов.";
    }

    List<String> now = keyOf(correcting);
    List<String> before = keyOf(corrected);
    List<String> names =
        Arrays.asList(
            "т. 2 (месец)",
            "т. 3 (година)",
            "т. 4 (код на задълженото лице)",
            "т. 5 (ЕГН/ЛН/ЛНЧ)",
            "т. 12 (вид осигурен)");

    StringBuilder changed = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      if (!equal(now.get(i), before.get(i))) {
        if (changed.length() > 0) {
          changed.append("; ");
        }
        changed
            .append(names.get(i))
            .append(": „")
            .append(before.get(i))
            .append("“ → „")
            .append(now.get(i))
            .append("“");
      }
    }
    if (changed.length() == 0) {
      return null;
    }
    return "чл. 9 ал. 1 т. 1: с код за корекция НЕ се коригират месец и година, код на задълженото"
        + " лице, ЕГН/ЛН/ЛНЧ и код за вид осигурен. Разминава се: "
        + changed
        + ". ⚠️ ПО ЧЛ. 9 АЛ. 3 СЕ ПОДАВАТ ДВЕ ДЕКЛАРАЦИИ: първо ЗАЛИЧАВАЩА (код „З“) за стария"
        + " запис, после НОВА с коректните данни. Корекция с променен ключ НЕ поправя нищо — тя"
        + " създава ВТОРИ запис, а първият остава жив, и разминаването се открива от НАП месеци"
        + " по-късно.";
  }

  /**
   * ПРИЧИНАТА ЕДИН ФАЙЛ ДА НЕ Е ДОПУСТИМ, или {@code null}, когато е.
   *
   * <p>„В един файл не се смесват код 0 и код 1 за същото лице (0 и 8 се допускат)“ — от заповедта
   * по чл. 8 ал. 5.
   *
   * <p>⚠️ И ЗАЩО 0 С 8 СЕ ДОПУСКА, А 0 С 1 — НЕ. Заличаване плюс нова декларация е точно
   * ПРЕДПИСАНИЯТ от чл. 9 ал. 3 път: двете твърдения не си противоречат, защото се отнасят до
   * различни състояния на записа. Редовна плюс коригираща, обратно, са две твърдения за ЕДИН запис
   * — и редът, в който НАП ще ги приложи, не е определен от файла.
   *
   * @param records записите на ЕДНА декларация, тоест на един файл
   */
  public static String refuseMixedCodes(List<BgD1Record> records) {
    if (records == null) {
      return null;
    }
    Set<String> regular = new LinkedHashSet<>();
    Set<String> correcting = new LinkedHashSet<>();
    for (BgD1Record record : records) {
      if (record == null) {
        continue;
      }
      String person = trim(record.getT5PersonalId());
      if (person == null || person.isEmpty()) {
        continue;
      }
      if (isRegular(record)) {
        regular.add(person);
      } else if (isCorrection(record)) {
        correcting.add(person);
      }
    }
    Set<String> both = new LinkedHashSet<>(regular);
    both.retainAll(correcting);
    if (both.isEmpty()) {
      return null;
    }
    return "в един файл не се смесват редовна (код 0) и коригираща (код 1) декларация за СЪЩОТО"
        + " лице. Разминават се: "
        + String.join(", ", both)
        + ". ⚠️ Двете са две твърдения за ЕДИН запис, а редът, в който ще се приложат, не е"
        + " определен от файла. Заличаване (код 8) с редовна, обратно, СЕ ДОПУСКА — то е точно"
        + " предписаният от чл. 9 ал. 3 път.";
  }

  private static boolean equal(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}

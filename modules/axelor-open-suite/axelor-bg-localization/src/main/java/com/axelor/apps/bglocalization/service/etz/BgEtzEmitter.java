package com.axelor.apps.bglocalization.service.etz;

/**
 * ЕМИТЕРЪТ — от сглобения файл към <b>байтове в WINDOWS-1251</b>.
 *
 * <p>Пише точно двата корена на схемите: {@code etzemploy} (ЕИК + {@code contract}/{@code rowenum})
 * и {@code etz123employer} ({@code formereik} + {@code eik} + {@code contract}/{@code rowenum}).
 *
 * <p><b>⚠️ РЕДЪТ НА ЕЛЕМЕНТИТЕ ИДВА ОТ КАТАЛОЗИТЕ, НЕ ОТ ПОДРЕДБАТА НА MAP-А.</b> Схемата ползва
 * {@code xs:all}, тоест разбъркан ред би минал ДНЕС. Емитерът все пак изписва в реда на
 * приложението: {@code xs:all} е избор на тази редакция на схемата, а не обещание, и файлът се чете
 * и от хора.
 *
 * <p><b>⚠️ ПРАЗЕН ЕЛЕМЕНТ НЕ СЕ ПИШЕ, И ТУК ТОВА Е ОБРАТНОТО НА НОИ.</b> При Приложение № 9 всички
 * тагове присъстват винаги, а неотносимите са празни. Тук почти всичко е {@code minOccurs="0"} и
 * {@code <begindate></begindate>} НЕ минава срещу {@code xs:date} — празният низ не е валидна дата.
 * Затова непопълненото поле просто отсъства.
 *
 * <p><b>⚠️ ЕМИТЕРЪТ НЕ ПРОВЕРЯВА ЗАДЪЛЖИТЕЛНОСТ.</b> Тя се пази ПРЕДИ него, от {@link
 * BgEtzMandatory}, и отделно от схемата СЛЕД него. Трети прочит тук би се разминал с единия от
 * двата, без нито един тест да падне.
 */
public class BgEtzEmitter {

  /** Байтовете на един ЕТЗ файл — WINDOWS-1251. */
  public byte[] emit(BgEtzFile file) {
    if (file == null) {
      throw new IllegalArgumentException("няма файл за емисия");
    }
    if (file.size() == 0) {
      // ⚠️ ОТКАЗ, А НЕ ФАЙЛ С НУЛА РЕДА. Схемата иска maxOccurs="unbounded" при minOccurs по
      // подразбиране 1 — тоест празният contract пада на структурна проверка, но чак при НАП.
      throw new IllegalStateException(
          "ЕТЗ файлът няма нито един ред — няма какво да се подаде. Схемата иска поне един"
              + " <rowenum>.");
    }
    BgEtzXmlWriter writer = new BgEtzXmlWriter();
    return file.getKind() == BgEtzEventKind.EMPLOYER_CHANGE
        ? emit123(file, writer)
        : emit62(file, writer);
  }

  /** {@code etzemploy} — сключване, изменение, прекратяване. */
  protected byte[] emit62(BgEtzFile file, BgEtzXmlWriter writer) {
    String root = BgEtzXsd.Schema.EMPLOY.getRootElement();
    writer.open(root);
    writer.leaf(BgEtz62Field.EIK.getTag(), file.getHeader(BgEtz62Field.EIK.getTag()));
    writer.open("contract");
    for (BgEtzRecord row : file.getRows()) {
      writer.open("rowenum");
      for (BgEtz62Field field : BgEtz62Field.values()) {
        if (field == BgEtz62Field.EIK) {
          continue; // заглавен реквизит — вече е изписан
        }
        writeIfPresent(writer, field.getTag(), row.get(field.getTag()));
      }
      writer.close("rowenum");
    }
    writer.close("contract");
    writer.close(root);
    return writer.toBytes();
  }

  /** {@code etz123employer} — промяна на работодател. */
  protected byte[] emit123(BgEtzFile file, BgEtzXmlWriter writer) {
    String root = BgEtzXsd.Schema.EMPLOYER_CHANGE.getRootElement();
    writer.open(root);
    // ⚠️ РЕДЪТ Е ПРЕДИШЕН, ПОСЛЕ НОВ. Двата ЕИК са съседни елементи от един и същ тип и
    // разменени, файлът остава валиден по схема — и вписва прехвърлянето в обратната посока.
    writer.leaf(BgEtz123Field.FORMEREIK.getTag(), file.getHeader(BgEtz123Field.FORMEREIK.getTag()));
    writer.leaf(BgEtz123Field.EIK.getTag(), file.getHeader(BgEtz123Field.EIK.getTag()));
    writer.open("contract");
    for (BgEtzRecord row : file.getRows()) {
      writer.open("rowenum");
      for (BgEtz123Field field : BgEtz123Field.values()) {
        if (field == BgEtz123Field.EIK || field == BgEtz123Field.FORMEREIK) {
          continue;
        }
        writeIfPresent(writer, field.getTag(), row.get(field.getTag()));
      }
      writer.close("rowenum");
    }
    writer.close("contract");
    writer.close(root);
    return writer.toBytes();
  }

  private static void writeIfPresent(BgEtzXmlWriter writer, String tag, String value) {
    if (value != null && !value.isEmpty()) {
      writer.leaf(tag, value);
    }
  }
}

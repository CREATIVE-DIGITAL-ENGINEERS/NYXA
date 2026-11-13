package com.axelor.apps.bglocalization.service.art73.adapter;

import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileAnnual;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileLayout;
import com.google.inject.Singleton;

/**
 * ФИЗИЧЕСКИЯТ ФОРМАТ НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 6 — XML по схемата {@code dec736}.
 *
 * <p>Утвърден със <b>{@link BgArt73Para6Xsd#ORDER заповед № З-ЦУ-1877/05.12.2019 г.}</b> на
 * изпълнителния директор на НАП; схемата е закована в {@link BgArt73Para6Xsd}.
 *
 * <p><b>⚠️ ВЛОЖЕНА СТРУКТУРА, А НЕ ПЛОСЪК СПИСЪК — ЕДИНСТВЕНАТА ИСТИНСКА РАЗЛИКА С АЛ. 1.</b> При
 * {@code dec731} записът е ПЛАЩАНЕ и лицето се повтаря; тук {@code part2/rowsenum} е ЛИЦЕТО, а
 * плащанията му висят в {@code incomedata/incomerows/rowsenum}. Писачът поема това без промяна —
 * {@link BgArt73XmlWriter} държи брояч на нивото, тоест вложеността е въпрос на реда на
 * извикванията, не на нова машинария.
 *
 * <p><b>⚠️ {@code incomedata} СЕ ПРОПУСКА ЦЯЛ, КОГАТО ЛИЦЕТО НЯМА ДОХОДИ.</b> Схемата го дава като
 * {@code minOccurs="0"}, но вътре в него {@code sumtaxdeducted} е задължителен. Изписан празен,
 * блокът щеше да падне на схемата заради собственото си задължително дете — тоест „няма доходи“ се
 * изразява с ЛИПСА на блока, а не с празен блок.
 *
 * <p><b>⚠️ {@code taxbase49} Е БЛОК НА ОСНОВНИЯ РАБОТОДАТЕЛ.</b> Пишем го само когато събирачът е
 * намерил записан годишен ред. Дали флагът {@code ismainemployer} и наличието на блока си
 * съответстват е въпрос на ВАЛИДАТОРА — тук той само се изписва такъв, какъвто е дошъл.
 *
 * <p><b>⚠️ {@code isterm} СЕ ПИШЕ КАТО 0, А НЕ СЕ ПРОПУСКА</b> — същият довод като при ал. 1: този
 * модул не строи справки при заличаване/прекратяване по чл. 162 ЗКПО, тоест 0 е ВЯРНОТО твърдение.
 */
@Singleton
public class BgArt73Para6FileLayout implements BgDeclarationFileLayout<BgArt73Para6FileContent> {

  public static final String FORM_NAME = "справка по чл. 73, ал. 6 ЗДДФЛ (трудови доходи)";

  /** Този модул не подава при заличаване/прекратяване по чл. 162 ЗКПО. */
  private static final int NOT_A_TERMINATION_FILING = 0;

  @Override
  public String formName() {
    return FORM_NAME;
  }

  /** ⚠️ Форматът Е потвърден — заповед {@link BgArt73Para6Xsd#ORDER}. */
  @Override
  public boolean isConfirmed() {
    return true;
  }

  @Override
  public String fileName() {
    return BgArt73Para6Xsd.FILE_NAME;
  }

  @Override
  public String encoding() {
    return BgArt73Para6Xsd.ENCODING;
  }

  @Override
  public String document(BgArt73Para6FileContent content) {
    requireConfirmed();
    BgArt73XmlWriter xml = new BgArt73XmlWriter(BgArt73Para6Xsd.ENCODING);

    xml.open(BgArt73Para6Xsd.ROOT_ELEMENT);
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
    for (BgArt73Para6FilePersonRow person : content.getPersons()) {
      writePerson(xml, person);
    }
    xml.close("part2");

    xml.close(BgArt73Para6Xsd.ROOT_ELEMENT);
    return xml.document();
  }

  /**
   * ЕДНО ЛИЦЕ — {@code part2/rowsenum}.
   *
   * <p>⚠️ Схемата ползва {@code xs:all}, тоест редът на елементите е без значение ЗА НЕЯ. Тук той е
   * редът на КОЛОНИТЕ на образеца, защото файлът се чете и от хора, които го сверяват срещу
   * хартията.
   */
  private void writePerson(BgArt73XmlWriter xml, BgArt73Para6FilePersonRow person) {
    xml.open("rowsenum");

    xml.integer("correctioncode", digit(person.getCorrectionCode()));
    xml.text("firstname", person.getFirstName());
    xml.text("secondname", person.getMiddleName());
    xml.text("thirdname", person.getLastName());
    xml.integer("identtype", digit(person.getIdentifierType()));
    xml.text("ident", person.getIdentifier());
    xml.integer("ismainemployer", person.isMainEmployer() ? 1 : 0);

    if (person.hasIncomeData()) {
      writeIncomeData(xml, person);
    }

    xml.close("rowsenum");
  }

  /** {@code incomedata} — редовете доход, двете основи по чл. 25, блокът по чл. 49 и сборът. */
  private void writeIncomeData(BgArt73XmlWriter xml, BgArt73Para6FilePersonRow person) {
    xml.open("incomedata");

    xml.open("incomerows");
    for (BgArt73Para6FileIncomeRow row : person.getIncomeRows()) {
      writeIncomeRow(xml, row);
    }
    xml.close("incomerows");

    xml.amount("taxbase251", person.getTaxBaseArt25Para1(), false);
    xml.amount("taxbase253", person.getTaxBaseArt25Para3(), false);

    if (person.getAnnual() != null) {
      writeAnnual(xml, person.getAnnual());
    }

    // ⚠️ minOccurs="1" вътре в блока: нула се ПИШЕ, не се пропуска.
    xml.amount("sumtaxdeducted", person.getTotalTaxWithheld(), true);

    xml.close("incomedata");
  }

  /** Един ред доход — {@code incomedata/incomerows/rowsenum}. */
  private void writeIncomeRow(BgArt73XmlWriter xml, BgArt73Para6FileIncomeRow row) {
    xml.open("rowsenum");

    xml.text("incomecode", row.getIncomeCode());
    xml.text("employereik", row.getEmployerIdentifier());
    xml.text("employername", row.getEmployerName());
    xml.amount("income", row.getIncome(), true);
    xml.amount("healthinsbg", row.getInsuranceBg(), false);
    xml.amount("healthinsforeign", row.getInsuranceForeign(), false);
    xml.amount("advancetax", row.getAdvanceTax(), false);

    // ⚠️ taxreductiondisabled/ins/healthins НЕ се пишат: „Ред и формат“, редове 8/9/10 = N
    // (незадължителни за прием). Модулът не ги събира — виж BgArt73Para6FileIncomeRow.

    xml.text("eiktransfrom", row.getPredecessorIdentifier());

    xml.close("rowsenum");
  }

  /** Блокът за годишното изравняване — {@code incomedata/taxbase49}. */
  private void writeAnnual(BgArt73XmlWriter xml, BgArt73Para6FileAnnual annual) {
    xml.open("taxbase49");

    xml.amount("taxbase", annual.getTaxBase(), false);
    xml.amount("taxreductiondisabled", annual.getReliefDisability(), false);
    xml.amount("taxreductionins", annual.getReliefVoluntaryPension(), false);
    xml.amount("taxreductionhealthins", annual.getReliefVoluntaryHealthLife(), false);
    xml.amount("taxreductionretire", annual.getReliefServiceYears(), false);
    xml.amount("taxreductiondonation01", annual.getReliefDonation01(), false);
    xml.amount("taxreductiondonation02", annual.getReliefDonation02(), false);
    xml.amount("taxreductiondonation03", annual.getReliefDonation03(), false);
    xml.amount("taxreductionchildren", annual.getReliefChildren(), false);
    xml.amount("taxreductionchildrendisab", annual.getReliefDisabledChildren(), false);
    xml.amount("taxbase491", annual.getTaxBaseAfterReliefs(), false);
    xml.amount("tax", annual.getTax(), false);
    // ⚠️ ЕДИНСТВЕНОТО поле, което приема отрицателна стойност — заповедта, изисквания т. 6.
    xml.amount("diff18", annual.getSettlement(), false);
    xml.amount("sum19deducted", annual.getWithheldByJanuary(), false);
    xml.amount("sum19refund", annual.getRefundedByJanuary(), false);

    xml.close("taxbase49");
  }

  /**
   * Низовият код → цифра.
   *
   * <p>⚠️ {@code null} при невалиден вход, а не изключение и не подразбираща се нула: валидаторът
   * вече е отказал такъв ред; стигне ли дотук, пропуснатият задължителен елемент пада на СХЕМАТА с
   * назована причина, вместо тихо да се превърне в „0“.
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

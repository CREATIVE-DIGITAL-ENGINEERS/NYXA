package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import java.time.LocalDate;

/**
 * Поредицата на ДДС протоколите за едно дружество — същата като реда {@code bgVatProtocol} в
 * шаблона BG_COA ({@code base_sequence.csv} / {@code base_sequenceVersion.csv} в axelor-account).
 * Чиста функция: строи, не записва.
 *
 * <p>10 цифри с водещи нули, без префикс и суфикс, без годишно и месечно нулиране. Единствената
 * версия започва на 01.01.2000, както версиите в шаблона: поредица без нулиране има ЕДНА отворена
 * версия. Започне ли от днес, документ с по-ранна дата не намира версия и Axelor създава нова от
 * номер 1 — виж бележката към {@code base_sequenceVersion.csv} в {@code chart-config.xml}.
 *
 * <p><b>importId е този на шаблона</b> ({@link #importIdFor}). Шаблонът търси поредицата по него и
 * по дружеството ({@code chart-config.xml}). Затова, ако BG_COA се приложи по-късно върху
 * дружество, чиято поредица е създадена при стартиране, той я обновява, а не прави втора. Две
 * поредици с един код биха повторили номерата на протоколите. Версията и следващият номер остават,
 * защото {@code ImportSequence} взема съществуващата версия за днешната дата.
 */
public final class BgVatProtocolSequence {

  public static final String NAME = "ДДС протокол (ЗДДС чл. 117 ал. 2 т. 1)";
  public static final int PADDING = 10;
  public static final LocalDate VERSION_START = LocalDate.of(2000, 1, 1);

  /** {@code importId} на реда в {@code BG_COA/base_sequence.csv}. */
  public static final String TEMPLATE_IMPORT_ID = "145";

  private BgVatProtocolSequence() {}

  /**
   * {@code importId}, който шаблонът дава на поредицата на дружеството: {@code _companyCode + '-' +
   * importId} в {@code chart-config.xml}. {@code Company.code} е задължителен.
   */
  public static String importIdFor(Company company) {
    return company.getCode() == null ? null : company.getCode() + "-" + TEMPLATE_IMPORT_ID;
  }

  public static Sequence newFor(Company company) {
    Sequence sequence = new Sequence();
    sequence.setCompany(company);
    sequence.setImportId(importIdFor(company));
    sequence.setName(NAME);
    sequence.setCodeSelect(BgVatProtocolNumbering.SEQUENCE_CODE);
    sequence.setPadding(PADDING);
    sequence.setPrefixe(null);
    sequence.setSuffixe(null);
    sequence.setToBeAdded(1);
    sequence.setYearlyResetOk(false);
    sequence.setMonthlyResetOk(false);
    SequenceVersion version = new SequenceVersion();
    version.setStartDate(VERSION_START);
    version.setNextNum(1L);
    sequence.addSequenceVersionListItem(version);
    return sequence;
  }
}

package com.axelor.apps.bglocalization.service.fatca;

/**
 * СТОЙНОСТИТЕ НА FATCA СЕЛЕКЦИИТЕ, назовани веднъж ({@code bg.fatca.*.select} в {@code
 * Selects.xml}).
 *
 * <p>Потокът на отчета: {@link #DRAFT} → {@link #GENERATED} (има файл; „Върни в чернова“ го
 * забравя) → {@link #SUBMITTED} (входящ номер от портала; краен — корекции няма).
 */
public final class BgFatcaStatus {

  // bg.fatca.report.status.select
  public static final String DRAFT = "DRAFT";
  public static final String GENERATED = "GENERATED";
  public static final String SUBMITTED = "SUBMITTED";

  // bg.fatca.entity.status.select — само REPORTING_FI подава отчет.
  public static final String REPORTING_FI = "REPORTING_FI";
  public static final String ACTIVE_NFFE = "ACTIVE_NFFE";
  public static final String PASSIVE_NFFE = "PASSIVE_NFFE";
  public static final String EXEMPT = "EXEMPT";

  // bg.fatca.holder.type.select
  public static final String INDIVIDUAL = "INDIVIDUAL";
  public static final String ORGANISATION = "ORGANISATION";

  // bg.fatca.acct.holder.type.select (IRS Pub 5124)
  /** Пасивно НФЧП със съществени американски собственици — иска SubstantialOwner. */
  public static final String PASSIVE_NFFE_WITH_OWNERS = "FATCA102";

  /** Определено американско лице — без US TIN такава сметка няма смисъл. */
  public static final String SPECIFIED_US_PERSON = "FATCA104";

  private BgFatcaStatus() {}
}

package com.axelor.apps.bglocalization.service.fixedasset;

/**
 * Изключенията по чл. 66 ал. 3 ЗКПО от преобразуването при отписване на актив.
 *
 * <p>Огледални на {@code bg.asset.art66.exception.select} в {@code views/Selects.xml}. Държат се
 * като константи по същия довод, по който базата държи {@code
 * FixedAssetRepository.TRANSFERED_REASON_*}: сгрешен низ не се обажда при компилация, а тихо отменя
 * законно преобразуване.
 */
public final class BgArt66Exception {

  /** Няма изключение — прилагат се ал. 1 и ал. 2. */
  public static final String NONE = "NONE";

  /** Ал. 3 т. 1 — непризнати разходи от липси на активи и свързани с тях вземания. */
  public static final String SHORTAGE = "SHORTAGE";

  /** Ал. 3 т. 2 — отписване за сметка на собствения капитал. */
  public static final String OWN_CAPITAL = "OWN_CAPITAL";

  /** Ал. 3 т. 3 — отписване по реда на чл. 60 ал. 6. */
  public static final String ART60_AL6 = "ART60_AL6";

  /**
   * Ал. 3 т. 4 — преобразуване на дружества и преустройство на кооперации по глава деветнадесета,
   * раздели II и III. Единственото изключение БЕЗ условие за съотношението ДБС/СБС.
   */
  public static final String REORGANISATION = "REORGANISATION";

  private BgArt66Exception() {}
}

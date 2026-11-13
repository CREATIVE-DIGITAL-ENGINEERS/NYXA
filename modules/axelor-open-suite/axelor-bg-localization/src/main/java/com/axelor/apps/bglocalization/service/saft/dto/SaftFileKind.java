package com.axelor.apps.bglocalization.service.saft.dto;

/**
 * ВИДЪТ НА ОДИТНИЯ ФАЙЛ — изборът в корена на схемата (Annual | Monthly | OnDemand) и S.H.11.
 *
 * <p><b>⚠️ ВИДЪТ СЕ ПОЗНАВА ПО ИМЕТО НА ФАЙЛА, НЕ ПО HeaderComment.</b> QA с. 73: S.H.11 е
 * незадължителен, а електронната услуга различава месечен, годишен и при поискване по конвенцията
 * на Прил. 3 т. I.2 — виж {@code BgSaftFileName}.
 *
 * <p><b>⚠️ „A“ Е ЛАТИНСКО.</b> Бележката към S.H.11 в книгата пише годишния с КИРИЛСКО „А“
 * (U+0410), а M и D — с латински; годишният образец на НАП е с латинско. Решено: латинско, като
 * другите два кода и като образеца (енумерацията в схемата е закоментирана, тоест приема и двете).
 */
public enum SaftFileKind {

  /** Месечният файл — {@code ЕИК_MM_YYYY.xml}. */
  MONTHLY("M"),

  /** Годишният файл: наличности и движение на дълготрайни активи — {@code ЕИК_YYYY.xml}. */
  ANNUAL("A"),

  /**
   * Файлът при поискване: наличности и движение на запаси — {@code ЕИК_DD_MM_YYYY_DD_MM_YYYY.xml}.
   */
  ON_DEMAND("D");

  private final String headerComment;

  SaftFileKind(String headerComment) {
    this.headerComment = headerComment;
  }

  /** S.H.11 HeaderComment — M / A / D. */
  public String getHeaderComment() {
    return headerComment;
  }
}

package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Xsd;
import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.List;

/**
 * СТРУКТУРНАТА ПРОВЕРКА СРЕЩУ СХЕМИТЕ НА ЧЛ. 73 — офлайн, срещу закованите копия.
 *
 * <p><b>⚠️ ТУК ЖИВЕЕ САМО ПРЕФИКСЪТ „ART73“, А ПРОВЕРКАТА Е В {@link BgXsdValidator}.</b>
 * Реализацията — втвърденият четец, събирачът на грешки, таванът на находките — беше написана тук и
 * обслужваше ал. 1 и ал. 6 през параметър за схема. SAF-T иска СЪЩАТА проверка, но находките му
 * стигат до екрана на човека като {@code SaftValidationIssue}: закованият код {@code
 * ART73_SCHEMA_ERROR} в доклад за одитен файл твърди грешен произход на грешката и праща търсенето
 * в грешната заповед. Затова реализацията слезе в общия пакет с ПРЕФИКС на кода, а тук остана
 * вратата — за да не се промени нито един извикващ на чл. 73.
 *
 * <p>За доводите защо се валидират БАЙТОВЕ, защо схемата е слаб пазач и защо втвърдяването е на
 * четеца, а не през {@code ACCESS_EXTERNAL_*} — виж класовия javadoc на {@link BgXsdValidator}.
 */
public final class BgArt73XsdValidator {

  /** Префиксът на кодовете за грешка на този формат. */
  private static final String CODE_PREFIX = "ART73";

  public static final String SCHEMA_MISSING = BgXsdValidator.schemaMissingCode(CODE_PREFIX);
  public static final String SCHEMA_ERROR = BgXsdValidator.schemaErrorCode(CODE_PREFIX);
  public static final String SCHEMA_CHECK_FAILED =
      BgXsdValidator.schemaCheckFailedCode(CODE_PREFIX);

  private BgArt73XsdValidator() {}

  /**
   * Структурните находки за подадените байтове СРЕЩУ СХЕМАТА НА АЛ. 1; празен списък значи „минава
   * схемата“.
   *
   * <p>⚠️ Запазено за извикващите на ал. 1 — делегира на {@link #validate(byte[], String, String)}.
   */
  public static List<BgDeclarationFileFinding> validate(byte[] xml) {
    return validate(xml, BgArt73Xsd.RESOURCE, BgArt73Xsd.describe());
  }

  /**
   * Структурните находки срещу ПОСОЧЕНАТА схема на чл. 73.
   *
   * <p><b>⚠️ СХЕМАТА Е ПАРАМЕТЪР, А НЕ ВТОРИ КЛАС.</b> Ал. 1 ({@code dec731}) и ал. 6 ({@code
   * dec736}) са различни схеми с различни заповеди, но проверката е една и съща.
   *
   * @param xml байтовете, които тръгват към НАП — НЕ низ
   * @param resource пътят в classpath-а до схемата ({@code BgArt73Xsd#RESOURCE} или {@code
   *     BgArt73Para6Xsd#RESOURCE})
   * @param describe едноредовото описание на схемата за съобщенията при отказ
   */
  public static List<BgDeclarationFileFinding> validate(
      byte[] xml, String resource, String describe) {
    return BgXsdValidator.validate(xml, resource, describe, CODE_PREFIX);
  }
}

package com.axelor.apps.bglocalization.service.vat.adapter;

import java.math.BigDecimal;
import java.util.List;

/**
 * „ВИД НА СТОКАТА / ОБХВАТ И ВИД НА УСЛУГАТА“ — поле 02-08 на PRODAGBI и 03-10 на POKUPKI (позиции
 * 138–167, Символен(30)). Чиста функция.
 *
 * <p>Празното поле спира файла на portal.nra.bg („Моля изберете път към файлове …“, проверено на
 * 11.11.2026): ръчно попълнено копие на същите файлове мина „Формиране на декларация“, тъй че
 * полето НИКОГА не остава празно.
 *
 * <p>Правилата, по ред:
 *
 * <ol>
 *   <li>фактура — редът с най-голяма основа (по абсолютна стойност; при равенство първият) измежду
 *       редовете с продукт: името на продуктовото му семейство; без семейство — {@value #GOODS} за
 *       складируем/консуматив, {@value #SERVICES} за услуга;
 *   <li>статия без фактура (протокол) — по операцията: ВОП, внос по чл. 167а и чл. 163а са стоки;
 *       услуга от ЕС / извън ЕС — услуги;
 *   <li>иначе — {@value #FALLBACK}.
 * </ol>
 *
 * Резултатът е съкратен до {@value #WIDTH} знака.
 */
public final class BgVatSubject {

  /** Ширината на полето в дневниците. */
  public static final int WIDTH = 30;

  public static final String GOODS = "стоки";
  public static final String SERVICES = "услуги";

  /** Последната възможност — документ без продукт и без операция, която да казва какво е. */
  public static final String FALLBACK = "стоки и услуги";

  /** {@code Product.productTypeSelect} на услугата ({@code product.product.type.select}). */
  public static final String PRODUCT_TYPE_SERVICE = "service";

  private BgVatSubject() {}

  /** Един ред на фактурата: основа, семейство на продукта, вид на продукта. */
  public static final class Line {
    final BigDecimal base;
    final boolean hasProduct;
    final String familyName;
    final String productType;

    public Line(BigDecimal base, boolean hasProduct, String familyName, String productType) {
      this.base = base;
      this.hasProduct = hasProduct;
      this.familyName = familyName;
      this.productType = productType;
    }
  }

  /**
   * Видът по редовете на фактурата, или {@code null}, когато нито един ред няма продукт.
   *
   * @param lines редовете в реда на фактурата
   */
  public static String fromLines(List<Line> lines) {
    if (lines == null) {
      return null;
    }
    Line largest = null;
    for (Line line : lines) {
      if (line == null || !line.hasProduct) {
        continue;
      }
      if (largest == null || abs(line.base).compareTo(abs(largest.base)) > 0) {
        largest = line;
      }
    }
    if (largest == null) {
      return null;
    }
    if (!isBlank(largest.familyName)) {
      return cut(largest.familyName);
    }
    return PRODUCT_TYPE_SERVICE.equals(trim(largest.productType)) ? SERVICES : GOODS;
  }

  /** Видът по ДДС операцията на статия без фактура, или {@code null}, когато тя не го казва. */
  public static String forOperation(String operation) {
    String op = trim(operation);
    if (op == null) {
      return null;
    }
    switch (op) {
      case BgVatMoveOperationRouting.OPERATION_VOP:
      case BgVatMoveOperationRouting.OPERATION_ART163A:
      case "IMPORT_167A":
        return GOODS;
      case BgVatMoveOperationRouting.OPERATION_SERVICE_EU:
      case BgVatMoveOperationRouting.OPERATION_SERVICE_NONEU:
        return SERVICES;
      default:
        return null;
    }
  }

  /**
   * Стойността за файла: подадената (съкратена до {@value #WIDTH}), а празна — {@value #FALLBACK}.
   */
  public static String orFallback(String subject) {
    return isBlank(subject) ? FALLBACK : cut(subject);
  }

  private static String cut(String value) {
    String v = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    return v.length() > WIDTH ? v.substring(0, WIDTH).trim() : v;
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}

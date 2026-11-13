package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.Input;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Сглобява входа за {@link BgArt79CorrectionRules} от фактите на АКТИВА, с подразбиране от
 * покупната фактура — чиста функция.
 *
 * <p><b>Две лица, едно правило.</b> {@link #inputFor} ЧЕТЕ наживо (за отписването), а {@link
 * #applyInvoiceDefaults} и {@link #applyCoefficientDefault} ЗАПИСВАТ същото подразбиране върху
 * актива (за годишния поток, чийто филтър пита базата). Преводът от фактурата живее на едно място,
 * за да не могат двата пътя да се разминат.
 *
 * <p><b>Подразбирането е удобство, не източник на истина.</b> {@code FixedAsset.invoiceLine} го има
 * само когато активът е породен от покупна фактура. Ръчно заведеният актив го няма, а сграда,
 * изградена от МНОГО фактури, няма един ред, който да носи цялото НДДС. Затова стойността, въведена
 * върху актива, ВИНАГИ бие фактурата, а фактурата се чете само за празно поле.
 *
 * <p>⚠️ Генерираните getter-и на {@code Integer} връщат 0 за незададено, не {@code null} — затова
 * „няма година“ се чете като {@code == 0}, а не като липса. Същото важи и за {@code BigDecimal} с
 * {@code ZERO}: виж {@code BgDefaultedGetterTest}.
 */
public final class BgArt79Acquisition {

  /** {@code InvoiceLine.bgVatCreditType} — „1“ пълен, „2“ частичен, „3“ без право/без данък. */
  private static final String INVOICE_CREDIT_FULL = "1";

  private static final String INVOICE_CREDIT_PARTIAL = "2";
  private static final String INVOICE_CREDIT_NONE = "3";

  private BgArt79Acquisition() {}

  /**
   * Входът за формулата, или {@code null}, когато активът не носи достатъчно факти.
   *
   * <p>Недостатъчно значи липсваща класификация (коя точка на ал. 3) или липсваща година на
   * упражняване на кредита — без тях няма нито знаменател, нито срок. Връща се {@code null}, а не
   * догадка: сгрешената точка умножава данъка по четири, а сгрешената година мести целия срок.
   */
  public static Input inputFor(FixedAsset asset, LocalDate eventDate) {
    if (asset == null || eventDate == null) {
      return null;
    }
    AssetKind kind = kindOf(asset);
    int creditYear = creditYearOf(asset);
    if (kind == null || creditYear == 0) {
      return null;
    }
    Integer firstUse = asset.getBgVatFirstUseYear() == 0 ? null : asset.getBgVatFirstUseYear();
    return new Input(
        kind,
        creditTypeOf(asset),
        vatChargedOf(asset),
        creditYear,
        firstUse,
        eventDate.getYear(),
        nullIfZero(asset.getBgVatCoefficientK0()),
        nullIfZero(asset.getBgVatProportionNid0()));
  }

  /** Класификацията по ал. 3 — изрично поле, никога изведено от категорията по чл. 55 ЗКПО. */
  public static AssetKind kindOf(FixedAsset asset) {
    String kind = asset.getBgVatAssetKindSelect();
    if (kind == null || kind.trim().isEmpty()) {
      return null;
    }
    try {
      return AssetKind.valueOf(kind.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * НДДС — от актива, иначе от покупната фактура като разликата „с данък минус без данък“.
   *
   * <p>Фактурният ред носи {@code inTaxTotal} и {@code exTaxTotal}; начисленият данък е разликата
   * им. Това е БРУТНИЯТ данък, тоест точно каквото ал. 3 нарича НДДС — К₀ влиза отделно.
   */
  static BigDecimal vatChargedOf(FixedAsset asset) {
    BigDecimal declared = asset.getBgVatChargedOnAcquisition();
    if (declared.signum() != 0) {
      return declared;
    }
    InvoiceLine line = asset.getInvoiceLine();
    if (line == null) {
      return BigDecimal.ZERO;
    }
    return line.getInTaxTotal().subtract(line.getExTaxTotal());
  }

  /**
   * Годината на упражняване на кредита — от актива, иначе годината на покупната фактура.
   *
   * <p>⚠️ Датата на фактурата е ПОДРАЗБИРАНЕ, не правило. Чл. 72 ал. 1 позволява правото да се
   * упражни в периода на възникване или в един от следващите 12, тъй че фактура от декември може
   * законно да е ползвана през следващата година — и тогава целият 20-/5-годишен срок се мести.
   */
  static int creditYearOf(FixedAsset asset) {
    int declared = asset.getBgVatCreditYear();
    if (declared != 0) {
      return declared;
    }
    InvoiceLine line = asset.getInvoiceLine();
    if (line == null) {
      return 0;
    }
    Invoice invoice = line.getInvoice();
    if (invoice == null || invoice.getInvoiceDate() == null) {
      return 0;
    }
    return invoice.getInvoiceDate().getYear();
  }

  /**
   * Видът кредит — от актива, иначе преведен от {@code InvoiceLine.bgVatCreditType}.
   *
   * <p>⚠️ Преводът е ЕДНОПОСОЧЕН и непълен нарочно: дневникът за покупките не различава
   * пропорционалния по чл. 71а/71б от частичния (и двата са „2“), а формулата на ал. 3 ги дели на
   * буква „б“ и буква „в“. Затова „2“ дава PARTIAL, а PROPORTIONAL се маркира само на ръка върху
   * актива — догадката тук би сложила ПрНИД₀ във формула, в която не е бил.
   */
  public static CreditType creditTypeOf(FixedAsset asset) {
    CreditType declared = declaredCreditTypeOf(asset);
    if (declared != null) {
      return declared;
    }
    CreditType fromInvoice = invoiceCreditTypeOf(asset);
    // Нито върху актива, нито върху фактурата: „не знам“ се ЧЕТЕ като „няма ползван кредит“, тъй
    // че корекция не се начислява от нищото. ⚠️ Това е конвенция на ЧЕТЕНЕТО и не се записва:
    // {@link #applyInvoiceDefaults} оставя полето празно, защото празно е обявена липса, а
    // записано NONE е твърдение, че кредит не е ползван.
    return fromInvoice == null ? CreditType.NONE : fromInvoice;
  }

  /** Видът, обявен ВЪРХУ АКТИВА, или {@code null}, когато полето е празно. */
  static CreditType declaredCreditTypeOf(FixedAsset asset) {
    String declared = asset.getBgVatAcquisitionCreditTypeSelect();
    if (declared == null || declared.trim().isEmpty()) {
      return null;
    }
    try {
      return CreditType.valueOf(declared.trim());
    } catch (IllegalArgumentException e) {
      return CreditType.NONE;
    }
  }

  /**
   * Видът, преведен от покупната фактура, или {@code null}, когато и тя мълчи.
   *
   * <p>⚠️ Преводът е ЕДНОПОСОЧЕН и непълен нарочно — вж. {@link #creditTypeOf}. {@code
   * PROPORTIONAL} не се ражда оттук никога.
   */
  static CreditType invoiceCreditTypeOf(FixedAsset asset) {
    InvoiceLine line = asset.getInvoiceLine();
    String code = line == null ? null : line.getBgVatCreditType();
    if (INVOICE_CREDIT_FULL.equals(code)) {
      return CreditType.FULL;
    }
    if (INVOICE_CREDIT_PARTIAL.equals(code)) {
      return CreditType.PARTIAL;
    }
    if (INVOICE_CREDIT_NONE.equals(code)) {
      return CreditType.NONE;
    }
    return null;
  }

  // ------------------------------------------------------------------ записване

  /**
   * Записва върху актива онова, което покупната фактура знае — и само него.
   *
   * <p><b>Защо изобщо се ЗАПИСВА, щом {@link #inputFor} и без това чете фактурата наживо.</b>
   * Защото четенето наживо обслужва само отписването. Годишната корекция по чл. 79а/79б обхожда
   * активите със ЗАЯВКА и филтърът ѝ пита БАЗАТА, не Java — актив с празни колони изобщо не влиза в
   * обхождането, колкото и изводима да е стойността им. Освен това фактурният ред се мени: кредитно
   * известие, преиздаване или сменен вид кредит след години биха разигравали историческия НДДС, а
   * той е закован в годината на придобиването.
   *
   * <p><b>Пише САМО в празно и НИКОГА не гади.</b> Въведеното от счетоводителя не се докосва —
   * подразбирането е удобство, не присъда. А четирите полета без източник във фактурата остават
   * празни:
   *
   * <ul>
   *   <li><b>класификацията по ал. 3</b> — понятието „недвижим имот“ по ЗДДС не съвпада с
   *       категорията по чл. 55 ЗКПО, а сгрешената точка сменя 1/20 с 1/5;
   *   <li><b>{@code PROPORTIONAL}</b> — дневникът за покупките не различава пропорционалния по чл.
   *       71а/71б от частичния (и двата са „2“);
   *   <li><b>ПрНИД₀</b> — няма го никъде в системата;
   *   <li><b>годината на фактическо използване</b> — факт за имота, не за фактурата.
   * </ul>
   *
   * <p>⚠️ И празният вид кредит НЕ се пълни с {@code NONE}. Четенето приема „не знам“ за „няма
   * ползван кредит“ ({@link #creditTypeOf}), но записано, същото NONE става ТВЪРДЕНИЕ, че кредит не
   * е ползван — и заковава корекцията на нула завинаги.
   */
  public static void applyInvoiceDefaults(FixedAsset asset) {
    if (asset == null || asset.getInvoiceLine() == null) {
      return;
    }
    if (asset.getBgVatChargedOnAcquisition().signum() == 0) {
      BigDecimal charged = vatChargedOf(asset);
      if (charged.signum() != 0) {
        asset.setBgVatChargedOnAcquisition(charged);
      }
    }
    if (asset.getBgVatCreditYear() == 0) {
      int year = creditYearOf(asset);
      if (year != 0) {
        asset.setBgVatCreditYear(year);
      }
    }
    if (declaredCreditTypeOf(asset) == null) {
      CreditType fromInvoice = invoiceCreditTypeOf(asset);
      if (fromInvoice != null) {
        asset.setBgVatAcquisitionCreditTypeSelect(fromInvoice.name());
      }
    }
  }

  /**
   * Записва К₀ от коефициента по чл. 73 за годината на кредита — <b>само при частичен кредит</b>.
   *
   * <p>Чл. 79 ал. 3 иска „коефициентът по чл. 73 за годината, през която е упражнено право на
   * данъчен кредит“. Подателят решава КОЙ коефициент е това (окончателният по ал. 6, а прогнозният
   * по ал. 5 само по изключение) — тук влиза вече избраното число.
   *
   * <p>⚠️ <b>Само за буква „б“.</b> Чл. 79в т. 3 и т. 5 заковават К₀ на 1 при изцяло приспаднат и
   * на 0 при чл. 70 — калкулаторът ги налага сам, тъй че записана тук стойност би била шум, който
   * не мени нищо. При {@code PROPORTIONAL} множителят е ПрНИД₀, не К₀. А празен вид кредит значи
   * „още не е обявен“: тогава и К₀ няма как да е верен.
   */
  public static void applyCoefficientDefault(FixedAsset asset, BigDecimal coefficient) {
    if (asset == null || coefficient == null || coefficient.signum() == 0) {
      return;
    }
    if (asset.getBgVatCoefficientK0().signum() != 0) {
      return;
    }
    if (declaredCreditTypeOf(asset) != CreditType.PARTIAL) {
      return;
    }
    asset.setBgVatCoefficientK0(coefficient);
  }

  private static BigDecimal nullIfZero(BigDecimal value) {
    return value.signum() == 0 ? null : value;
  }
}

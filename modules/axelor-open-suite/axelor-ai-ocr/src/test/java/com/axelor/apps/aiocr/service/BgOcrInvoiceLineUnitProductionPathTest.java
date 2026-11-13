package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService;
import com.axelor.apps.aiaccounting.service.invoice.spi.InvoiceLineUnitDefaultProvider;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLineEditService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Мерната единица на OCR ред — през РЕАЛНИЯ път на приемането, не през помощен метод.
 *
 * <pre>
 *   OcrExtractResult (формата, която Azure върна)
 *     → BgOcrIntakeService.buildInvoiceLine          (истинският строител на ред)
 *     → resolveLineProductAndAccount                 (истинското разрешаване на продукт)
 *     → InvoiceLineUnitDefaultService.applyTo        (споделеното правило)
 * </pre>
 *
 * <h2>Формата, от която тръгва тестът</h2>
 *
 * ДЕСЕТ обикновени реда, всеки вързан към генеричния продукт {@code OCR-GEN}, чийто {@code
 * base_product.unit} е NULL. Ако единственото присвояване по този път стои зад {@code if
 * (product.getUnit() != null)}, всичките десет се записват с {@code unit = null} — без ред в лога и
 * без признак по фактурата.
 *
 * <p>Подменени са САМО двете места, които искат база: търсенето на продукт и доставчикът на
 * настройката. Самото решение кой ред каква единица получава минава през продукционния код.
 */
class BgOcrInvoiceLineUnitProductionPathTest {

  /** Нетата на десетте реда от реалния документ. */
  private static final String[] LINE_NETS = {
    "3.30", "5.00", "5.38", "4.05", "1.39", "2.39", "10.62", "2.25", "16.18", "96.00"
  };

  private static Unit unit(String name) {
    Unit u = new Unit();
    u.setName(name);
    return u;
  }

  private static InvoiceLineUnitDefaultProvider provider(Unit configured) {
    return new InvoiceLineUnitDefaultProvider() {
      @Override
      public Unit defaultInvoiceLineUnit() {
        return configured;
      }

      @Override
      public String notConfiguredMessage() {
        return "Default invoice line unit is not configured in BG Localization.";
      }
    };
  }

  private static InvoiceLineUnitDefaultService defaults(Unit configured) {
    List<InvoiceLineUnitDefaultProvider> installed = List.of(provider(configured));
    return new InvoiceLineUnitDefaultService() {
      @Override
      protected List<InvoiceLineUnitDefaultProvider> providers() {
        return installed;
      }
    };
  }

  /**
   * Истинският {@code BgOcrIntakeService}, с подменено само търсенето на продукт (база) и
   * доставчика на настройката.
   */
  private static class IntakeWithStubbedLookups extends BgOcrIntakeService {

    private final Product product;
    private final InvoiceLineUnitDefaultService defaults;

    IntakeWithStubbedLookups(Product product, InvoiceLineUnitDefaultService defaults) {
      super(null, null, null, null, null);
      this.product = product;
      this.defaults = defaults;
    }

    @Override
    protected Product resolveProduct(OcrExtractLine src, Company company) {
      return product;
    }

    @Override
    protected InvoiceLineUnitDefaultService unitDefaults() {
      return defaults;
    }
  }

  /**
   * Истинският AI редактор на OCR редове, с подменен само доставчика на настройката. {@code settle}
   * е мост към продукционния {@code settleUnit} — {@code protected}, тоест достъпен само отвътре в
   * наследник.
   */
  private static class EditorWithStubbedDefaults extends OcrInvoiceLineEditService {

    private final InvoiceLineUnitDefaultService defaults;

    EditorWithStubbedDefaults(InvoiceLineUnitDefaultService defaults) {
      super(null);
      this.defaults = defaults;
    }

    @Override
    protected InvoiceLineUnitDefaultService unitDefaults() {
      return defaults;
    }

    void settle(InvoiceLine line) {
      settleUnit(line);
    }
  }

  /** Фактурата, каквато приемането ѝ подава: компания има, иначе продуктът не се разрешава. */
  private static Invoice draft() {
    Invoice invoice = new Invoice();
    Company company = new Company();
    company.setName("Nyxa");
    invoice.setCompany(company);
    return invoice;
  }

  /**
   * Редовете, както Azure ги е върнал: нето и описание, БЕЗ ставка — така редът не се нуждае от
   * данъчния справочник и тестът остава без база, без да заобикаля нищо по пътя на единицата.
   */
  private static OcrExtractResult extracted() {
    OcrExtractResult result = new OcrExtractResult();
    for (int i = 0; i < LINE_NETS.length; i++) {
      OcrExtractLine line = new OcrExtractLine();
      line.setDescription("item " + i);
      line.setQty(BigDecimal.ONE);
      line.setNetAmount(new BigDecimal(LINE_NETS[i]));
      result.getLines().add(line);
    }
    return result;
  }

  /** Генеричният продукт на приемането: разрешава се, но НЯМА собствена мерна единица. */
  private static Product ocrGeneric() {
    Product product = new Product();
    product.setCode("OCR-GEN");
    product.setName("OCR Generic Purchase");
    return product;
  }

  // ------------------------------------------------------------- регресията

  @Test
  @DisplayName("⚠️ десет реда с продукт БЕЗ единица — нито един празен")
  void tenLinesOnAProductWithoutItsOwnUnitAllGetTheConfiguredDefault() throws Exception {
    Unit broi = unit("Брой");
    BgOcrIntakeService intake = new IntakeWithStubbedLookups(ocrGeneric(), defaults(broi));
    Invoice invoice = draft();
    OcrExtractResult result = extracted();

    List<InvoiceLine> built = new ArrayList<>();
    for (OcrExtractLine src : result.getLines()) {
      built.add(intake.buildInvoiceLine(invoice, src, result));
    }

    assertEquals(10, built.size());
    for (InvoiceLine line : built) {
      assertNotNull(line.getProduct(), "предпоставката: продуктът СЕ разрешава");
      assertNull(line.getProduct().getUnit(), "и точно той няма собствена единица");
      assertSame(broi, line.getUnit(), "редът не бива да стига до записа без мерна единица");
      assertEquals(
          InvoiceLineRepository.TYPE_NORMAL, line.getTypeSelect(), "това са обикновени редове");
    }
  }

  @Test
  @DisplayName("Продукт със СОБСТВЕНА единица — тя печели над настроената по подразбиране")
  void aProductWithItsOwnUnitWins() throws Exception {
    Unit kg = unit("Килограм");
    Product product = ocrGeneric();
    product.setUnit(kg);

    InvoiceLine line =
        new IntakeWithStubbedLookups(product, defaults(unit("Брой")))
            .buildInvoiceLine(draft(), extracted().getLines().get(0), extracted());

    assertSame(kg, line.getUnit());
  }

  @Test
  @DisplayName("Ред без никакъв продукт (само описание) също получава настроената единица")
  void aDescriptionOnlyLineGetsTheConfiguredDefault() throws Exception {
    Unit broi = unit("Брой");

    InvoiceLine line =
        new IntakeWithStubbedLookups(null, defaults(broi))
            .buildInvoiceLine(draft(), extracted().getLines().get(0), extracted());

    assertNull(line.getProduct());
    assertSame(broi, line.getUnit());
  }

  @Test
  @DisplayName("⚠️ Ненастроена локализация спира приемането с ЯСНА грешка, не прави 10 празни реда")
  void missingConfigurationFailsTheIntakeLoudly() {
    BgOcrIntakeService intake = new IntakeWithStubbedLookups(ocrGeneric(), defaults(null));
    Invoice invoice = draft();
    OcrExtractResult result = extracted();

    AxelorException failure =
        assertThrows(
            AxelorException.class,
            () -> intake.buildInvoiceLine(invoice, result.getLines().get(0), result));

    assertTrue(
        failure.getMessage().contains("Default invoice line unit is not configured"),
        "съобщението трябва да казва какво да се настрои: " + failure.getMessage());
  }

  // --------------------------------------------- AI редакторът на OCR редове

  /**
   * Приоритетът в редактора на редове (add/update/replace през AI инструментите). Извиква се
   * продукционният {@code settleUnit} — последната стъпка на {@code apply}, точно след продукта и
   * след изричното {@code unitId} на повикващия.
   *
   * <p>⚠️ Цялото {@code apply} не се кара оттук, защото по средата си иска {@code
   * CurrencyScaleService} през Guice, тоест жив инжектор. Редът на стъпките се вижда на място:
   * {@code settleUnit} е последният ред на метода.
   */
  @Test
  @DisplayName("AI редакторът: изрично подадена → на продукта → настроената")
  void theAiLineEditorKeepsTheSamePrecedence() {
    Unit broi = unit("Брой");
    Unit litre = unit("Литър");
    Unit kg = unit("Килограм");
    EditorWithStubbedDefaults editor = new EditorWithStubbedDefaults(defaults(broi));

    InvoiceLine explicit = new InvoiceLine();
    explicit.setUnit(litre);
    editor.settle(explicit);
    assertSame(litre, explicit.getUnit());

    InvoiceLine fromProduct = new InvoiceLine();
    Product withKg = ocrGeneric();
    withKg.setUnit(kg);
    fromProduct.setProduct(withKg);
    editor.settle(fromProduct);
    assertSame(kg, fromProduct.getUnit());

    InvoiceLine bare = new InvoiceLine();
    bare.setProduct(ocrGeneric());
    editor.settle(bare);
    assertSame(broi, bare.getUnit());
  }

  @Test
  @DisplayName("Заглавен ред в редактора остава без единица")
  void aTitleLineIsLeftAlone() {
    EditorWithStubbedDefaults editor = new EditorWithStubbedDefaults(defaults(unit("Брой")));
    InvoiceLine title = new InvoiceLine();
    title.setTypeSelect(InvoiceLineRepository.TYPE_TITLE);
    editor.settle(title);
    assertNull(title.getUnit());
  }
}

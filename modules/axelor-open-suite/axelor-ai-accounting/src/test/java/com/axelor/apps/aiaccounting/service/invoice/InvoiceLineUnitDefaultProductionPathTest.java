package com.axelor.apps.aiaccounting.service.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldContributor;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiaccounting.service.invoice.spi.InvoiceLineUnitDefaultProvider;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Единицата на новия ред — през РЕАЛНИТЕ пътища на AI фактурите, не през помощен метод.
 *
 * <pre>
 *   InvoiceLineEditService.addLine / updateLine   (истинските операции на инструментите)
 *     → applySpec                                 (истинското прилагане на подадените полета)
 *     → settleUnit → InvoiceLineUnitDefaultService (споделеното правило)
 * </pre>
 *
 * <p>Подменено е САМО това, което иска Guice и няма нищо общо с единицата: справочникът на
 * допълнителните полета ({@code writableFieldNames}) и доставчикът на настройката. Самото
 * подреждане по приоритет минава през продукционния код.
 */
class InvoiceLineUnitDefaultProductionPathTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Unit unit(String name) {
    Unit unit = new Unit();
    unit.setName(name);
    return unit;
  }

  /** Доставчик с настроена стойност — както bg-localization отговаря при попълнен конфиг. */
  private static InvoiceLineUnitDefaultProvider configured(Unit unit) {
    return new InvoiceLineUnitDefaultProvider() {
      @Override
      public Unit defaultInvoiceLineUnit() {
        return unit;
      }

      @Override
      public String notConfiguredMessage() {
        return "Default invoice line unit is not configured in BG Localization.";
      }
    };
  }

  /** Инсталирана локализация БЕЗ настроена стойност — точно случаят на живата база. */
  private static InvoiceLineUnitDefaultProvider unconfigured() {
    return configured(null);
  }

  private static InvoiceLineUnitDefaultService serviceWith(
      InvoiceLineUnitDefaultProvider... installed) {
    List<InvoiceLineUnitDefaultProvider> list = new ArrayList<>();
    for (InvoiceLineUnitDefaultProvider provider : installed) {
      list.add(provider);
    }
    return new InvoiceLineUnitDefaultService() {
      @Override
      protected List<InvoiceLineUnitDefaultProvider> providers() {
        return list;
      }
    };
  }

  /**
   * Истинският редактор. Подменени са САМО двете неща, които искат жив Guice инжектор и нямат нищо
   * общо с мерната единица: справочникът на допълнителните полета (той се сканира от classpath-а) и
   * доставчикът на настройката. Самото {@code addLine}/{@code updateLine} → {@code applySpec} →
   * {@code settleUnit} е продукционният код.
   */
  private static InvoiceLineEditService editorWith(InvoiceLineUnitDefaultService defaults) {
    AiInvoiceFieldRegistry emptyRegistry =
        new AiInvoiceFieldRegistry() {
          @Override
          public List<AiInvoiceFieldContributor> all() {
            return List.of();
          }
        };
    return new InvoiceLineEditService() {
      @Override
      protected AiInvoiceFieldRegistry fieldRegistry() {
        return emptyRegistry;
      }

      /**
       * Стоковото {@code InvoiceLineService.compute} също иска инжектор. Пропускането му НЕ крие
       * нищо по темата: то се изпълнява вътре в {@code applySpec} (стъпка 4), тоест ПРЕДИ {@code
       * settleUnit}, и не пипа {@code unit} — сметките му са по количество, цена и данък.
       */
      @Override
      protected void recompute(Invoice invoice, InvoiceLine line) {
        // no-op
      }

      @Override
      protected InvoiceLineUnitDefaultService unitDefaults() {
        return defaults;
      }
    };
  }

  private static Invoice invoiceFor(InvoiceSide side) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(
        side == InvoiceSide.SUPPLIER
            ? InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
            : InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    invoice.setInvoiceLineList(new ArrayList<>());
    return invoice;
  }

  private static InvoiceLine onlyLine(Invoice invoice) {
    assertEquals(1, invoice.getInvoiceLineList().size());
    return invoice.getInvoiceLineList().get(0);
  }

  // ------------------------------------------------------- AI-създадени редове

  @Test
  @DisplayName("AI ред по ДОСТАВНА фактура без продукт и без единица получава настроената")
  void aiSupplierLineWithoutProductGetsTheConfiguredDefault() throws Exception {
    Unit broi = unit("Брой");
    Invoice invoice = invoiceFor(InvoiceSide.SUPPLIER);

    editorWith(serviceWith(configured(broi)))
        .addLine(invoice, InvoiceSide.SUPPLIER, JSON.readTree("{\"productName\":\"Услуга\"}"));

    assertSame(broi, onlyLine(invoice).getUnit());
  }

  @Test
  @DisplayName("AI ред по КЛИЕНТСКА фактура без продукт и без единица получава настроената")
  void aiCustomerLineWithoutProductGetsTheConfiguredDefault() throws Exception {
    Unit broi = unit("Брой");
    Invoice invoice = invoiceFor(InvoiceSide.CUSTOMER);

    editorWith(serviceWith(configured(broi)))
        .addLine(invoice, InvoiceSide.CUSTOMER, JSON.readTree("{\"productName\":\"Консултация\"}"));

    assertSame(broi, onlyLine(invoice).getUnit());
  }

  @Test
  @DisplayName("⚠️ Изрично занулена единица при update се връща — обикновен ред не остава празен")
  void updateThatClearsTheUnitDoesNotLeaveItEmpty() throws Exception {
    Unit broi = unit("Брой");
    Invoice invoice = invoiceFor(InvoiceSide.SUPPLIER);
    InvoiceLineEditService editor = editorWith(serviceWith(configured(broi)));

    editor.addLine(invoice, InvoiceSide.SUPPLIER, JSON.readTree("{\"productName\":\"Услуга\"}"));
    InvoiceLine line = onlyLine(invoice);
    editor.updateLine(invoice, InvoiceSide.SUPPLIER, line, JSON.readTree("{\"unitId\":null}"));

    assertSame(broi, line.getUnit(), "занулената единица не бива да оцелее до записа");
  }

  @Test
  @DisplayName("⚠️ Ненастроена локализация спира създаването с ЯСНА грешка, не мълчи")
  void missingConfigurationBlocksTheCreation() throws Exception {
    Invoice invoice = invoiceFor(InvoiceSide.SUPPLIER);
    InvoiceLineEditService editor = editorWith(serviceWith(unconfigured()));

    InvoiceBlocked blocked =
        assertThrows(
            InvoiceBlocked.class,
            () ->
                editor.addLine(
                    invoice, InvoiceSide.SUPPLIER, JSON.readTree("{\"productName\":\"Услуга\"}")));

    assertEquals(AiBlockerCode.MISSING_UNIT_CONFIG.wire(), blocked.getCode());
    assertTrue(
        blocked.getMessage().contains("Default invoice line unit is not configured"),
        "съобщението трябва да казва КАКВО липсва: " + blocked.getMessage());
    assertTrue(
        invoice.getInvoiceLineList().isEmpty(),
        "нито един ред не бива да остане на фактурата след ясния отказ");
  }

  @Test
  @DisplayName("⚠️ Единицата се решава СЛЕД сметките — преизчисление не може да я изтрие")
  void theUnitIsSettledAfterAxelorsRecompute() throws Exception {
    Unit broi = unit("Брой");
    Invoice invoice = invoiceFor(InvoiceSide.SUPPLIER);
    AiInvoiceFieldRegistry emptyRegistry =
        new AiInvoiceFieldRegistry() {
          @Override
          public List<AiInvoiceFieldContributor> all() {
            return List.of();
          }
        };
    // Преизчисление, което ЗАНУЛЯВА единицата. Стоковото не прави това, но ако някога започне,
    // редът на стъпките е единственото, което пази резултата — и точно той се твърди тук.
    InvoiceLineEditService editor =
        new InvoiceLineEditService() {
          @Override
          protected AiInvoiceFieldRegistry fieldRegistry() {
            return emptyRegistry;
          }

          @Override
          protected void recompute(Invoice inv, InvoiceLine line) {
            line.setUnit(null);
          }

          @Override
          protected InvoiceLineUnitDefaultService unitDefaults() {
            return serviceWith(configured(broi));
          }
        };

    editor.addLine(invoice, InvoiceSide.SUPPLIER, JSON.readTree("{\"productName\":\"Услуга\"}"));

    assertSame(broi, onlyLine(invoice).getUnit(), "settleUnit трябва да е ПОСЛЕДНАТА стъпка");
  }

  // ------------------------------------------------------------- приоритетът

  @Test
  @DisplayName("Приоритет: изрично подадена единица → единица на продукта → настроената")
  void precedenceIsExplicitThenProductThenDefault() throws Exception {
    Unit broi = unit("Брой");
    Unit kg = unit("Килограм");
    Unit litre = unit("Литър");
    InvoiceLineUnitDefaultService service = serviceWith(configured(broi));

    InvoiceLine explicit = normalLine();
    explicit.setUnit(litre);
    Product productWithKg = new Product();
    productWithKg.setUnit(kg);
    explicit.setProduct(productWithKg);
    service.applyTo(explicit);
    assertSame(litre, explicit.getUnit(), "изрично подадената не се подменя от продукта");

    InvoiceLine fromProduct = normalLine();
    fromProduct.setProduct(productWithKg);
    service.applyTo(fromProduct);
    assertSame(kg, fromProduct.getUnit(), "единицата на продукта бие настроената по подразбиране");

    InvoiceLine bare = normalLine();
    bare.setProduct(new Product()); // точно OCR-GEN: продукт БЕЗ собствена единица
    service.applyTo(bare);
    assertSame(broi, bare.getUnit(), "продукт без единица пада на настроената");
  }

  @Test
  @DisplayName("Заглавен и опаковъчен ред не се пипат — там Axelor не иска единица")
  void nonNormalLinesAreLeftAlone() throws Exception {
    InvoiceLineUnitDefaultService service = serviceWith(configured(unit("Брой")));

    for (int type :
        new int[] {
          InvoiceLineRepository.TYPE_TITLE,
          InvoiceLineRepository.TYPE_START_OF_PACK,
          InvoiceLineRepository.TYPE_END_OF_PACK
        }) {
      InvoiceLine line = new InvoiceLine();
      line.setTypeSelect(type);
      service.applyTo(line);
      assertNull(line.getUnit(), "ред от вид " + type + " не бива да получава единица");
    }
  }

  @Test
  @DisplayName("Без инсталирана локализация правилото мълчи — то е нейно, не на ядрото")
  void withoutAnyProviderNothingIsEnforced() throws Exception {
    InvoiceLine line = normalLine();
    serviceWith().applyTo(line);
    assertNull(line.getUnit());
  }

  @Test
  @DisplayName("⚠️ Две локализации с различна стойност — отказ, не избор по ред на сканиране")
  void twoProvidersAreRefusedRatherThanPickedBetween() {
    InvoiceLineUnitDefaultService service =
        serviceWith(configured(unit("Брой")), configured(unit("Килограм")));

    AxelorException failure =
        assertThrows(AxelorException.class, () -> service.applyTo(normalLine()));
    assertTrue(
        failure.getMessage().contains("More than one localization"),
        "отказът трябва да казва защо: " + failure.getMessage());
  }

  private static InvoiceLine normalLine() {
    InvoiceLine line = new InvoiceLine();
    line.setTypeSelect(InvoiceLineRepository.TYPE_NORMAL);
    line.setQty(BigDecimal.ONE);
    return line;
  }
}

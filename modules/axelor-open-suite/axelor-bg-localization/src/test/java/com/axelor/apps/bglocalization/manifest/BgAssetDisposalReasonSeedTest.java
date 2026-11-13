package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgAssetRemoval;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Пазачи на причините за извеждане на актив — seed-ът, който води цялото отписване.
 *
 * <p>Причината решава четири различни неща: разходната сметка при отписване, дали отписване изобщо
 * се дължи, третирането по ЗДДС и предложеното основание по чл. 80. Сгрешен ред тук не се обажда
 * никъде — той просто отписва на грешна сметка или освобождава от данък.
 *
 * <p><b>⚠️ {@code quotedRows}, не {@code rows}.</b> Бележките за доказване съдържат запетаи, тирета
 * и кавички-лапички, а колоните са с QUOTE_ALL — голото цепене по „;“ връща стойностите ЗАЕДНО с
 * кавичките и {@link BgSeed#col} не намира нито една колона. Същият капан е назован и в {@code
 * BgD1FlagsAgainstNaredbaTest}.
 */
class BgAssetDisposalReasonSeedTest {

  private static final String FILE = "bg_asset_disposal_reason.csv";

  /** Базовите стойности на {@code AssetDisposalReason.disposalTypeSelect}. */
  private static final String TYPE_SCRAPPING = "1";

  private static final String TYPE_CESSION = "2";

  /**
   * ⚠️ ЧУЖДИТЕ НОМЕРА. {@code account_assetDisposalReason.csv} в axelor-account вече заема importId
   * 1–6 (Breakage, Damage, Transfer, Obsolescence, C_EXT, C_INT), а търсенето при внос е {@code
   * self.importId = :importId} БЕЗ префикс по компания — тоест номерата са едно общо пространство и
   * заетият номер ПРЕИМЕНУВА заварен ред, вместо да добави нов.
   */
  private static final int FOREIGN_IMPORT_IDS_UP_TO = 6;

  @Test
  @DisplayName("девет причини, с номера извън заетите от axelor-account")
  void номерата_не_се_бият_с_чуждите() {
    List<String[]> rows = BgSeed.quotedRows(FILE);
    assertEquals(9, rows.size(), "очаквам деветте български основания за извеждане");

    Set<String> ids = new LinkedHashSet<>();
    for (String[] row : rows) {
      String id = BgSeed.col(BgSeed.quotedHeader(FILE), row, "importId");
      assertTrue(ids.add(id), "importId " + id + " се среща два пъти — вносът е upsert и НЕ трие");
      assertTrue(
          Integer.parseInt(id) > FOREIGN_IMPORT_IDS_UP_TO,
          "importId "
              + id
              + " попада в заетите 1–"
              + FOREIGN_IMPORT_IDS_UP_TO
              + " от account_assetDisposalReason.csv и би ПРЕИМЕНУВАЛ заварена причина, вместо да"
              + " добави нова — номерата са общо пространство, без префикс по компания");
    }
  }

  @Test
  @DisplayName("кодовете са уникални — колоната е unique в домейна")
  void кодовете_са_уникални() {
    Set<String> codes = new LinkedHashSet<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      String code = BgSeed.col(BgSeed.quotedHeader(FILE), row, "code");
      assertFalse(code.isEmpty(), "празен код");
      assertTrue(codes.add(code), "код " + code + " се повтаря, а полето е unique в домейна");
    }
  }

  @Test
  @DisplayName("слотът за разходна сметка съвпада с базовия вид отписване")
  void слотът_съвпада_с_вида() {
    // Разходната сметка НЕ идва от слота — базата я взима по transferredReason, който излиза от
    // disposalTypeSelect: продажба (2) → realisedAssetsValueAccount (60908), бракуване (1) →
    // approExtraordDepreciationExpenseAccount (60905). Слотът е обявеното НАМЕРЕНИЕ и трябва да
    // сочи същото, инак seed-ът твърди едно, а статията прави друго.
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String kind = BgSeed.col(h, row, "bgRemovalKindSelect");
      String slot = BgSeed.col(h, row, "bgWriteOffAccountSelect");
      String type = BgSeed.col(h, row, "disposalTypeSelect");

      if (!BgAssetRemoval.Kind.WRITE_OFF.equals(kind)) {
        assertTrue(
            slot.isEmpty(),
            code + ": не е отписване, тъй че разходна сметка не му трябва — слотът да е празен");
        continue;
      }
      assertFalse(slot.isEmpty(), code + ": отписване без обявен слот за разходна сметка");
      if (BgAssetRemoval.AccountSlot.REALISED_VALUE.equals(slot)) {
        assertEquals(
            TYPE_CESSION,
            type,
            code
                + ": слот 609 (балансова стойност при продажба) иска базов вид „продажба“ (2) —"
                + " само тогава generateDisposalMove взима realisedAssetsValueAccount");
      } else {
        assertEquals(
            TYPE_SCRAPPING,
            type,
            code
                + ": слот EXTRAORDINARY иска базов вид „бракуване“ (1) — само тогава"
                + " generateDisposalMove взима approExtraordDepreciationExpenseAccount");
      }
    }
  }

  @Test
  @DisplayName("сметката по подразбиране: унищожен → 60908, възстановима липса → 442, иначе празно")
  void сметката_по_подразбиране() {
    // Правилото: УНИЩОЖЕН актив — брак, непреодолима сила — е
    // балансова стойност на отписан ДМА, 60908; ИЗЧЕЗНАЛ актив — липса — е 60905 „Разходи за липси
    // и брак на активи“, и идва от категорията (празен код). Възстановимата липса е ВЗЕМАНЕ: при
    // отговорно лице стойността не напуска предприятието, а сменя формата си — 442.
    //
    // ⚠️ Номерата са ИЗБОР НА СЪСТАВИТЕЛЯ по NRA_Nom_Accounts, не нормативни: задължителен
    // национален сметкоплан няма от 2002 г. Затова стоят ТУК, в началните данни, а не в кода.
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("BG_SCRAP", "60908");
    expected.put("BG_FORCE", "60908");
    expected.put("BG_SHORT_REC", "442");
    Map<String, String> actual = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String account = BgSeed.col(h, row, "bgRemovalAccountCode");
      if (!account.isEmpty()) {
        actual.put(BgSeed.col(h, row, "code"), account);
      }
    }
    assertEquals(
        expected,
        actual,
        "сметка по подразбиране има само там, където категорията не дава правилната: унищожен"
            + " актив → 60908, възстановима липса → 442. Празно = категорията: продажба и дарение"
            + " на 60908 (realisedAssetsValueAccount), невъзстановима липса на 60905"
            + " (approExtraordDepreciationExpenseAccount)");
  }

  @Test
  @DisplayName("невъзстановимата липса НЕ носи сметка — тя е разход на 60905 от категорията")
  void невъзстановимата_липса_остава_разход() {
    // Двете причини се различават точно по това и по нищо друго в счетоводната част: BG_SHORT е
    // разход, BG_SHORT_REC е вземане. ДДС третирането им е ЕДНАКВО — чл. 79 ал. 1 ЗДДС дължи данък
    // при кражба, загуба или липси, а чл. 80 ал. 2 т. 1 б. „г“ иска ДОКАЗВАНЕ, че събитието не е по
    // вина на лицето. Наличието на отговорно лице не е такова доказване, то по-скоро сочи
    // обратното.
    String shortAccount = null;
    String shortVat = null;
    String recVat = null;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      if ("BG_SHORT".equals(code)) {
        shortAccount = BgSeed.col(h, row, "bgRemovalAccountCode");
        shortVat = BgSeed.col(h, row, "bgVatTreatmentSelect");
      }
      if ("BG_SHORT_REC".equals(code)) {
        recVat = BgSeed.col(h, row, "bgVatTreatmentSelect");
      }
    }
    assertEquals(
        "",
        shortAccount,
        "BG_SHORT е НЕвъзстановимата липса: няма отговорно лице, няма вземане, стойността наистина"
            + " напуска предприятието. Тя е разход на 60905 от категорията");
    assertEquals(
        shortVat,
        recVat,
        "двете липси трябва да имат ЕДНАКВО ДДС третиране. Възстановимостта е счетоводен въпрос"
            + " (разход или вземане), а не данъчен: чл. 79 ал. 1 ЗДДС дължи данък при кражба,"
            + " установяване на загуба или липси, а освобождаването по чл. 80 ал. 2 т. 1 б. „г“"
            + " виси под условие лицето ДА ДОКАЖЕ, че събитието не е по негова вина. Отговорното"
            + " лице не е такова доказване");
  }

  @Test
  @DisplayName("подразбирането по ЗДДС е „подлежи“ за брак, липси и непреодолима сила")
  void ддс_подразбирането_е_подлежи() {
    List<String> mustBeDue = Arrays.asList("BG_SCRAP", "BG_SHORT", "BG_FORCE");
    List<String> seen = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String treatment = BgSeed.col(h, row, "bgVatTreatmentSelect");
      if (mustBeDue.contains(code)) {
        seen.add(code);
        assertEquals(
            BgAssetRemoval.VatTreatment.CORRECTION_DUE,
            treatment,
            code
                + ": чл. 79 ал. 1 ЗДДС дължи данък „при унищожаване, кражба, установяване на загуба"
                + " или на липси или при бракуване“. Освобождаванията по чл. 80 ал. 2 висят под"
                + " условие за ДОКАЗАНОСТ, което seed не може да твърди — а обявено „не подлежи“,"
                + " когато корекция се дължи, е скрит данъчен пасив");
      }
      assertFalse(
          BgAssetRemoval.VatTreatment.NOT_DUE.equals(treatment),
          code
              + ": нито една причина не бива да предлага „не подлежи“ по подразбиране. Тази"
              + " стойност се обявява само per отписване и само срещу доказателство — виж"
              + " BgFixedAssetWriteOffManagementRepository");
    }
    assertEquals(mustBeDue.size(), seen.size(), "липсва причина от " + mustBeDue);
  }

  @Test
  @DisplayName("продажбата и дарението не са корекция, а фактура и приравнена доставка")
  void продажбата_и_дарението() {
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String treatment = BgSeed.col(h, row, "bgVatTreatmentSelect");
      if ("BG_SALE".equals(code)) {
        assertEquals(
            BgAssetRemoval.VatTreatment.NOT_APPLICABLE,
            treatment,
            "продажбата е облагаема доставка — ДДС-то се начислява на ФАКТУРАТА по чл. 113 ЗДДС,"
                + " не като корекция. Корекция по чл. 79 ал. 2 би се дължала само при последваща"
                + " доставка БЕЗ право на приспадане");
      }
      if ("BG_SCRAPSALE".equals(code)) {
        assertEquals(
            BgAssetRemoval.VatTreatment.REVERSE_CHARGED,
            treatment,
            "продажбата на бракуван актив като отпадък по Приложение № 2, част първа, т. 5 е"
                + " доставка, по която доставчикът НЕ начислява данък — чл. 163а ал. 2 ЗДДС го прави"
                + " изискуем от получателя. Обявена като NOT_APPLICABLE, тя би се слила с"
                + " обикновената продажба и дневникът не би показал, че фактурата излиза без"
                + " начислен данък");
      }
      if ("BG_DONAT".equals(code)) {
        assertEquals(
            BgAssetRemoval.VatTreatment.CHARGED_AS_SUPPLY,
            treatment,
            "безвъзмездното прехвърляне е ПРИРАВНЕНО на възмездна доставка по чл. 6 ал. 3 т. 2"
                + " ЗДДС — начислява се ДДС, а не корекция по чл. 79");
      }
    }
  }

  @Test
  @DisplayName("консервацията и напълно данъчно амортизираният НЕ са отписване")
  void двете_причини_без_отписване() {
    boolean suspension = false;
    boolean taxOnly = false;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String kind = BgSeed.col(h, row, "bgRemovalKindSelect");
      if ("BG_CONSRV".equals(code)) {
        suspension = true;
        assertEquals(
            BgAssetRemoval.Kind.SUSPENSION,
            kind,
            "чл. 59 ал. 1 ЗКПО, дословно: „Данъчният амортизируем актив НЕ СЕ ОТПИСВА от данъчния"
                + " амортизационен план.“ Обявена като отписване, консервацията би произвела точно"
                + " статията с Кт 20x, която законът забранява");
      }
      if ("BG_TAXEND".equals(code)) {
        taxOnly = true;
        assertEquals(
            BgAssetRemoval.Kind.TAX_PLAN_ONLY,
            kind,
            "чл. 60 ал. 1 изважда актива от ДАП при пълно данъчно амортизиране, а данъчният план се"
                + " изчерпва САМ — счетоводна статия не се дължи и активът може още да е на баланс");
      }
    }
    assertTrue(suspension, "липсва причина „Консервация“ (BG_CONSRV)");
    assertTrue(taxOnly, "липсва причина „Напълно данъчно амортизиран“ (BG_TAXEND)");
  }

  @Test
  @DisplayName("всяко предложено основание по чл. 80 идва с условието, което трябва да се докаже")
  void основанията_носят_условието() {
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String ground = BgSeed.col(h, row, "bgVatGroundSelect");
      String note = BgSeed.col(h, row, "bgVatProofNote");
      assertFalse(
          note.trim().isEmpty(),
          code
              + ": всяка причина трябва да носи бележка какво се дължи и на какво основание —"
              + " съставителят я чете вместо да търси в закона");
      if (ground.startsWith("ART80_")) {
        // Проверява се, че бележката ГОВОРИ за доказване, а не точна фраза: „докаже“, „доказано“,
        // „потвърдено“ и „документ“ са все начини да се каже същото и всички се срещат в закона.
        //
        // Гледа се само фамилията ART80_*, защото условието за доказаност е нейно: то стои в
        // отварящото изречение на точката от чл. 80 ал. 2. ART163A_2 е режим на начисляване при
        // доставка, а не изключение от корекция — на него не виси условие за доказване и общата
        // проверка би минала случайно, само защото бележката съдържа думата „документира“.
        String lower = note.toLowerCase();
        assertTrue(
            lower.contains("докаж")
                || lower.contains("доказ")
                || lower.contains("потвърд")
                || lower.contains("документ"),
            code
                + ": предложено основание "
                + ground
                + " без описано условие за доказване. Всяко освобождаване по чл. 80 ал. 2 виси под"
                + " условие в ОТВАРЯЩОТО изречение на точката („доказано или потвърдено"
                + " унищожаване …“), а не в буквата — бележката трябва да го каже");
      }
    }
  }

  @Test
  @DisplayName("продаденият за скрап актив върви по клона за ПРОДАЖБА, не по бракуването")
  void скрапът_е_продажба_не_бракуване() {
    // Чл. 80 ал. 2 т. 4 ЗДДС е ИЗКЛЮЧЕНИЕ ОТ КОРЕКЦИЯ и има смисъл само ако корекционно събитие по
    // чл. 79 вече е настъпило („бракуване, после доставка с бракуваните стоки“). Когато активът
    // излиза СРЕЩУ ПРЕСТАЦИЯ, събитието е доставка от самото начало: корекция не се поражда, тъй
    // че изключение от нея не се цитира. Основанието е чл. 163а ал. 2 — данъкът е изискуем от
    // получателя, защото Приложение № 2, част първа, т. 5 е „Отпадъци от черни и цветни метали“, а
    // § 1 т. 77 ДР ЗДДС включва в тях „бракуваните машини, съоръжения, детайли и конструкции“.
    boolean seen = false;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      String ground = BgSeed.col(h, row, "bgVatGroundSelect");

      assertFalse(
          BgAssetRemoval.VatGround.ART80_2_4.equals(ground),
          code
              + ": нито една причина не бива да предлага чл. 80 ал. 2 т. 4. Продажбата за скрап"
              + " не е изключение от корекция, а доставка — основанието ѝ е чл. 163а ал. 2");

      if (!"BG_SCRAPSALE".equals(code)) {
        continue;
      }
      seen = true;
      assertEquals(
          TYPE_CESSION,
          BgSeed.col(h, row, "disposalTypeSelect"),
          "продажбата за скрап е базов вид „продажба“ (2) — само тогава фактурата носи прихода и"
              + " балансовата стойност отива на 609");
      assertEquals(
          BgAssetRemoval.AccountSlot.REALISED_VALUE,
          BgSeed.col(h, row, "bgWriteOffAccountSelect"),
          "слот REALISED_VALUE: активът излиза срещу престация. Бракът (слот EXTRAORDINARY) е само за"
              + " реално унищожен актив БЕЗ насрещна престация");
      assertEquals(
          BgAssetRemoval.Kind.WRITE_OFF,
          BgSeed.col(h, row, "bgRemovalKindSelect"),
          "продажбата за скрап е пълно отписване — и счетоводно, и от ДАП");
      assertEquals(
          BgAssetRemoval.VatGround.ART163A_2,
          ground,
          "основанието е чл. 163а ал. 2, а не точка от чл. 80 ал. 2");

      String note = BgSeed.col(h, row, "bgVatProofNote");
      // Малката буква е на закона: чл. 163а ал. 1 пише „посочени в приложение № 2“, а заглавието
      // на самото приложение е с главна. Бележката цитира и двете, тъй че сверката е без регистър.
      String lowerNote = note.toLowerCase();
      assertTrue(
          lowerNote.contains("163а") && lowerNote.contains("приложение № 2"),
          "бележката трябва да носи и двете препратки — чл. 163а ал. 2 и Приложение № 2 — защото"
              + " обратното начисляване виси на предмета на доставката, не на вида отписване");
      assertTrue(
          note.contains("113"),
          "бележката трябва да каже, че извеждането се документира с ФАКТУРА по чл. 113 ЗДДС —"
              + " това е разликата от бракуването");
    }
    assertTrue(seen, "липсва причина „Продажба за скрап“ (BG_SCRAPSALE)");
  }

  @Test
  @DisplayName("бракуването остава само за унищожен актив без насрещна престация")
  void бракуването_е_стеснено() {
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      if (!"BG_SCRAP".equals(BgSeed.col(h, row, "code"))) {
        continue;
      }
      String note = BgSeed.col(h, row, "bgVatProofNote");
      assertTrue(
          note.contains("БЕЗ насрещна престация"),
          "бележката на бракуването трябва да казва изрично, че важи само без насрещна престация —"
              + " инак съставителят ще отпише продаден за скрап актив като брак и ще начисли корекция"
              + " по чл. 79, която не се дължи");
      assertTrue(
          note.contains("Продажба за скрап"),
          "бележката трябва да посочи КЪДЕ отива продаденият за скрап актив — иначе разделянето"
              + " остава знание на автора, а не на съставителя");
      return;
    }
    throw new AssertionError("липсва причина „Бракуване (износване)“ (BG_SCRAP)");
  }

  @Test
  @DisplayName("⚠️ възстановимата липса сменя СМЕТКАТА, но НЕ и ДДС третирането")
  void възстановимата_липса_не_пипа_ддс() {
    // Двата въпроса са различни: счетоводното третиране на липсата (разход или вземане) и данъчният
    // ѝ кредит. Чл. 79 ал. 1 ЗДДС дължи данък „при унищожаване, кражба, установяване на загуба или
    // на липси", а освобождаване има само по чл. 80 ал. 2 т. 1 б. „г" — събитие извън контрола на
    // лицето, за което ЛИЦЕТО МОЖЕ ДА ДОКАЖЕ, че не е причинено по негова вина. Наличието на
    // отговорно лице НЕ е такова доказване; то по-скоро сочи обратното.
    String[] възстановима = null;
    String[] невъзстановима = null;
    for (String[] row : BgSeed.quotedRows(FILE)) {
      List<String> h = BgSeed.quotedHeader(FILE);
      String code = BgSeed.col(h, row, "code");
      if ("BG_SHORT_REC".equals(code)) {
        възстановима = row;
      } else if ("BG_SHORT".equals(code)) {
        невъзстановима = row;
      }
    }
    assertTrue(възстановима != null, "липсва причина „възстановима липса“ (BG_SHORT_REC)");
    assertTrue(невъзстановима != null, "липсва причина „липси и кражби“ (BG_SHORT)");

    List<String> h = BgSeed.quotedHeader(FILE);
    for (String field : Arrays.asList("bgVatTreatmentSelect", "bgVatGroundSelect")) {
      assertEquals(
          BgSeed.col(h, невъзстановима, field),
          BgSeed.col(h, възстановима, field),
          field
              + ": ДДС третирането на възстановимата липса трябва да е ТОЧНО като на"
              + " невъзстановимата. Различие тук би значело, че отговорното лице освобождава от"
              + " корекция — а то не е доказване по чл. 80 ал. 2 т. 1 б. „г“");
    }
    assertEquals(
        BgAssetRemoval.VatTreatment.CORRECTION_DUE,
        BgSeed.col(h, възстановима, "bgVatTreatmentSelect"),
        "и двете остават „подлежи на корекция“");

    // Слотът също остава EXTRAORDINARY: базата пак строи 60905, а пренасочването към вземане става
    // ПОСЛЕ и само ако клиентът е попълнил мапинга. Без запис поведението е непроменено.
    assertEquals(
        BgAssetRemoval.AccountSlot.EXTRAORDINARY,
        BgSeed.col(h, възстановима, "bgWriteOffAccountSelect"),
        "слотът остава извънреден разход — сметката се сменя от мапинга, не от seed-а");
    assertEquals(
        "1",
        BgSeed.col(h, възстановима, "disposalTypeSelect"),
        "базов вид „бракуване“ (1), както при невъзстановимата липса");
  }
}

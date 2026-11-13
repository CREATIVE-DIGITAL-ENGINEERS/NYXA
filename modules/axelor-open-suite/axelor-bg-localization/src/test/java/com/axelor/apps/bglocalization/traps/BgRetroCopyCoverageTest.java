package com.axelor.apps.bglocalization.traps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВСЯКО СКАЛАРНО ПОЛЕ НА ФИША ИМА РЕШЕНИЕ ЗА РЕТРО-КОПИЕТО — копира се или е изрично изключено.
 *
 * <p><b>ЗАЩО СЪЩЕСТВУВА.</b> {@code BgPaySlipService#retroCopyOf} строи коригиращото и заличаващото
 * копие поле по поле. Пропуснато поле не пада никъде: генерираният getter връща стойност по
 * подразбиране и копието просто носи нула, празно или {@code false}. Точно така се загубиха десет
 * полета наведнъж — двата броя болнични дни, шестте кофи на т. 16, изваждаемото и дискриминаторът —
 * и коригиращата Д1 излизаше с празни т. 16.А и т. 16.2 при осигурителен доход, който включва
 * перото по чл. 40 ал. 5 КСО. Противоречие ВЪТРЕ в един запис, невидимо от кода.
 *
 * <p><b>⚠️ СПИСЪКЪТ Е НА ИЗКЛЮЧЕНИЯТА, А НЕ НА КОПИРАНИТЕ, И ТОВА Е ЦЯЛАТА РАЗЛИКА.</b> Ново поле
 * по подразбиране е ЧЕРВЕНО и иска решение — копира ли се, или не, и защо. Обърнат („изброй
 * копираните“), списъкът щеше да мълчи точно при новото поле, тоест при единствения случай, заради
 * който изобщо съществува.
 *
 * <p>⚠️ Чете СОРСА, не reflection върху метода: кое поле се копира се вижда само в тялото на {@code
 * retroCopyOf}. Имената на полетата идват от домейна, тоест преименувано поле проваля търсенето,
 * вместо да остави разминати имена в проза. Същият идиом като {@code BgDefaultedGetterTest}.
 */
class BgRetroCopyCoverageTest {

  private static final Path DOMAIN =
      Paths.get("src/main/resources/domains/BgPaySlip.xml").toAbsolutePath();

  private static final Path LINE_DOMAIN =
      Paths.get("src/main/resources/domains/BgPaySlipLine.xml").toAbsolutePath();

  private static final Path REGIME =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/payroll/BgPayrollRegime.java")
          .toAbsolutePath();
  private static final Path SERVICE =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipService.java")
          .toAbsolutePath();

  /** Скаларните типове в домейна — връзките се копират отделно и не са предмет на този пазач. */
  private static final Pattern FIELD =
      Pattern.compile("<(string|boolean|integer|decimal|date|datetime|long)\\s+name=\"(\\w+)\"");

  /**
   * ⚠️ ПОЛЕТАТА, КОИТО НАРОЧНО НЕ СЕ КОПИРАТ — всяко с ПРИЧИНА, не само с име.
   *
   * <p>Причина без съдържание е по-лоша от липсващ ред: тя изглежда като решение. Затова тестът
   * иска и текст, не само ключ.
   */
  private static final Map<String, String> NOT_COPIED = new LinkedHashMap<>();

  static {
    NOT_COPIED.put(
        "reference",
        "копието е СВОЙ документ със свой номер. Носено наготово, две различни изявления пред НАП"
            + " щяха да се представят с един и същ идентификатор");
    NOT_COPIED.put(
        "bgCancelReason",
        "причината за отказ е състояние на ОРИГИНАЛА. Пренесена, копието щеше да се роди с текст,"
            + " обясняващ чужд отказ");
    NOT_COPIED.put(
        "bgRegimeChangeSummary",
        "обобщението сравнява КОПИЕТО с оригинала и се пише при запис на копието"
            + " (BgPaySlipRegimeAudit); пренесено, то щеше да описва промени, които копието още"
            + " не е правило");
    NOT_COPIED.put(
        "bgCancelReasonInput",
        "входът на бутона „Откажи“ е transient: не се пази и затова няма какво да се пренесе");
    NOT_COPIED.put(
        "bgPaidDate",
        "ретро-фишът НЕ се изплаща никога (refusePayRetro отказва на първия ред на pay()) — дата на"
            + " плащане върху него би твърдяла пари, които не са тръгвали");
    NOT_COPIED.put(
        "bgPayrollMoveNote",
        "статията на копието се ражда при неговото потвърждаване и носи своя следа; преписаната"
            + " щеше да сочи счетоводния запис на оригинала");
    NOT_COPIED.put(
        "bgGeneratorNotes",
        "присъдите са доклад на ЧУЖД обход. Копието не пуска генератори (isEligibleDraft излиза на"
            + " bgIsCorrection), тоест пренесени, те щяха да описват смятане, което не се е случило");
    NOT_COPIED.put(
        "bgGeneratorGaps",
        "същият обход и същият довод като bgGeneratorNotes: отказите са на генератор, който върху"
            + " копието изобщо не се пуска");
    NOT_COPIED.put(
        "bgGeneratorRefusalCount",
        "броячът върви с присъдите: пренесен сам, той щеше да твърди откази, чийто текст го няма"
            + " никъде на този фиш");
    NOT_COPIED.put(
        "bgGeneratedAt",
        "датата затваря ОНЗИ обход и различава „нула откази“ от „генераторите не са пускани“."
            + " Пренесена, тя щеше да твърди, че копието е минало през генератори");
    NOT_COPIED.put(
        "bgSickDaysRefusal",
        "отказът е на ОБХОДА на генераторите върху оригинала, а копието не го пуска. Дните на"
            + " копието идват замразени от оригинала, който е потвърден — тоест без отказ —, а при"
            + " пристигнал лист applyArrival отказва и не ражда копие");
    NOT_COPIED.put(
        "bgArt177Refusal",
        "отказът по чл. 177 е на ОБХОДА на генераторите върху оригинала, а копието не пуска"
            + " генератора по чл. 177 — перо 165 идва замразено от оригинала, който е потвърден,"
            + " тоест без отказ (A8)");
    NOT_COPIED.put(
        "bgCalcFingerprint",
        "отпечатъкът описва ПРЕСМЯТАНЕТО на оригинала. Копието не се пресмята от генераторите"
            + ", тоест пренесен, той щеше да твърди пресмятане, каквото върху копието"
            + " няма");
    NOT_COPIED.put(
        "bgGrossTotal",
        "СБОР на перата, а перата се копират. recompute(copy) го изчислява наново от тях — пренесен,"
            + " той щеше да е второ число за същата сметка и да се размине при първата промяна");
    NOT_COPIED.put(
        "bgTaxBase",
        "СМЯТА СЕ от копираните пера (recompute → taxBaseHeader, ЗДДФЛ чл. 42 ал. 2 и ал. 3)."
            + " Пренесена, основата щеше да е второ число за същата сметка");
    NOT_COPIED.put(
        "bgTaxAmount",
        "СМЯТА СЕ от данъчната основа и ставката за периода (recompute). Пренесен, данъкът щеше да"
            + " оцелее и след промяна на перата по копието — точно което корекцията прави");
    NOT_COPIED.put(
        "bgNetTotal",
        "СМЯТА СЕ от перата (recompute). ⚠️ И ТОЧНО ТО НЕ БИВА да се пренася: retroDelta сравнява"
            + " нетото на копието с това на оригинала, тоест преписано, разликата щеше да е нула"
            + " винаги и към лицето нямаше да тръгне нищо");
    NOT_COPIED.put(
        "bgEmployerCost",
        "СМЯТА СЕ от перата в групи 1xx/9xx и работодателските 5xx (recompute) — сбор, не вход");
    NOT_COPIED.put(
        "bgSocialExpenseTaxBase",
        "СМЯТА СЕ от перата за социални разходи и капачката за периода (recompute). Пренесена, тя"
            + " щеше да носи капачка от друг период");
    NOT_COPIED.put(
        "bgIsHistoricalImport",
        "признакът описва КАК е влязъл оригиналът (миграция), а не какъв е копието. Пренесен, той"
            + " щеше да заключи и генераторите на копие, което не е внасяно отникъде");
  }

  // ═══ ПОКРИТИЕТО ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Всяко скаларно поле на фиша е ИЛИ копирано в retroCopyOf, ИЛИ изрично изключено")
  void everyScalarFieldHasADecision() throws IOException {
    Set<String> fields = domainFields();
    assertTrue(
        fields.size() > 30,
        "котвата: очаквам десетки полета, намерих " + fields.size() + " — regex-ът е счупен");

    Set<String> copied = copiedInRetroCopyOf();
    assertTrue(
        copied.size() > 10,
        "котвата: не намирам copy.set* в retroCopyOf — парсерът чете друг метод. Намерени: "
            + copied);

    List<String> undecided = new ArrayList<>();
    for (String field : fields) {
      if (!copied.contains(field) && !NOT_COPIED.containsKey(field)) {
        undecided.add(field);
      }
    }
    if (!undecided.isEmpty()) {
      fail(
          "ПОЛЕ БЕЗ РЕШЕНИЕ ЗА РЕТРО-КОПИЕТО: "
              + undecided
              + ".\n  ⚠️ Пропуснато поле НЕ пада никъде — генерираният getter връща стойност по"
              + " подразбиране и копието носи нула, празно или false. Точно така коригиращата Д1"
              + " излизаше с празни т. 16.А и т. 16.2.\n  Поправка: или добави"
              + " copy.setXxx(original.getXxx()) в BgPaySlipService#retroCopyOf, или впиши полето в"
              + " NOT_COPIED ТУК, с причина.");
    }
  }

  @Test
  @DisplayName("⚠️ Списъкът с изключения не пази полета, които вече не съществуват")
  void noExceptionOutlivesItsField() throws IOException {
    Set<String> fields = domainFields();
    Set<String> stale = new TreeSet<>(NOT_COPIED.keySet());
    stale.removeAll(fields);
    assertEquals(
        new TreeSet<String>(),
        stale,
        "⚠️ Изключение за поле, което домейнът НЕ носи — преименувано или махнато. Оставено, то учи"
            + " следващия читател, че решението още важи.");
  }

  @Test
  @DisplayName("⚠️ Всяко изключение носи ПРИЧИНА, а не само име")
  void everyExceptionCarriesItsReason() {
    List<String> thin = new ArrayList<>();
    for (Map.Entry<String, String> e : NOT_COPIED.entrySet()) {
      if (e.getValue() == null || e.getValue().trim().length() < 20) {
        thin.add(e.getKey());
      }
    }
    assertEquals(
        new ArrayList<String>(),
        thin,
        "изключение без причина изглежда като решение, без да е такова");
  }

  @Test
  @DisplayName("⚠️ Тринайсетте замразени полета СА в копието, и дискриминаторът е ПОСЛЕДЕН")
  void theTwelveFrozenFieldsAreCopiedWithTheDiscriminatorLast() throws IOException {
    List<String> order = copiedOrder();
    List<String> frozen =
        Arrays.asList(
            "bgSickDaysEmployer",
            "bgSickDaysDoo",
            "bgSickDaysNoBenefit",
            "bgLeaveDays161",
            "bgLeaveDays162",
            "bgLeaveDays163",
            "bgLeaveDays164",
            "bgLeaveDays165",
            "bgLeaveDays16A",
            "bgLeaveDaysOutside",
            "bgOtherPayerInsurableIncome",
            "bgNoOtherPayerIncome",
            "bgLeaveDaysFrozenAt");
    for (String field : frozen) {
      assertTrue(order.contains(field), "липсва в retroCopyOf: " + field);
    }
    for (String field : frozen) {
      if ("bgLeaveDaysFrozenAt".equals(field)) {
        continue;
      }
      assertTrue(
          order.indexOf(field) < order.indexOf("bgLeaveDaysFrozenAt"),
          "⚠️ ДИСКРИМИНАТОРЪТ bgLeaveDaysFrozenAt ТРЯБВА ДА Е СЛЕД „"
              + field
              + "“. Датата казва „на числата отгоре може да се вярва“; вдигната преди тях, всеки"
              + " провал по средата на блока оставя вдигната дата над непопълнени кофи и Д1 чете"
              + " нулите като ФАКТ. Последна, тя прави непълното копие равно на падане към"
              + " огледалната заявка — вчерашното поведение, а не тихо грешното.");
    }
  }

  @Test
  @DisplayName(
      "⚠️ Признакът за авто-попълване се занулява ИЗРИЧНО, а не се оставя на подразбирането")
  void theAutoFillFlagIsExplicitlyClearedOnTheCopy() throws IOException {
    assertTrue(
        source().contains("copy.setBgAutoFillBaseSalary(false)"),
        "⚠️ Ретро-копието НИКОГА не бива да извежда заплатата наново: тя се чете от договора и"
            + " анексите му ПО ДАТА, а корекцията се прави месеци по-късно и по ДНЕШНИТЕ анекси би"
            + " дала друго число за онзи месец. Без изричното зануляване това се пази по СЛУЧАЙНОСТ"
            + " — непренесеният признак се чете FALSE от генерирания getter и applyBaseSalary"
            + " излиза само защото bgBaseSalary е ненулево.");
    assertTrue(
        NOT_COPIED.containsKey("bgAutoFillBaseSalary")
            || copiedOrder().contains("bgAutoFillBaseSalary"),
        "полето трябва да има решение като всяко друго — тук то е „пише се изрично false“");
  }

  // ═══ И ПЕРАТА, ПО СЪЩИЯ ДОВОД ════════════════════════════════════════════

  /**
   * ⚠️ СЪЩИЯТ ПАЗАЧ, ЕДНО НИВО ПО-НАДОЛУ — и той вече хвана един пропуск. {@code copyOf} копираше
   * 13 от 14-те скаларни полета на перото и изпускаше {@code bgHealthBaseKind}: редът по ЗЗО чл. 40
   * ал. 1 т. 5 се четеше на копието като ОБИКНОВЕНА здравна вноска, т. 17 и т. 18 излизаха празни,
   * а сумата отиваше в т. 22.1. Намерено от {@code BgRetroFrozenDaysTest}, закова се тук.
   *
   * <p>Правилото на метода е обявено в собствения му javadoc — „копието трябва да е това, което
   * оригиналът Е БИЛ“ — тоест тук изключения НЯМА: всяко скаларно поле се копира.
   */
  @Test
  @DisplayName("⚠️ Всяко скаларно поле на ПЕРОТО се копира от copyOf — изключения няма")
  void everyLineFieldIsCopied() throws IOException {
    Set<String> fields = fieldsOf(LINE_DOMAIN);
    assertTrue(fields.size() > 10, "котвата: очаквам над десет полета, намерих " + fields.size());

    Set<String> copied = new LinkedHashSet<>(assignedIn("protected BgPaySlipLine copyOf("));
    List<String> missing = new ArrayList<>();
    for (String field : fields) {
      if (!copied.contains(field)) {
        missing.add(field);
      }
    }
    assertEquals(
        new ArrayList<String>(),
        missing,
        "⚠️ ПОЛЕ НА ПЕРОТО, КОЕТО КОПИЕТО ГУБИ. Правилото на copyOf е „копието трябва да е това,"
            + " което оригиналът Е БИЛ“ — пропуснатото поле не пада никъде, а се чете като"
            + " подразбиране. Ако ново поле наистина НЕ бива да се пренася, това е промяна на"
            + " правилото на метода и иска свой javadoc, не тих пропуск.");
  }

  // ═══ ПАРСЕРИТЕ ═══════════════════════════════════════════════════════════

  private static Set<String> domainFields() throws IOException {
    return fieldsOf(DOMAIN);
  }

  private static Set<String> fieldsOf(Path domain) throws IOException {
    assertTrue(Files.exists(domain), "не намирам домейна: " + domain);
    Matcher m = FIELD.matcher(new String(Files.readAllBytes(domain), StandardCharsets.UTF_8));
    Set<String> out = new LinkedHashSet<>();
    while (m.find()) {
      out.add(m.group(2));
    }
    return out;
  }

  private static String source() throws IOException {
    assertTrue(Files.exists(SERVICE), "не намирам услугата: " + SERVICE);
    return new String(Files.readAllBytes(SERVICE), StandardCharsets.UTF_8);
  }

  /** Имената на полетата, присвоени в тялото на {@code retroCopyOf}, В РЕДА на присвояване. */
  private static List<String> copiedOrder() throws IOException {
    return assignedIn("protected BgPaySlip retroCopyOf(");
  }

  /** Имената на полетата, присвоени в тялото на посочения метод, В РЕДА на присвояване. */
  private static List<String> assignedIn(String signature) throws IOException {
    String body = bodyOf(signature);
    Matcher m = Pattern.compile("copy\\.set(\\w+)\\(").matcher(body);
    List<String> out = new ArrayList<>();
    while (m.find()) {
      String name = m.group(1);
      out.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
    }
    return out;
  }

  private static Set<String> copiedInRetroCopyOf() throws IOException {
    Set<String> copied = new LinkedHashSet<>(copiedOrder());
    // ⚠️ РЕЖИМЪТ НА ОСИГУРЯВАНЕ СЕ КОПИРА ПРЕЗ ЕДНО МЯСТО — BgPayrollRegime#copy, — а не поле по
    // поле в retroCopyOf. Броят се сетърите в САМИЯ помощник, и то само ако retroCopyOf наистина го
    // вика: махнато извикване прави всичките осемнайсет полета „неизвестни“ и тестът почервенява.
    String retroCopyOf = bodyOf("protected BgPaySlip retroCopyOf(");
    if (retroCopyOf.contains("BgPayrollRegime.copy(original, copy)")) {
      String helper = new String(Files.readAllBytes(REGIME), StandardCharsets.UTF_8);
      Matcher m = Pattern.compile("\\b(?:b|to)\\.set(BgRegime\\w+)\\(").matcher(helper);
      while (m.find()) {
        String name = m.group(1);
        copied.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
      }
    }
    return copied;
  }

  /**
   * Тялото на метода — от подписа до затварящата скоба на същото ниво.
   *
   * <p>⚠️ Реже се по ПОДПИС, а не по „от началото до следващия {@code protected}“: вложен клас или
   * лямбда вътре в метода биха прекъснали второто на грешно място и парсерът щеше да вижда половин
   * метод, без да се оплаче.
   */
  private static String bodyOf(String signature) throws IOException {
    String code = source();
    int start = code.indexOf(signature);
    assertTrue(start > 0, "не намирам метода — преименуван ли е? " + signature);
    int brace = code.indexOf('{', start);
    int depth = 0;
    for (int i = brace; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return code.substring(brace, i + 1);
        }
      }
    }
    return fail("незатворено тяло — парсерът не може да реже: " + signature);
  }
}

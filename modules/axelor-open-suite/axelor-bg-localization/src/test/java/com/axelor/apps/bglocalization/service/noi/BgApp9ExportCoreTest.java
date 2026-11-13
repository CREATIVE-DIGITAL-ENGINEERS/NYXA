package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗНОСНИЯТ КОМПЛЕКТ — дневникът и влизането на файла през ОБЩОТО ЯДРО.
 *
 * <p><b>⚠️ КАКВО СЕ ПРОВЕРЯВА ТУК И КАКВО НЕ.</b> Модулът няма mock-подпора (виж {@code
 * BgApp9DeclarationService}), затова {@code BgApp9ExportService} е разделен нарочно: заявките и
 * записът са в него, а сметките, които има смисъл да се доказват, са ЧИСТИ функции и се проверяват
 * тук без Postgres. Заявката, която избира удостоверенията за прозореца, се сверява СТРУКТУРНО при
 * ръчното пускане на приложението, не тук.
 */
class BgApp9ExportCoreTest {

  @Test
  @DisplayName("⚠️ Дневникът дели дните по ПЛАТЕЦ, докато файлът носи техния СБОР")
  void journalKeepsThePayerSplitThatTheFileMerges() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.standardLines(sick); // 2 осигурител + 4 НОИ

    int[] days = BgApp9ExportService.daysByPayer(sick);

    assertEquals(2, days[0], "дни за сметка на осигурителя (чл. 40 ал. 5 КСО)");
    assertEquals(4, days[1], "дни за сметка на ДОО");

    // Същите редове във файла дават ЕДНО число — официалният ред не дели по платец.
    List<BgApp9Point9.Month> months =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), null, "01");
    assertEquals(6, months.get(0).days);
    assertEquals(days[0] + days[1], months.get(0).days, "описът и файлът трябва да се връзват");
  }

  @Test
  @DisplayName("Лист без редове дава нули в описа, а не изключение")
  void aLeaveWithoutLinesGivesZeroes() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    int[] days = BgApp9ExportService.daysByPayer(sick);
    assertEquals(0, days[0]);
    assertEquals(0, days[1]);
    assertEquals(0, BgApp9ExportService.daysByPayer(null)[0]);
  }

  @Test
  @DisplayName("Липсите се разпределят по документа, който ги е породил")
  void gapsAreAttributedToTheirDocument() {
    BgApp9Declaration broken = BgApp9Fixture.declaration();
    broken.setBgP4InsuredTypeCode(null);

    BgSickLeave second =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0009999", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 25));
    BgApp9Fixture.standardLines(second);
    BgApp9Declaration good = BgApp9Fixture.declarationFor(second);
    // Липсите се закачат по id — в базата всяко удостоверение има свое.
    broken.setId(1L);
    good.setId(2L);

    List<BgApp9Declaration> both = BgApp9Fixture.one(broken);
    both.add(good);

    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), both, 1);
    assertTrue(emission.isRefused());

    // ⚠️ Изправният лист остава в описа с НУЛА липси — иначе операторът би търсил грешка и в него.
    assertFalse(BgApp9ExportService.gapsOf(emission, broken).isEmpty());
    assertTrue(BgApp9ExportService.gapsOf(emission, good).isEmpty());
  }

  @Test
  @DisplayName(
      "Лице с два договора: еднакъв етикет „лист № · име“, липсите на едното НЕ са на другото")
  void gapsFollowTheDeclarationIdNotTheLabel() {
    // Един болничен лист → по едно удостоверение за ВСЕКИ договор на лицето: същият номер, същото
    // име, тоест същият етикет.
    BgApp9Declaration broken = BgApp9Fixture.declaration();
    broken.setBgP4InsuredTypeCode(null);
    broken.setId(11L);
    BgApp9Declaration good = BgApp9Fixture.declaration();
    good.setId(12L);
    assertEquals(BgApp9MandatoryPoints.label(broken), BgApp9MandatoryPoints.label(good));

    List<BgApp9Declaration> both = BgApp9Fixture.one(broken);
    both.add(good);
    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), both, 1);
    assertTrue(emission.isRefused());

    assertFalse(BgApp9ExportService.gapsOf(emission, broken).isEmpty());
    assertTrue(
        BgApp9ExportService.gapsOf(emission, good).isEmpty(),
        "изрядното удостоверение не носи липсите на другия договор");
  }

  @Test
  @DisplayName("⚠️ Файлът към НОИ влиза през СЪЩОТО ядро, което носи и файловете към НАП")
  void theNoiFileGoesThroughTheSharedCore() {
    BgApp9Emission emission =
        BgApp9Emitter.emit(
            BgApp9Fixture.context(), BgApp9Fixture.one(BgApp9Fixture.declaration()), 1);

    RegulatoryFile file =
        new RegulatoryFile(
            Institution.NOI,
            emission.getFileName(),
            "text/xml",
            emission.getXml().getBytes(StandardCharsets.UTF_8));

    assertEquals(Institution.NOI, file.getInstitution());
    assertEquals("Pril9_12062026_1.xml", file.getFileName());
    assertEquals(
        RegulatoryDigest.sha256Hex(emission.getXml().getBytes(StandardCharsets.UTF_8)),
        file.getSha256(),
        "отпечатъкът трябва да е на ТОЧНО тези байтове");
    assertEquals(64, file.getSha256().length());
  }

  @Test
  @DisplayName("⚠️ Емисията е ДЕТЕРМИНИСТИЧНА — два пъти същият вход дава същия отпечатък")
  void emissionIsDeterministic() {
    // Без това свойство отпечатъкът не пази нищо: файл, чието съдържание зависи от часовника,
    // получава нов хеш при всяко генериране и „неизменяем“ става дума без покритие. Затова датата
    // на износа и часът на записа влизат ПРЕЗ контекста, а не се четат вътре в емитера.
    BgApp9EmitterContext context = BgApp9Fixture.context();

    String first =
        BgApp9Emitter.emit(context, BgApp9Fixture.one(BgApp9Fixture.declaration()), 1).getXml();
    String second =
        BgApp9Emitter.emit(context, BgApp9Fixture.one(BgApp9Fixture.declaration()), 1).getXml();

    assertEquals(first, second);
    assertEquals(
        RegulatoryDigest.sha256Hex(first.getBytes(StandardCharsets.UTF_8)),
        RegulatoryDigest.sha256Hex(second.getBytes(StandardCharsets.UTF_8)));

    // А промяна в СЪДЪРЖАНИЕТО мени отпечатъка — иначе той не би различавал два различни файла.
    BgApp9Declaration changed = BgApp9Fixture.declaration();
    changed.setCertificateSeqNo(Integer.valueOf(2));
    changed.setBgP5RelationshipCount(Integer.valueOf(2));
    String third = BgApp9Emitter.emit(context, BgApp9Fixture.one(changed), 1).getXml();
    assertNotEquals(first, third);
  }
}

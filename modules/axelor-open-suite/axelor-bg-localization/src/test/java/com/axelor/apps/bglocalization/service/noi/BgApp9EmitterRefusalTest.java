package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЕМИСИЯТА СПИРА И КАЗВА ЗАЩО — нула тихи непълни файлове.
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ КЛАС Е НАЙ-ВАЖНИЯТ В Ф2.</b> Схемата НЕ пази пълнотата: всеки елемент в нея е
 * структурно задължителен, но празнотата се изразява със СТОЙНОСТ — празен низ или кода 9 „не е
 * избрано“. Файл, в който т. 1, т. 2, т. 4 и т. 26 са попълнени с 9, минава {@code Pril9.xsd} без
 * нито едно съобщение. Тоест емитер, който при липсващ отговор пише 9 „за да не пада“, произвежда
 * технически валиден файл, който декларира пред НОИ, че никое обстоятелство не е избрано — и лицето
 * получава отказ по обезщетение, което му се полага.
 *
 * <p><b>Всеки свидетел тук е ПЪЛНАТА фикстура МИНУС едно поле.</b> Така се доказва, че спира точно
 * това поле, а не че непълен запис изобщо спира.
 *
 * <p><b>⚠️ И ПОСЛЕДНИЯТ СВИДЕТЕЛ Е ЗА КОДА, А НЕ ЗА ДАННИТЕ:</b> при отказ {@code getXml()} ХВЪРЛЯ.
 * Това е урокът от четирите генератора на фиша, направен неизбежен — там присъдите се връщаха и се
 * ИЗХВЪРЛЯХА безнаказано, защото повикващият получаваше и стойност, и обяснение и можеше да вземе
 * само първото. Тук при отказ ПРОСТО НЯМА какво друго да се вземе.
 */
class BgApp9EmitterRefusalTest {

  private static BgApp9Emission emit(BgApp9Declaration d) {
    return BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);
  }

  /** Има ли отказ, който сочи този елемент по схемата. */
  private static boolean mentionsTag(BgApp9Emission emission, String tag) {
    for (BgApp9Gap gap : emission.getGaps()) {
      if (tag.equals(gap.tag)) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("Т. 4 без код за вид осигурен СПИРА емисията и назовава полето")
  void missingInsuredTypeStopsTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP4InsuredTypeCode(null);

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused(), "емисията произведе файл при липсваща т. 4");
    assertTrue(mentionsTag(emission, "TypeOfInsured"), emission.gapReport());
    assertTrue(
        emission.gapReport().contains("т. 4"),
        "отказът не назовава точката: " + emission.gapReport());
  }

  @Test
  @DisplayName("⚠️ Код за вид осигурен ИЗВЪН набора на образеца също спира — а е валиден за Д1")
  void insuredTypeOutsideTheFormsNomenclatureStopsTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    // 14 е валиден код по Наредба Н-8 и НЕ Е в изброяването на Pril9.xsd. Преписан мълчаливо, той
    // би дал файл, който пада на СТРУКТУРНА проверка при НОИ — тоест без обяснение за оператора.
    d.setBgP4InsuredTypeCode("14");

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(emission.gapReport().contains("14"), emission.gapReport());
  }

  @Test
  @DisplayName("Т. 1 без отговор СПИРА — а не се записва като 9 „не е избрано“")
  void unresolvedPointOneStopsTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP1Insured(null);

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(mentionsTag(emission, "RiskInsured"), emission.gapReport());
  }

  @Test
  @DisplayName("⚠️ Т. 2 и т. 3 БЕЗ отговор в нито една от двете СПИРАТ")
  void bothStayPointsUnansweredStopTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP2SixMonthStaySelect("NOT_APPLICABLE");
    d.setBgP3TwelveMonthStaySelect("NOT_APPLICABLE");

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(mentionsTag(emission, "SixMonthsInsurance"), emission.gapReport());
  }

  @Test
  @DisplayName("⚠️ Т. 2 и т. 3 с отговор в ОБЕТЕ също спират — контролът допуска само едната")
  void bothStayPointsAnsweredStopTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP2SixMonthStaySelect("MET");
    d.setBgP3TwelveMonthStaySelect("MET");

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(emission.gapReport().contains("САМО в едната"), emission.gapReport());
  }

  @Test
  @DisplayName("Т. 9 без нито един ред СПИРА и сочи ПРЕСМЯТАНЕТО НА ФИША")
  void missingPointNineLinesStopTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().getBgPeriodLineList().clear();

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(mentionsTag(emission, "SickLeaveMonths"), emission.gapReport());
    assertTrue(
        emission.gapReport().contains("ПРЕСМЯТАНЕ НА ФИША"),
        "отказът не казва КЪДЕ се поправя: " + emission.gapReport());
  }

  @Test
  @DisplayName("Липсващ номер на болничния лист СПИРА и казва кое от трите полета липсва")
  void missingChartNumberStopsTheEmission() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setBgSeries(null);

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(mentionsTag(emission, "PatientsChartNumber"), emission.gapReport());
    assertTrue(emission.gapReport().contains("липсва серия"), emission.gapReport());
  }

  @Test
  @DisplayName("⚠️ Е-болничен с кирилско Е в „Номер“ СПИРА и отказът казва, че буквата е кирилска")
  void electronicNumberWithCyrillicEStopsAndSaysSo() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setBgSeries(null);
    d.getSickLeave().setBgYear(null);
    d.getSickLeave().setBgDocumentNumber("\u0415" + "20260000001");

    BgApp9Emission emission = emit(d);

    assertTrue(emission.isRefused());
    assertTrue(mentionsTag(emission, "PatientsChartNumber"), emission.gapReport());
    assertTrue(emission.gapReport().contains("буквата е кирилска"), emission.gapReport());
    assertFalse(emission.gapReport().contains("липсва серия"), emission.gapReport());
  }

  @Test
  @DisplayName("Е-болничен с латинско E в „Номер“ НЕ спира по номера, без серия и година")
  void electronicNumberWithLatinEPassesTheChartNumberCheck() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.getSickLeave().setBgSeries(null);
    d.getSickLeave().setBgYear(null);
    d.getSickLeave().setBgDocumentNumber("\u0045" + "20260000001");

    BgApp9Emission emission = emit(d);

    assertFalse(mentionsTag(emission, "PatientsChartNumber"), emission.gapReport());
  }

  @Test
  @DisplayName("Т. 25 СПИРА при обикновен код, а при 12/13/96 НЕ спира")
  void employerRegistryIsMandatoryExceptForThreeCodes() {
    BgApp9Declaration ordinary = BgApp9Fixture.declaration();
    ordinary.getSickLeave().setBgEmployerReceivedNo(null);
    ordinary.getSickLeave().setBgEmployerReceivedDate(null);

    BgApp9Emission refused = emit(ordinary);
    assertTrue(refused.isRefused(), "т. 25 е задължителна при вид осигурен 01");
    assertTrue(mentionsTag(refused, "NumberInEmployerRegistry"), refused.gapReport());

    // ⚠️ Същият празен лист, но с код 13 — контролът изрично освобождава 12, 13 и 96.
    BgApp9Declaration exempt = BgApp9Fixture.declaration();
    exempt.getSickLeave().setBgEmployerReceivedNo(null);
    exempt.getSickLeave().setBgEmployerReceivedDate(null);
    exempt.setBgP4InsuredTypeCode("13");

    BgApp9Emission passed = emit(exempt);
    assertFalse(passed.isRefused(), passed.gapReport());
  }

  @Test
  @DisplayName("⚠️ ТП на НОИ липсва → спира ЦЕЛИЯТ файл, не един документ")
  void missingNssiRegionalOfficeStopsTheWholeFile() {
    BgApp9EmitterContext broken =
        BgApp9EmitterContext.builder()
            .bulstatKasa(BgApp9Fixture.COMPANY_BULSTAT)
            .bulName("Никса ЕООД")
            .address("София-град", "Столична", "София", "1000", "ул. 1", null, null, null)
            .representative("Иван", "Георгиев", "Петров", "Управител", "София")
            .certificateDate(java.time.LocalDate.of(2026, 6, 10))
            .exportDate(java.time.LocalDate.of(2026, 6, 12))
            .source(BgApp9Fixture.DEVELOPER_EIK)
            .build();

    BgApp9Emission emission =
        BgApp9Emitter.emit(broken, BgApp9Fixture.one(BgApp9Fixture.declaration()), 1);

    assertTrue(emission.isRefused());
    boolean fileLevel = false;
    for (BgApp9Gap gap : emission.getGaps()) {
      if (BgApp9Gap.FILE_LEVEL.equals(gap.document) && "NSSI_RO".equals(gap.tag)) {
        fileLevel = true;
      }
    }
    assertTrue(fileLevel, "липсата не е обявена като заглавна: " + emission.gapReport());
  }

  @Test
  @DisplayName("⚠️ ВСИЧКИТЕ липси излизат наведнъж, а не първата")
  void allGapsAreCollectedNotJustTheFirst() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP4InsuredTypeCode(null);
    d.setBgP1Insured(null);
    d.setCertificateSeqNo(null);

    BgApp9Emission emission = emit(d);

    assertTrue(
        emission.gapCount() >= 3, "операторът трябва да поправи ВЕДНЪЖ: " + emission.gapReport());
    assertTrue(mentionsTag(emission, "TypeOfInsured"));
    assertTrue(mentionsTag(emission, "RiskInsured"));
    assertTrue(mentionsTag(emission, "DocNumber"));
  }

  @Test
  @DisplayName("⚠️ При отказ getXml() ХВЪРЛЯ — тихият непълен файл е невъзможен по устройство")
  void refusedEmissionHasNoFileAtAll() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP4InsuredTypeCode(null);

    BgApp9Emission emission = emit(d);

    assertEquals(0, emission.getDocumentCount());
    assertEquals(null, emission.getFileName());
    IllegalStateException thrown = assertThrows(IllegalStateException.class, emission::getXml);
    assertTrue(thrown.getMessage().contains("ОТКАЗАНА"), thrown.getMessage());
    // Съобщението носи ПЪРВАТА причина, за да не се налага втори прочит на друго място.
    assertTrue(thrown.getMessage().contains("TypeOfInsured"), thrown.getMessage());
  }

  @Test
  @DisplayName("Всеки отказ носи документ, точка, елемент по схемата и МЯСТО за поправка")
  void everyGapIsActionable() {
    BgApp9Declaration d = BgApp9Fixture.declaration();
    d.setBgP4InsuredTypeCode(null);
    d.getSickLeave().setBgIssueDate(null);

    List<BgApp9Gap> gaps = emit(d).getGaps();

    assertFalse(gaps.isEmpty());
    for (BgApp9Gap gap : gaps) {
      assertTrue(gap.document != null && !gap.document.isEmpty(), "липсва документ");
      assertTrue(gap.clause != null && !gap.clause.isEmpty(), "липсва точка");
      assertTrue(gap.tag != null && !gap.tag.isEmpty(), "липсва елемент по схемата");
      assertTrue(gap.reason != null && gap.reason.length() > 20, "причината не е изречение");
      assertTrue(gap.asNote().startsWith("[! ЛИПСВА]"), gap.asNote());
    }
  }

  @Test
  @DisplayName("⚠️ Празната фикстура дава САМО откази и НИТО ЕДИН байт")
  void emptyDeclarationProducesOnlyRefusals() {
    BgApp9Declaration bare = new BgApp9Declaration();
    bare.setSickLeave(
        BgApp9Fixture.sickLeave(
            "E",
            2026,
            "0001234",
            java.time.LocalDate.of(2026, 6, 5),
            java.time.LocalDate.of(2026, 6, 12)));

    BgApp9Emission emission = emit(bare);

    assertTrue(emission.isRefused());
    assertTrue(emission.gapCount() >= 6, emission.gapReport());
  }
}

package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestPayrollChainResolver.ChainResolution;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Краят на трудовото правоотношение се чете от ТЕРМИНАЛНИЯ запис на анексната верига.
 *
 * <p>⚠️ Случаите тук са снети от истинска изворова база, не са измислени: вериги PayrollID 18 →19
 * →1022 и 1030 →1035 са точно такива, при които краят по корена обявява живи договори за приключили
 * и връща старата заплата. Следата е през PayrollID — имена на лица тук не се записват.
 */
class MicroinvestPayrollChainResolverTest {

  private static MicroinvestSourceModels.Payroll row(
      int payrollId, Integer root, String from, String to) {
    MicroinvestSourceModels.Payroll p = new MicroinvestSourceModels.Payroll();
    p.payrollId = payrollId;
    p.annexPayrollId = root;
    p.fromDate = from == null ? null : LocalDate.parse(from);
    p.toDate = to == null ? null : LocalDate.parse(to);
    return p;
  }

  @Test
  @DisplayName("приключил корен + ОТВОРЕН анекс → отворен договор, не край по корена")
  void openAnnexAfterEndedRoot() {
    // 18 [01.07.2024..28.02.2025] → 19 [01.03.2025..31.12.2025] → 1022 [01.01.2026..
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll mid = row(19, 18, "2025-03-01", "2025-12-31");
    MicroinvestSourceModels.Payroll tail = row(1022, 18, "2026-01-01", null);

    ChainResolution r =
        MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, mid, tail));

    assertTrue(r.isResolved());
    assertTrue(r.isOpen(), "веригата завършва с отворен запис — договорът е ЖИВ");
    assertNull(r.endDate);
    assertEquals(1022, r.terminal.payrollId);
  }

  @Test
  @DisplayName("наистина прекратена верига дава края на ТЕРМИНАЛНИЯ запис, не на корена")
  void genuinelyTerminatedChain() {
    MicroinvestSourceModels.Payroll root = row(1024, 1024, "2026-02-16", "2026-04-30");
    MicroinvestSourceModels.Payroll tail = row(1031, 1024, "2026-05-01", "2026-05-17");
    tail.contrractTermDate = LocalDate.parse("2026-05-17");

    ChainResolution r = MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, tail));

    assertTrue(r.isResolved());
    assertFalse(r.isOpen());
    assertEquals(LocalDate.parse("2026-05-17"), r.endDate);
    assertEquals(LocalDate.parse("2026-05-17"), r.terminationDate);
    assertEquals(1031, r.terminal.payrollId);
  }

  @Test
  @DisplayName("ЗАЛИЧЕНИЯТ терминален запис не съществува — терминален става предшественикът му")
  void deletedTerminalRecordIsIgnored() {
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll mid = row(19, 18, "2025-03-01", "2025-12-31");
    MicroinvestSourceModels.Payroll tail = row(1022, 18, "2026-01-01", null);
    tail.isDeleted = true;

    ChainResolution r =
        MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, mid, tail));

    assertTrue(r.isResolved());
    assertFalse(r.isOpen(), "заличеният отворен анекс НЕ прави договора жив");
    assertEquals(LocalDate.parse("2025-12-31"), r.endDate);
    assertEquals(19, r.terminal.payrollId);
  }

  @Test
  @DisplayName("два терминални записа → ОТКАЗ, а не избор по реда на четене")
  void refusesTwoTerminalRecords() {
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll follows = row(19, 18, "2025-03-01", null);
    // Втори клон, който НЕ продължава никого — остава извън обхождането.
    MicroinvestSourceModels.Payroll orphan = row(20, 18, "2025-06-01", null);

    ChainResolution r =
        MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, follows, orphan));

    assertFalse(r.isResolved());
    assertNull(r.terminal);
    assertNotNull(r.refusal);
    assertTrue(r.refusal.contains("#20"), "отказът назовава непокрития ред: " + r.refusal);
  }

  @Test
  @DisplayName("скъсана връзка (дупка между периодите) → ОТКАЗ, не мълчаливо спиране при корена")
  void refusesBrokenLink() {
    // 19 започва ЕДИН ДЕН по-късно от очакваното — обхождането спира при корена и оставя 19 вън.
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll gap = row(19, 18, "2025-03-02", null);

    ChainResolution r = MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, gap));

    assertFalse(r.isResolved());
    assertNull(r.terminal);
    assertNotNull(r.refusal);
    assertTrue(r.refusal.contains("#19"), r.refusal);
  }

  @Test
  @DisplayName("две начала на една дата → ОТКАЗ: наследникът е двусмислен")
  void refusesAmbiguousSuccessor() {
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll a = row(19, 18, "2025-03-01", null);
    MicroinvestSourceModels.Payroll b = row(20, 18, "2025-03-01", "2025-12-31");

    ChainResolution r = MicroinvestPayrollChainResolver.resolve(root, Arrays.asList(root, a, b));

    assertFalse(r.isResolved());
    assertTrue(r.refusal.contains("2025-03-01"), r.refusal);
  }

  @Test
  @DisplayName("ред, който свършва преди да е започнал → ОТКАЗ")
  void refusesContradictoryDates() {
    MicroinvestSourceModels.Payroll root = row(18, 18, "2025-03-01", "2025-01-31");

    ChainResolution r =
        MicroinvestPayrollChainResolver.resolve(root, Collections.singletonList(root));

    assertFalse(r.isResolved());
    assertNotNull(r.refusal);
  }

  @Test
  @DisplayName("самотен отворен корен си остава отворен")
  void loneOpenRoot() {
    MicroinvestSourceModels.Payroll root = row(1025, 1025, "2025-12-01", null);

    ChainResolution r =
        MicroinvestPayrollChainResolver.resolve(root, Collections.singletonList(root));

    assertTrue(r.isOpen());
    assertEquals(1025, r.terminal.payrollId);
  }

  @Test
  @DisplayName("редът на подаване не влияе — обхождането е по прилежащия интервал")
  void ignoresRowOrder() {
    MicroinvestSourceModels.Payroll root = row(18, 18, "2024-07-01", "2025-02-28");
    MicroinvestSourceModels.Payroll mid = row(19, 18, "2025-03-01", "2025-12-31");
    MicroinvestSourceModels.Payroll tail = row(1022, 18, "2026-01-01", null);

    List<MicroinvestSourceModels.Payroll> shuffled = Arrays.asList(tail, root, mid);
    ChainResolution r = MicroinvestPayrollChainResolver.resolve(root, shuffled);

    assertTrue(r.isOpen());
    assertEquals(1022, r.terminal.payrollId);
  }
}

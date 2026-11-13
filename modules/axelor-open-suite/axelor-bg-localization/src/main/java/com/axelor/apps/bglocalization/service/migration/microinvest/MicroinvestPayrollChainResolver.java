package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Намира ДЕЙСТВИТЕЛНИЯ край на едно трудово правоотношение по цялата верига „основен запис +
 * анекси“ в Microinvest.
 *
 * <p><b>⚠️ Коренът на веригата НЕ носи края на договора.</b> В Microinvest всеки анекс е отделен
 * {@code Payroll} ред със собствен интервал {@code [FromDate, ToDate]}, а {@code AnnexPayrollID} на
 * всички редове сочи КОРЕНА, не непосредствения предшественик. Затова {@code root.toDate} е краят
 * само на ПЪРВИЯ период, не на правоотношението: PayrollID 18 приключва на 28.02.2025 г., но
 * веригата продължава през 19 (до 31.12.2025 г.) и завършва с ОТВОРЕНИЯ 1022. Взето наужким, {@code
 * root.toDate} затваря жив договор и — по-лошо — капва договорната заплата към отдавна отминала
 * дата, защото Microinvest пренася стойността на всеки ред напред за всеки месец (виж {@link
 * MicroinvestContractPayrollFields}).
 *
 * <p>Връзката между съседните редове е ПРИЛЕЖАЩИЯТ ИНТЕРВАЛ: наследникът започва в деня след като
 * предшественикът приключва ({@code successor.fromDate == predecessor.toDate + 1 ден}). Обхождането
 * тръгва от корена и следва точно тази връзка, така че редът на четене от файла не участва в
 * избора.
 *
 * <p><b>Отказва, вместо да гадае</b> — цикъл, скъсана връзка, повече от един терминален ред или
 * противоречиви дати оставят заварената дата в Nyxa непокътната и искат човек.
 */
final class MicroinvestPayrollChainResolver {

  private MicroinvestPayrollChainResolver() {}

  /** Присъдата за една верига. */
  static final class ChainResolution {

    /** Терминалният ДЕЙСТВАЩ запис на веригата — {@code null} при отказ. */
    final MicroinvestSourceModels.Payroll terminal;

    /**
     * Краят на правоотношението: {@code null} значи ОТВОРЕН договор (терминалният запис няма {@code
     * ToDate}), а не „не знам“ — „не знам“ се изразява с {@link #refusal}.
     */
    final LocalDate endDate;

    /** {@code ContrractTermDate} на терминалния запис. */
    final LocalDate terminationDate;

    /** Причината за отказ, или {@code null} при успех. */
    final String refusal;

    private ChainResolution(
        MicroinvestSourceModels.Payroll terminal,
        LocalDate endDate,
        LocalDate terminationDate,
        String refusal) {
      this.terminal = terminal;
      this.endDate = endDate;
      this.terminationDate = terminationDate;
      this.refusal = refusal;
    }

    boolean isResolved() {
      return refusal == null;
    }

    /** Отвореният договор е РЕШЕН, не неизвестен: веригата свършва с ред без край. */
    boolean isOpen() {
      return refusal == null && endDate == null;
    }

    static ChainResolution refused(String reason) {
      return new ChainResolution(null, null, null, reason);
    }

    static ChainResolution of(MicroinvestSourceModels.Payroll terminal) {
      return new ChainResolution(terminal, terminal.toDate, terminal.contrractTermDate, null);
    }
  }

  /**
   * @param root коренът на веригата (редът, чийто {@code payrollId} е ключът на веригата)
   * @param chain ВСИЧКИ редове на веригата, включително корена и заличените
   */
  static ChainResolution resolve(
      MicroinvestSourceModels.Payroll root, List<MicroinvestSourceModels.Payroll> chain) {

    if (root == null) {
      return ChainResolution.refused("веригата няма корен");
    }
    if (root.isDeleted) {
      // Заличен корен изобщо не стига дотук -- спира се по-рано (виж classifyChain). Отказът тук е
      // само предпазен колан, за да няма мълчаливо обхождане от несъществуващ ред.
      return ChainResolution.refused("коренът Payroll #" + root.payrollId + " е заличен в извора");
    }

    // ⚠️ Заличеният ред НЕ СЪЩЕСТВУВА. Изключва се преди обхождането, а не след него: ако
    // терминалният ред е заличен, терминален става предшественикът му и договорът е ПРИКЛЮЧИЛ.
    List<MicroinvestSourceModels.Payroll> live = new ArrayList<>();
    for (MicroinvestSourceModels.Payroll p : chain) {
      if (p != null && !p.isDeleted) {
        live.add(p);
      }
    }

    Map<LocalDate, MicroinvestSourceModels.Payroll> byStart = new HashMap<>();
    for (MicroinvestSourceModels.Payroll p : live) {
      if (p.fromDate == null) {
        return ChainResolution.refused(
            "Payroll #" + p.payrollId + " няма начална дата — веригата не може да се проследи");
      }
      if (p.toDate != null && p.toDate.isBefore(p.fromDate)) {
        return ChainResolution.refused(
            "Payroll #"
                + p.payrollId
                + " свършва ("
                + p.toDate
                + ") преди да е започнал ("
                + p.fromDate
                + ")");
      }
      MicroinvestSourceModels.Payroll clash = byStart.put(p.fromDate, p);
      if (clash != null) {
        return ChainResolution.refused(
            "Payroll #"
                + clash.payrollId
                + " и #"
                + p.payrollId
                + " започват на една и съща дата ("
                + p.fromDate
                + ") — наследникът е двусмислен");
      }
    }

    // Обхождане от корена по прилежащия интервал. Посетените се пазят, за да се хване цикъл.
    Set<Integer> visited = new LinkedHashSet<>();
    MicroinvestSourceModels.Payroll current = root;
    while (true) {
      if (!visited.add(current.payrollId)) {
        return ChainResolution.refused(
            "веригата се затваря в цикъл при Payroll #" + current.payrollId);
      }
      if (current.toDate == null) {
        break; // отворен терминален запис
      }
      MicroinvestSourceModels.Payroll next = byStart.get(current.toDate.plusDays(1));
      if (next == null) {
        break; // приключил терминален запис
      }
      current = next;
    }

    if (visited.size() != live.size()) {
      // Остава действащ ред, до който обхождането не стига: или втори терминален клон, или скъсана
      // връзка (пропуск/застъпване между интервалите). И в двата случая изборът би бил по реда на
      // четене — затова отказ.
      StringBuilder stranded = new StringBuilder();
      for (MicroinvestSourceModels.Payroll p : live) {
        if (!visited.contains(p.payrollId)) {
          if (stranded.length() > 0) {
            stranded.append(", ");
          }
          stranded
              .append('#')
              .append(p.payrollId)
              .append(" [")
              .append(p.fromDate)
              .append("..")
              .append(p.toDate == null ? "отворен" : p.toDate)
              .append(']');
        }
      }
      return ChainResolution.refused(
          "веригата от Payroll #"
              + root.payrollId
              + " не обхваща действащ(и) ред(ове) "
              + stranded
              + " — скъсана връзка или втори терминален запис, не се решава автоматично");
    }

    return ChainResolution.of(current);
  }
}

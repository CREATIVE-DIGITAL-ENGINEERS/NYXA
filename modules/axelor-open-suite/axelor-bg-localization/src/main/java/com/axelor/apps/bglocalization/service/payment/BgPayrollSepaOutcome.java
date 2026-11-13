package com.axelor.apps.bglocalization.service.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * КАКВО ИЗЛЕЗЕ ОТ ЕДИН ПРОБЕГ — числата и границите, показани на оператора.
 *
 * <p><b>⚠️ ГРАНИЦИТЕ СЕ ПОКАЗВАТ ДОРИ КОГАТО ВСИЧКО Е МИНАЛО.</b> Успешен пробег без доклад
 * отговаря само на „станало ли е“; въпросът, който човекът пред екрана всъщност има, е „ВСИЧКИ ЛИ
 * СА ВЪТРЕ“. Изключените фишове — вече платените, непотвърдените и нулевите — се броят ПО ПРИЧИНА и
 * се изброяват поименно там, където броят е малък, защото всяка от трите причини се поправя на
 * различен екран.
 *
 * <p><b>⚠️ И ЗАБЕЛЕЖКАТА ОТ СХЕМАТА Е ТУК, А НЕ В ЛОГА.</b> Виж {@link BgSepaXsdValidator}:
 * липсваща схема не отказва пробега, но мълчанието по въпроса „сверен ли е файлът“ е точно това,
 * което прави зеления екран по-уверен, отколкото е.
 */
public final class BgPayrollSepaOutcome {

  private final Long bankOrderId;
  private final String bankOrderSeq;
  private final String fileName;
  private final int lineCount;
  private final BigDecimal total;
  private final String currencyIso;
  private final LocalDate executionDate;
  private final String remittance;
  private final String senderIban;
  private final List<BgPayrollSepaCandidate> zeroNet;
  private final int alreadyOrdered;
  private final int notConfirmed;
  private final String xsdNote;

  public BgPayrollSepaOutcome(
      Long bankOrderId,
      String bankOrderSeq,
      String fileName,
      int lineCount,
      BigDecimal total,
      String currencyIso,
      LocalDate executionDate,
      String remittance,
      String senderIban,
      List<BgPayrollSepaCandidate> zeroNet,
      int alreadyOrdered,
      int notConfirmed,
      String xsdNote) {
    this.bankOrderId = bankOrderId;
    this.bankOrderSeq = bankOrderSeq;
    this.fileName = fileName;
    this.lineCount = lineCount;
    this.total = total;
    this.currencyIso = currencyIso;
    this.executionDate = executionDate;
    this.remittance = remittance;
    this.senderIban = senderIban;
    this.zeroNet = zeroNet == null ? new ArrayList<>() : zeroNet;
    this.alreadyOrdered = alreadyOrdered;
    this.notConfirmed = notConfirmed;
    this.xsdNote = xsdNote;
  }

  public Long getBankOrderId() {
    return bankOrderId;
  }

  public String getBankOrderSeq() {
    return bankOrderSeq;
  }

  public int getLineCount() {
    return lineCount;
  }

  public BigDecimal getTotal() {
    return total;
  }

  /** Едноредовото известие: само двете числа, които решават дали докладът да се прочете. */
  public String notice() {
    return "SEPA файлът е готов: "
        + lineCount
        + (lineCount == 1 ? " ред, " : " реда, ")
        + total.toPlainString()
        + " "
        + currencyIso
        + ". Прочети „Резултат и граници“.";
  }

  /** Целият разказ — показва се в полето, не в известие. */
  public String report() {
    StringBuilder text = new StringBuilder();
    text.append("ФАЙЛЪТ Е ГЕНЕРИРАН И ЗАКАЧЕН КЪМ ПЛАТЕЖНОТО НАРЕЖДАНЕ.\n")
        .append("⚠️ Нищо не е подадено към банка. Файлът се преглежда и се качва от ЧОВЕК, а")
        .append(" фишовете ОСТАВАТ „Потвърден“ — състоянието „Изплатен“ е отделно решение.\n\n");

    text.append("Нареждане: ").append(bankOrderSeq).append("\n");
    text.append("Файл: ")
        .append(fileName)
        .append(" (формат ")
        .append(BgPayrollSepaRules.FILE_FORMAT_SELECT)
        .append(")\n");
    text.append("Платец (IBAN): ").append(senderIban).append("\n");
    text.append("Дата на изпълнение: ").append(executionDate).append("\n");
    text.append("Основание на всеки ред: „").append(remittance).append("“\n");
    text.append("Редове: ").append(lineCount).append(" — по един на лице\n");
    text.append("Сбор (CtrlSum): ")
        .append(total.toPlainString())
        .append(" ")
        .append(currencyIso)
        .append("\n");
    text.append("Схема pain.001.001.03: ")
        .append(xsdNote == null ? "сверен, 0 грешки" : "НЕ Е СВЕРЕН — " + xsdNote)
        .append("\n");

    text.append("\n═══ ИЗКЛЮЧЕНИ ОТ ФАЙЛА ═══\n");
    if (zeroNet.isEmpty() && alreadyOrdered == 0 && notConfirmed == 0) {
      text.append("Няма. Всеки потвърден фиш за този период има свой ред.");
      return text.toString();
    }
    if (alreadyOrdered > 0) {
      text.append("\n• ")
          .append(alreadyOrdered)
          .append(" фиша вече са в друго платежно нареждане и НЕ се плащат втори път.")
          .append(" Виж полето „Платежно нареждане (SEPA)“ на фиша.");
    }
    if (notConfirmed > 0) {
      text.append("\n• ")
          .append(notConfirmed)
          .append(" фиша не са „Потвърден“ (чернова, изплатен или отказан) и не влизат в плащане.");
    }
    if (!zeroNet.isEmpty()) {
      text.append("\n• ")
          .append(zeroNet.size())
          .append(" с нулево нето — законен случай (цял месец неплатен отпуск), но SEPA не носи")
          .append(" нулеви преводи:");
      for (BgPayrollSepaCandidate candidate : zeroNet) {
        text.append("\n    – ")
            .append(candidate.getPersonName())
            .append(" (фиш ")
            .append(candidate.getSlipReference())
            .append(")");
      }
    }
    return text.toString();
  }
}

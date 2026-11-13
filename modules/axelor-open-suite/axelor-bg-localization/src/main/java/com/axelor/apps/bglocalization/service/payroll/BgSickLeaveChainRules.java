package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.hr.db.EmploymentContract;
import java.util.Objects;

/**
 * ВЕРИГАТА ОТ БОЛНИЧНИ ЛИСТОВЕ — кога продължението е законно свързано с предходния лист (A2).
 * Чиста функция над стойностите на двата записа.
 *
 * <p><b>Защо връзка.</b> Чл. 40 ал. 5 КСО дава на осигурителя първите ДВА работни дни от
 * неработоспособността — от ЦЕЛИЯ непрекъснат период, не от всеки лист. Продължението е част от
 * същия период и затова не открива нови два дни, а правото на обезщетение се определя към началото
 * му. Дотук фишът знаеше само, ЧЕ листът е продължение: при продължение от 1-во число първият лист
 * (от предходния месец) не влизаше в заявката и работодателят плащаше двата дни втори път.
 *
 * <p><b>Отказите — всеки е разминаване между две твърдения:</b>
 *
 * <ul>
 *   <li>отметка „продължение“ без връзка — не се знае НА КОЙ лист;
 *   <li>продължение с вид, чийто платец е осигурителят — видът твърди „първите дни са мои“, а
 *       връзката — „първите дни вече са били“;
 *   <li>връзка към лист по ДРУГ договор или на друго дружество — двата дни се дължат от осигурителя
 *       по ТОЗИ договор, а чужда верига е чужд период;
 *   <li>връзка към лист, който не е преди този — веригата върви само назад;
 *   <li>продължение, което започва преди КРАЯ на предходния лист — двата листа се застъпват, а един
 *       ден неработоспособност е един ден, не два;
 *   <li>продължение с ПРОПУСК — начало по-късно от деня след края на предходния лист. При
 *       прекъсване неработоспособността не е непрекъсната: листът е ПЪРВИЧЕН, не продължение, и
 *       осигурителят отново плаща първите дни по чл. 40 ал. 5 КСО. Връзката би ги отнела тихо.
 * </ul>
 */
public final class BgSickLeaveChainRules {

  private BgSickLeaveChainRules() {}

  /** Отказът за листа, или {@code null}, ако връзката (или липсата ѝ) е законна. */
  public static String refusal(BgSickLeave sheet) {
    if (sheet == null) {
      return null;
    }
    BgSickLeave previous = sheet.getBgContinuationOf();
    if (previous == null) {
      return Boolean.TRUE.equals(sheet.getBgIsContinuation())
          ? "листът е отбелязан като продължение, но не е посочено НА КОЙ лист (поле „Продължава"
              + " лист“). Без връзката фишът не може да намери началото на неработоспособността —"
              + " а от него зависят първите два дни по чл. 40 ал. 5 КСО и правото на обезщетение."
              + " Посочете предходния лист или махнете отметката."
          : null;
    }
    if (previous == sheet || (previous.getId() != null && previous.getId().equals(sheet.getId()))) {
      return "листът не може да продължава сам себе си.";
    }
    String contracts =
        contractMismatch(sheet.getEmploymentContract(), previous.getEmploymentContract());
    if (contracts != null) {
      return contracts;
    }
    if (sheet.getFromDate() != null
        && previous.getFromDate() != null
        && !previous.getFromDate().isBefore(sheet.getFromDate())) {
      return "посоченият предходен лист ("
          + previous.getFromDate()
          + ") не започва ПРЕДИ този ("
          + sheet.getFromDate()
          + ") — веригата върви само назад.";
    }
    if (sheet.getFromDate() != null
        && previous.getToDate() != null
        && !sheet.getFromDate().isAfter(previous.getToDate())) {
      return "продължението започва на "
          + sheet.getFromDate()
          + ", а предходният лист свършва на "
          + previous.getToDate()
          + " — двата листа се застъпват. Продължението започва СЛЕД края на листа, който"
          + " продължава; поправете датите на единия от двата.";
    }
    if (sheet.getFromDate() != null
        && previous.getToDate() != null
        && sheet.getFromDate().isAfter(previous.getToDate().plusDays(1))) {
      return "предходният лист свършва на "
          + previous.getToDate()
          + ", а продължението започва на "
          + sheet.getFromDate()
          + " — между тях има ПРОПУСК. Продължението започва в деня след края на листа, който"
          + " продължава. При прекъсване листът е ПЪРВИЧЕН, не продължение: осигурителят отново"
          + " плаща първите дни по чл. 40 ал. 5 КСО. Махнете връзката „Продължава лист“ или"
          + " поправете датите.";
    }
    BgAbsenceType type = sheet.getBgAbsenceType();
    if (type != null && BgSickLeaveEpisode.PAYER_EMPLOYER.equals(type.getPayer())) {
      return "листът е продължение, а видът „"
          + type.getName()
          + "“ е с платец осигурителя — тоест твърди, че първите два дни са негови. Първите дни са"
          + " на НАЧАЛОТО на веригата и вече са били; продължението е изцяло за ДОО. Изберете вида"
          + " ДОО от листа (напр. „Болнични ДОО“).";
    }
    return null;
  }

  private static String contractMismatch(EmploymentContract mine, EmploymentContract theirs) {
    if (mine == null || theirs == null) {
      return null;
    }
    if (mine == theirs || (mine.getId() != null && mine.getId().equals(theirs.getId()))) {
      return null;
    }
    Company myCompany = mine.getPayCompany();
    Company theirCompany = theirs.getPayCompany();
    boolean otherCompany =
        myCompany != null
            && theirCompany != null
            && !Objects.equals(myCompany.getId(), theirCompany.getId());
    return "посоченият предходен лист е по ДРУГ трудов договор"
        + (otherCompany ? " и на ДРУГО дружество" : "")
        + ". Първите два дни по чл. 40 ал. 5 КСО се дължат от осигурителя по ТОЗИ договор, а"
        + " веригата е по договор: посочете предходния лист по същия договор.";
  }
}

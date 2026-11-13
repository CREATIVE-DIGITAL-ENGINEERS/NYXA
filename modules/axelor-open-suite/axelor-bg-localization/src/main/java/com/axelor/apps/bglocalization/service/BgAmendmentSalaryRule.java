package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgContractAmendment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * КОЯ Е ОСНОВНАТА ЗАПЛАТА ЗА ТОЗИ ПЕРИОД — чистата половина, без база и без Guice.
 *
 * <p><b>⚠️ ДОГОВОРЪТ НОСИ БАЗАТА, АНЕКСИТЕ ЛЯГАТ ОТГОРЕ.</b> {@code
 * EmploymentContract.bgBaseSalary} е заплатата, КАКТО Е СКЛЮЧЕНА, и никой код не я пише. Всяко
 * изменение по чл. 119 ал. 1 (или едностранно увеличение по чл. 118 ал. 3) е РЕД в {@code
 * BgContractAmendment} със своя дата на действие. Обратното — анексът да презаписва договора — би
 * дало верен текущ месец и невъзстановимо минало: фиш за септември, направен след октомврийското
 * увеличение, щеше да се сметне с октомврийската заплата.
 *
 * <p><b>⚠️ ДАТАТА НА ДЕЙСТВИЕ Е т. 16А, НЕ т. 16.</b> {@code entryDate}, не {@code changeDate}:
 * споразумение, подписано на 20-ти и влизащо в сила от 1-ви следващ месец, носи две различни
 * стойности и парите следват втората. Взета датата на подписване, увеличението щеше да плати и
 * десетте дни преди да е било в сила.
 *
 * <p><b>⚠️ КАНДИДАТ Е САМО АНЕКС С ДАТА НА ДЕЙСТВИЕ И С НЕПРАЗНА ЗАПЛАТА.</b> Двете отсявания са
 * различни и всяко пази свой случай:
 *
 * <ul>
 *   <li>{@code newSalary.signum() == 0} — анекс за ДЛЪЖНОСТ, работно време или срок. Той не казва
 *       нищо за заплатата и не бива да я мени. ⚠️ {@code signum()}, не {@code == null}:
 *       генерираният getter за decimal връща {@code ZERO}, тоест проверка за null тук би била
 *       мъртъв код — същият капан, който вече ухапа копирането на заплатата върху фиша.
 *   <li>{@code entryDate == null} — анекс, който не може да се постави във времето. Такива са и
 *       ВСИЧКИТЕ посяти от миграцията 2026-09-12: тя пренася {@code new_salary} от договора, но
 *       {@code entry_date} остава NULL, защото {@code amendment_date} не казва от кога действа.
 *       Пропуснати тук, те не местят нито едно число на нито един заварен фиш. ⚠️ Това е носещо
 *       свойство, не съвпадение — пази го {@code BgAmendmentSeededRowTest}.
 * </ul>
 *
 * <p><b>⚠️ ДВАТА ОТКАЗА СЕ ПРОИЗНАСЯТ ПРЕДИ ИЗБОРА, И РЕДЪТ НЕ Е СВОБОДЕН.</b> Питани след него, те
 * щяха да мълчат точно когато има какво да кажат: месецът, разполовен от анекса, има и валиден
 * предходен кандидат, тоест изборът винаги би върнал старата заплата и половината месец щеше да се
 * плати по нея безмълвно.
 */
public final class BgAmendmentSalaryRule {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgAmendmentSalaryRule() {}

  /**
   * Дата за човешко око. {@code null} се пише с тире, а не гърми: {@code changeDate} е {@code
   * required} в домейна и NOT NULL в базата, но съобщение за отказ, което хвърля NPE, заменя
   * назованата причина с stack trace — тоест точно това, което целият този клас избягва.
   */
  private static String fmt(LocalDate date) {
    return date == null ? "—" : DATE.format(date);
  }

  /**
   * Заплатата в сила за периода, или обявена причина да няма такава.
   *
   * @param amendments анексите на договора — подадени, НЕ заредени тук; {@code null} се чете като
   *     празно
   * @param contractBase базата по договора; връща се, когато нито един анекс не е в сила
   * @param periodFrom първият ден на периода — той решава кое е „в сила“. {@code null} значи
   *     „периодът още не е избран“ и отговорът е базата: анекс не може да се постави във времето
   *     срещу период, който го няма
   * @param periodTo последният ден на периода — само за отказа при среда на месеца
   */
  public static BgSalaryInEffect inEffect(
      List<BgContractAmendment> amendments,
      BigDecimal contractBase,
      LocalDate periodFrom,
      LocalDate periodTo) {

    List<BgContractAmendment> candidates = salaryCandidates(amendments);
    if (candidates.isEmpty() || periodFrom == null) {
      return BgSalaryInEffect.contractBase(contractBase);
    }

    BgSalaryInEffect straddle = refuseStraddlingPeriod(candidates, periodFrom, periodTo);
    if (straddle != null) {
      return straddle;
    }

    List<BgContractAmendment> inForce = latestInForce(candidates, periodFrom);
    if (inForce.isEmpty()) {
      return BgSalaryInEffect.contractBase(contractBase);
    }
    if (inForce.size() > 1) {
      return refuseAmbiguous(inForce);
    }

    BgContractAmendment winner = inForce.get(0);
    return BgSalaryInEffect.fromAmendment(
        winner.getNewSalary(),
        winner,
        "Основна заплата "
            + winner.getNewSalary().toPlainString()
            + " по допълнително споразумение от "
            + fmt(winner.getChangeDate())
            + ", в сила от "
            + fmt(winner.getEntryDate())
            + " (основание т. 12 „"
            + winner.getBgChangeGround()
            + "“). Базата по договора остава непроменена.");
  }

  /**
   * Анексите, които изобщо говорят за заплата И могат да се поставят във времето.
   *
   * <p>Виж класовия javadoc защо двете отсявания са различни.
   */
  static List<BgContractAmendment> salaryCandidates(List<BgContractAmendment> amendments) {
    List<BgContractAmendment> out = new ArrayList<>();
    if (amendments == null) {
      return out;
    }
    for (BgContractAmendment a : amendments) {
      if (a != null && a.getEntryDate() != null && a.getNewSalary().signum() != 0) {
        out.add(a);
      }
    }
    return out;
  }

  /**
   * ОТКАЗ · АНЕКСЪТ РАЗПОЛОВЯВА ПЕРИОДА.
   *
   * <p>Фишът носи ЕДНА основна заплата, карвана по неплатени дни — две заплати в един месец нямат
   * къде да легнат. Пропорционалното разделяне (1–14 по старата, 15–31 по новата) е назована бъдеща
   * дупка и НЕ се строи тук; мълчаливият избор на едната от двете би надплатил или недоплатил
   * половин месец, без никой да разбере.
   *
   * <p><b>⚠️ ОТКАЗЪТ Е ЗА ЗАСЕГНАТИЯ МЕСЕЦ, НЕ ЗА АНЕКСА ЗАВИНАГИ.</b> Анекс в сила от 15.10
   * разполовява САМО октомври. За ноември нататък той е нормален кандидат и дава новата заплата; за
   * септември изобщо не е в сила. Отказ, вързан за анекса, щеше да спре всеки следващ фиш по този
   * договор до края на времето.
   *
   * <p>Границите: {@code periodFrom} НЕ разполовява (анекс от 1-во число важи за целия месец),
   * {@code periodTo} разполовява (анекс от 31-ви оставя тридесет дни по старата).
   */
  private static BgSalaryInEffect refuseStraddlingPeriod(
      List<BgContractAmendment> candidates, LocalDate periodFrom, LocalDate periodTo) {
    if (periodTo == null) {
      return null;
    }
    for (BgContractAmendment a : candidates) {
      LocalDate entry = a.getEntryDate();
      if (entry.isAfter(periodFrom) && !entry.isAfter(periodTo)) {
        return BgSalaryInEffect.refused(
            "Допълнително споразумение от "
                + fmt(a.getChangeDate())
                + " мени основното възнаграждение на "
                + a.getNewSalary().toPlainString()
                + " от "
                + fmt(entry)
                + " — НАСРЕД периода "
                + fmt(periodFrom)
                + "–"
                + fmt(periodTo)
                + ". Фишът носи една основна заплата и не може да плати месеца по две;"
                + " пропорционалното разделяне не е построено. Въведете сумата за този месец"
                + " на ръка в „Основна заплата“ (отметката за авто-попълване пада сама), или"
                + " поправете датата на влизане в сила, ако тя е на първо число.");
      }
    }
    return null;
  }

  /**
   * Кандидатите с НАЙ-КЪСНА дата на действие, която вече е настъпила.
   *
   * <p>Връща повече от един САМО при равенство на датата — виж {@link #refuseAmbiguous}.
   */
  private static List<BgContractAmendment> latestInForce(
      List<BgContractAmendment> candidates, LocalDate periodFrom) {
    List<BgContractAmendment> best = new ArrayList<>();
    LocalDate bestDate = null;
    for (BgContractAmendment a : candidates) {
      LocalDate entry = a.getEntryDate();
      if (entry.isAfter(periodFrom)) {
        continue;
      }
      if (bestDate == null || entry.isAfter(bestDate)) {
        bestDate = entry;
        best.clear();
        best.add(a);
      } else if (entry.equals(bestDate)) {
        best.add(a);
      }
    }
    return best;
  }

  /**
   * ОТКАЗ · ДВА АНЕКСА В СИЛА ОТ ЕДИН И СЪЩИ ДЕН.
   *
   * <p><b>⚠️ И ТОВА НЕ Е НЕВЪЗМОЖНО СЪСТОЯНИЕ.</b> Уникалният ключ на таблицата е (договор, дата на
   * споразумението, основание) — два реда с РАЗЛИЧНА дата на подписване могат законно да делят една
   * дата на влизане в сила. Избор по {@code id} или по реда на списъка би бил мълчалива монета
   * върху пари; редът на {@code fetch()} дори не е обещан.
   */
  private static BgSalaryInEffect refuseAmbiguous(List<BgContractAmendment> tied) {
    StringBuilder sb = new StringBuilder();
    for (BgContractAmendment a : tied) {
      if (sb.length() > 0) {
        sb.append("; ");
      }
      sb.append("от ")
          .append(fmt(a.getChangeDate()))
          .append(" на ")
          .append(a.getNewSalary().toPlainString());
    }
    return BgSalaryInEffect.refused(
        "Две или повече допълнителни споразумения менят основното възнаграждение от една и съща"
            + " дата ("
            + fmt(tied.get(0).getEntryDate())
            + "): "
            + sb
            + ". Не може да се каже коя е заплатата. Оставете едно от тях със заплата или"
            + " въведете сумата на ръка в „Основна заплата“.");
  }

  /**
   * ОТКАЗ ПРИ ЗАПИС НА ДОГОВОРА · БАЗАТА СЕ ВДИГА ВТОРИ ПЪТ.
   *
   * <p>Навикът, който този отказ лови: ТРЗ въвежда анекс за 1200,00 и по стар рефлекс вдига и
   * {@code bgBaseSalary} на 1200,00. Анексът тогава не защитава нищо — септември също става
   * 1200,00, защото базата е мръднала под него.
   *
   * <p><b>⚠️ СИГНАЛЪТ Е СЪВПАДЕНИЕТО, А НЕ „ПОЛЕТО Е ПИПАНО“.</b> Сравнение старо↔ново е недостъпно
   * при запис: {@code em.find()} връща същия управляван обект, вече с новата стойност, а JPQL
   * проекция се предхожда от flush. Съвпадението с {@code newSalary} на анекс е тесен, но точен
   * сигнал — поправка на първоначалната база на трето число минава безпрепятствено.
   *
   * @return изречението за отказ, или {@code null}, когато няма какво да се каже
   */
  public static String refuseBaseRestatingAmendment(
      BigDecimal contractBase, List<BgContractAmendment> amendments) {
    if (contractBase == null || contractBase.signum() == 0) {
      return null;
    }
    for (BgContractAmendment a : salaryCandidates(amendments)) {
      if (a.getNewSalary().compareTo(contractBase) == 0) {
        return "Основното месечно възнаграждение по договора е "
            + contractBase.toPlainString()
            + " — същата сума, която допълнително споразумение от "
            + fmt(a.getChangeDate())
            + " вече въвежда от "
            + fmt(a.getEntryDate())
            + ". Полето на договора е заплатата КАКТО Е СКЛЮЧЕНА и не се вдига с анекс:"
            + " вдигната, тя мени и вече отминалите месеци, за които старата заплата е вярната."
            + " Оставете базата каквато е и нека анексът носи новата сума.";
      }
    }
    return null;
  }
}

package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.hr.db.Employee;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ПРИЗНАКЪТ „ДЛЪЖНИКЪТ ИМА ДЕЦА НА ИЗДРЪЖКА“ по ГПК чл. 446 — изведен от {@code
 * Employee.bgChildren}.
 *
 * <p><b>⚠️ БЕЗ НОВО ПОЛЕ, И ТОВА Е РЕШЕНИЕ.</b> Изкушението е чекбокс „има деца на издръжка“ на
 * договора: едно поле, нула сметки. Точно затова НЕ се прави. Чекбоксът е ВТОРИ източник на истина
 * до {@code bgChildren} и се разминава с него мълчаливо — детето навършва 18 и списъкът го знае, а
 * чекбоксът остава вдигнат, докато някой не се сети. Разминаването се мери в пари: 1/4 вместо 1/3
 * върху нетото, всеки месец, без нищо на фиша да изглежда сгрешено.
 *
 * <p><b>⚠️ ЧЛ. 446 Е БУЛЕВО, НЕ СТЕПЕНУВА ПО БРОЙ.</b> Едно дете и четири деца дават една и съща
 * скала. Затова тук се отговаря с „да/не“, а не с брой — брой би поканил някого да го умножи по
 * нещо.
 *
 * <p><b>⚠️ ДАТАТА Е ЗАДЪЛЖИТЕЛНА, защото отговорът се МЕНИ.</b> Дете, непълнолетно през март, е
 * пълнолетно през септември; ТЕЛК изтича. Питан „днес“ за фиш за минал период, признакът щеше да
 * даде днешния отговор за чужд месец — и коригиращ фиш щеше да удържа по друга скала от оригинала.
 *
 * <p><b>ТРИ състояния, не две.</b> {@link Status#WITH_CHILDREN} и {@link Status#WITHOUT_CHILDREN}
 * са отговори; {@link Status#UNDETERMINABLE} е ОТКАЗ и съществува, защото липсващият вход НЕ бива
 * да се чете като „няма деца“. Тази посока на подразбирането удържа ПОВЕЧЕ от родител — 1/3 вместо
 * 1/4 — тоест взима пари от човек, чиито данни просто не са попълнени. Отказът се вижда; тихото
 * „не“ не.
 *
 * <p><b>Границата на тази услуга.</b> Тя отговаря на ЕДИН въпрос и не смята нищо. Скалата се
 * прилага от {@link BgGarnishmentResolver}, който е чист и не знае нито за {@code Employee}, нито
 * за дати.
 *
 * <p><b>═══ ОТКЪДЕ ИДВА ВЪПРОСЪТ, ДОСЛОВНО ═══</b> ГПК чл. 446 ал. 1 (ред. ДВ бр. 86/2017), сверено
 * срещу официалния ГПК (justice.government.bg), степенува всеки от трите си прага по един и същи
 * израз: „...— една трета част, ако е без деца, и една четвърт част, ако е с деца, които издържа“.
 *
 * <p><b>⚠️ „КОИТО ИЗДЪРЖА“ Е ЦЯЛОТО УСЛОВИЕ, и то е причината този клас да съществува.</b> Нормата
 * не пита колко деца има лицето и не пита дали са вписани някъде — пита ИЗДЪРЖА ЛИ ГИ. Оттам
 * следват и двете основания тук: непълнолетното дете се издържа по закон, а пълнолетното с валиден
 * ТЕЛК — по силата на увреждането си. Оттам следва и че отговорът е БУЛЕВ: изразът е „ако е с
 * деца“, без степенуване по брой.
 *
 * <p>⚠️ Изразът се повтаря дословно и в т. 2, и в т. 3 на ал. 1 — тоест един и същи признак избира
 * и трите прага, и затова се пита ВЕДНЪЖ, тук, а не по веднъж на праг.
 */
public class BgGarnishmentService {

  /** Пълнолетието по ЗЛС — 18 навършени години. */
  static final int MAJORITY_AGE = 18;

  /** Отговорът на въпроса „има ли деца на издръжка“, с трето състояние за неустановимото. */
  public enum Status {
    WITH_CHILDREN,
    WITHOUT_CHILDREN,
    UNDETERMINABLE
  }

  /**
   * ИМА ЛИ ЛИЦЕТО ДЕЦА НА ИЗДРЪЖКА към тази дата.
   *
   * <p>⚠️ Неустановимото дава {@code false} ТУК — и точно затова този вход НЕ бива да се ползва
   * сам, когато има запор. Пазачът при потвърждаване пита {@link #childrenStatus} и ОТКАЗВА при
   * {@link Status#UNDETERMINABLE}; този вход е за местата, където отговорът е сведение, а не
   * основание за удържане.
   */
  public boolean hasMaintainedChildren(Employee employee, LocalDate date) {
    return childrenStatus(employee, date) == Status.WITH_CHILDREN;
  }

  /**
   * ТРИТЕ СЪСТОЯНИЯ, с приоритет на положителния отговор.
   *
   * <p>Редът е част от правилото: ако ПОНЕ ЕДНО дете е установено като на издръжка, отговорът е
   * „да“ и непопълнените данни на останалите вече нямат значение. Иначе непопълненото дете прави
   * целия отговор неустановим — не „не“.
   *
   * <p>⚠️ ЛИПСВАЩ СЛУЖИТЕЛ Е НЕУСТАНОВИМО, НЕ „БЕЗ ДЕЦА“. Договор без служител е дефект в данните и
   * не е твърдение, че лицето няма деца.
   *
   * <p>⚠️ ПРАЗЕН СПИСЪК Е „БЕЗ ДЕЦА“, и това е различно от липсващ служител. Празният списък е
   * попълнен отговор — ТРЗ е отворило картона и не е вписало дете. Прочетен като неустановимо, той
   * би блокирал всеки запор на бездетен длъжник, тоест пазачът щеше да спира верните случаи.
   */
  public Status childrenStatus(Employee employee, LocalDate date) {
    if (employee == null || date == null) {
      return Status.UNDETERMINABLE;
    }
    List<BgEmployeeChild> children =
        employee.getBgChildren() == null ? new ArrayList<>() : employee.getBgChildren();

    boolean anyUndeterminable = false;
    for (BgEmployeeChild child : children) {
      if (child == null) {
        continue;
      }
      if (isMaintained(child, date)) {
        return Status.WITH_CHILDREN;
      }
      if (isUndeterminable(child, date)) {
        anyUndeterminable = true;
      }
    }
    return anyUndeterminable ? Status.UNDETERMINABLE : Status.WITHOUT_CHILDREN;
  }

  /**
   * ДЕТЕ НА ИЗДРЪЖКА: непълнолетно към датата ИЛИ с увреждане и ВАЛИДЕН към датата ТЕЛК.
   *
   * <p>⚠️ ДВЕ ОСНОВАНИЯ, НЕ ЕДНО, и второто не е следствие от първото. Пълнолетното дете с
   * увреждане остава на издръжка; непълнолетното без ТЕЛК също. Слети в едно условие, едното от
   * двете винаги би изпадало.
   *
   * <p>⚠️ Възрастта се мери с {@code birthDate.plusYears(18).isAfter(date)}, а не с разлика в
   * години: на самия 18-и рожден ден лицето ВЕЧЕ е пълнолетно, тоест границата е включваща отгоре.
   */
  static boolean isMaintained(BgEmployeeChild child, LocalDate date) {
    return isMinorAt(child, date) || hasValidDisabilityAt(child, date);
  }

  private static boolean isMinorAt(BgEmployeeChild child, LocalDate date) {
    LocalDate born = child.getBirthDate();
    return born != null && born.plusYears(MAJORITY_AGE).isAfter(date);
  }

  private static boolean hasValidDisabilityAt(BgEmployeeChild child, LocalDate date) {
    if (!child.getIsDisabled()) {
      return false;
    }
    LocalDate until = child.getDisabilityValidTo();
    return until != null && !until.isBefore(date);
  }

  /**
   * ⚠️ КОГА ДЕТЕТО НЕ КАЗВА НИТО „ДА“, НИТО „НЕ“.
   *
   * <p>Две положения, и двете са ЛИПСВАЩ ВХОД, а не отговор:
   *
   * <ul>
   *   <li><b>без дата на раждане</b> и без валиден ТЕЛК — възрастта не може да се пресметне, тоест
   *       не се знае дали е непълнолетно;
   *   <li><b>отбелязано с увреждане, но без срок на ТЕЛК</b> — „пожизнено решение“ и „никой не е
   *       попълнил докога“ изглеждат ЕДНАКВО в базата. Прочетено като изтекло, второто би удържало
   *       по-голяма част от родител на дете с увреждане; прочетено като валидно, първото би
   *       удържало по-малка част по непопълнено поле. Затова не се чете нито едното — иска се
   *       попълване.
   * </ul>
   */
  static boolean isUndeterminable(BgEmployeeChild child, LocalDate date) {
    if (isMaintained(child, date)) {
      return false;
    }
    if (child.getIsDisabled() && child.getDisabilityValidTo() == null) {
      return true;
    }
    return child.getBirthDate() == null;
  }
}

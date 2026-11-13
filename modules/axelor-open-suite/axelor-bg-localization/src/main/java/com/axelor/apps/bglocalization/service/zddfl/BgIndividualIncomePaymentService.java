package com.axelor.apps.bglocalization.service.zddfl;

import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.db.repo.BgIncomeTypeRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;

/**
 * Пълни реда на регистъра от вида доход — тънък слой над {@link BgIncomeTaxResolver}.
 *
 * <p><b>Правилата НЕ живеят тук.</b> Тук се случват само трите неща, които решателят нарочно не
 * прави: изважда ставките от каталога, сверява ДАТАТА срещу валидността на реда и записва резултата
 * обратно в полетата. Така „1000 при 25 на сто дава 75“ остава проверимо без база.
 *
 * <p><b>⚠️ ДВА ВХОДА, ЕДНА СМЕТКА.</b> Формата вика {@link #computeInto} при всяка промяна на
 * бруто, вид доход или ЗОВ; авто-захранването ({@code BgIncomePaymentFeedService}) вика СЪЩИЯ метод
 * след като е препишало полетата от изходния документ. Това е нарочно и е цялата причина методът да
 * е публичен: втора сметка при захранването би значела, че ръчно въведеният и авто-захранваният ред
 * за едно и също плащане могат да дадат различни числа — разлика, която се вижда едва в справката.
 */
@Singleton
public class BgIndividualIncomePaymentService {

  /** Източникът за ръчно въведен ред — {@code bg.income.payment.source.select}. */
  public static final String SOURCE_MANUAL = "MANUAL";

  /**
   * Код корекция „0“ — основни данни, указание т. 2 към справката по чл. 73 ал. 1.
   *
   * <p>⚠️ Стои ТУК, а не на захранването, защото е стойността по подразбиране на ПОЛЕТО, а не
   * решение на прогона: и ръчният ред тръгва от нея. Захранването я слага само при СЪЗДАВАНЕ и
   * никога не я връща върху ред, на който човек е сложил „1“ или „8“.
   */
  public static final String CORRECTION_PRIMARY = "0";

  /**
   * Смята и ЗАПИСВА трите суми и вида данък върху записа.
   *
   * <p>При отказ полетата НЕ се пипат: половин попълнена форма изглежда като сметнат резултат.
   * Върнатият {@link BgIncomeTaxResult} носи причината и контролерът я показва.
   */
  public BgIncomeTaxResult computeInto(BgIndividualIncomePayment payment) {
    BgIncomeType type = resolveType(payment);
    if (type == null) {
      return BgIncomeTaxResult.refuse(
          "Не е избран вид доход — без него не се знае по кой член се смята.");
    }

    BgIncomeTaxResult result =
        BgIncomeTaxResolver.resolve(
            new BgIncomeTaxRequest(
                type.getTaxPattern(),
                type.getExpenseRate(),
                type.getFinalTaxRate(),
                type.getAdvanceTaxRate(),
                Boolean.TRUE.equals(type.getWithheldByPayer()),
                payment.getGrossAmount(),
                payment.getInsuranceAmount(),
                Boolean.TRUE.equals(payment.getSelfInsuredDeclared())));

    if (result.isResolved()) {
      payment.setExpenseAmount(result.expenseAmount);
      payment.setTaxableBase(result.taxableBase);
      payment.setTaxAmount(result.taxAmount);
      payment.setTaxKind(result.taxKind);
    }
    return result;
  }

  /**
   * Предупреждение, ако избраният ред от каталога НЕ е в сила на датата на плащането.
   *
   * <p>⚠️ <b>Предупреждение, не блокировка — и това е нарочно.</b> Коригираща справка за минала
   * година ползва кода, валиден ТОГАВА, тоест ред извън днешната валидност е ЗАКОНЕН вход, когато
   * датата също е в миналото. Блокирано, това би направило корекциите невъзможни. Проверката пита
   * друго: съвпадат ли ДВЕТЕ — редът и датата.
   *
   * <p>Връща {@code null}, когато няма какво да се каже.
   */
  public String validityWarning(BgIndividualIncomePayment payment) {
    BgIncomeType type = resolveType(payment);
    LocalDate date = payment.getPaymentDate();
    if (type == null || date == null) {
      return null;
    }
    LocalDate from = type.getValidFrom();
    LocalDate to = type.getValidTo();

    if (from != null && date.isBefore(from)) {
      return "Код "
          + type.getCode()
          + " е в сила от "
          + from
          + ", а датата на изплащане е "
          + date
          + ". Ставките, с които е сметнат данъкът, са на РЕД, който още не е действал.";
    }
    if (to != null && date.isAfter(to)) {
      return "Код "
          + type.getCode()
          + " е в сила до "
          + to
          + ", а датата на изплащане е "
          + date
          + ". За тази дата вероятно има по-нова редакция на кода — проверете номенклатурата.";
    }
    return null;
  }

  /** Подразбирания при нов ръчен ред. */
  public void prepareNew(BgIndividualIncomePayment payment) {
    if (payment.getSource() == null) {
      payment.setSource(SOURCE_MANUAL);
    }
    if (payment.getTaxKind() == null) {
      // ⚠️ taxKind е required. Оставен празен, новият запис не се записва изобщо и потребителят
      // вижда отказ на полето, вместо резултата от изчислението.
      payment.setTaxKind(BgIncomeTaxResolver.TAX_NONE);
    }
  }

  /**
   * Видът доход, ПРЕЧЕТЕН от базата.
   *
   * <p>⚠️ Не се ползва обектът от контекста на формата: при onChange той носи само това, което
   * екранът е показал, тоест ставките може да ги няма. Сметка върху такъв обект би дала нула данък
   * на напълно коректен ред.
   */
  private BgIncomeType resolveType(BgIndividualIncomePayment payment) {
    BgIncomeType type = payment.getIncomeType();
    if (type == null) {
      return null;
    }
    if (type.getId() == null) {
      return type;
    }
    BgIncomeType stored = Beans.get(BgIncomeTypeRepository.class).find(type.getId());
    return stored == null ? type : stored;
  }
}

package com.axelor.apps.bglocalization.service.payroll.ledger;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Collections;
import java.util.List;

/**
 * ОБХОДЪТ НА ЕДИН ПЕРИОД — кои фишове изобщо стигат до ведомостта.
 *
 * <p><b>⚠️ ЕДИНСТВЕНОТО МЯСТО, НА КОЕТО СЕ РЕШАВА КОЙ НЕ ВЛИЗА.</b> Разбиването по вид
 * правоотношение е в {@link BgPayrollLedgerVariant} и то не изпуска никого; изборът от екрана е в
 * {@link BgPayrollLedgerSelection} и той реже видимо. Тук се прави ЕДИНСТВЕНОТО невидимо рязане —
 * оттам и трите изречения по-долу.
 *
 * <p><b>⚠️ ОТКАЗАНИЯТ ФИШ НЕ Е РЕД ВЪВ ВЕДОМОСТТА.</b> Той остава в базата за одит, но нищо по него
 * не се дължи и не се плаща. Чернова и потвърден фиш ВЛИЗАТ и двата: ТРЗ сверява ведомостта именно
 * преди потвърждаването, а състоянието стои в своя графа върху листа — същият избор като на
 * печатния фиш.
 *
 * <p><b>⚠️ КОРИГИРАЩИТЕ И ЗАЛИЧАВАЩИТЕ ФИШОВЕ ВЛИЗАТ, И ТОВА Е ОБРАТНОТО НА SEPA ПРОБЕГА.</b> Там
 * се плаща и ретро-фишът е забранен, защото делтата му излиза с ТЕКУЩОТО нето; тук се ОТЧИТА, а
 * коригиращият фиш носи пълните верни числа за своя период — тоест ведомостта на коригирания месец
 * без него би показвала сумите, за които вече е признато, че са били грешни. Състоянието им се чете
 * от графата „Вид“.
 *
 * <p><b>⚠️ ДРУЖЕСТВОТО Е НЕЗАДЪЛЖИТЕЛНО, И ТОВА НЕ Е ХЛАБАВОСТ.</b> Фишът НЯМА свое дружество — то
 * се чете през {@code EmploymentContract.payCompany} (същият прочит като в {@code
 * BgPayrollSepaService}). Самоосигуряващото се лице обаче няма договор и {@link
 * com.axelor.apps.bglocalization.service.d1.BgD1Carrier#company()} връща {@code null} за него ПО
 * УСТРОЙСТВО — регистрацията не носи дружество и не бива да носи. Тоест филтър по дружество
 * изключва ВСИЧКИ самоосигуряващи се, винаги. Избрано дружество значи „ведомост на предприятието“ и
 * тогава изключването е вярно и се БРОИ в доклада; празно поле значи „целият период“ и тогава
 * самоосигуряващите се имат свой лист. Измисленият път (дружеството на лицето по друг негов
 * договор) би приписал на предприятието чужд разход.
 */
@Singleton
public class BgPayrollLedgerService {

  protected final BgPaySlipRepository paySlipRepository;

  @Inject
  public BgPayrollLedgerService(BgPaySlipRepository paySlipRepository) {
    this.paySlipRepository = paySlipRepository;
  }

  /** Ведомостта на един период. Липсващ период дава празна ведомост, а не изключение. */
  public BgPayrollLedger build(Company company, Period period) {
    return BgPayrollLedger.of(slipsOf(company, period), selfInsuredOutsideScope(company, period));
  }

  /**
   * Колко фиша на самоосигуряващи се лица е изрязал филтърът по дружество.
   *
   * <p>⚠️ БРОИ СЕ САМО КОГАТО ИМА КАКВО ДА СЕ ИЗРЕЖЕ. При празно поле „Дружество“ обхватът е целият
   * период, тоест нищо не е изрязано и второ запитване към базата би било запитване, чийто отговор
   * се знае предварително.
   */
  protected int selfInsuredOutsideScope(Company company, Period period) {
    if (company == null || period == null) {
      return 0;
    }
    return (int)
        paySlipRepository
            .all()
            .filter(
                "self.period = :period AND self.statusSelect != :cancelled "
                    + "AND self.bgSelfInsuredRegistration IS NOT NULL")
            .bind("period", period)
            .bind("cancelled", BgPaySlipResolver.CANCELLED)
            .count();
  }

  /**
   * Фишовете, от които се строи ведомостта — подредени стабилно, но НЕ по име.
   *
   * <p>⚠️ ПОДРЕЖДАНЕТО ПО ИМЕ Е В {@link BgPayrollLedger}, НА ЕДНО МЯСТО, И ТОВА Е ИЗМЕРЕНА
   * ПРЕДПАЗЛИВОСТ. Postgres подрежда по КОЛАЦИЯТА на базата, а тя е свойство на машината: под
   * {@code en_US.UTF-8} и под {@code C} едни и същи кирилски имена излизат в различен ред.
   * Ведомост, подредена веднъж тук (в SQL) и веднъж в отчета (пак в SQL), би излизала различно на
   * две инсталации от един и същ билд. Затова базата дава СТАБИЛЕН, но произволен ред, а редът на
   * хартията се решава в Java — и отчетът го получава наготово (виж {@code
   * BgPayrollLedgerPrintService}).
   */
  protected List<BgPaySlip> slipsOf(Company company, Period period) {
    if (period == null) {
      return Collections.emptyList();
    }

    StringBuilder filter =
        new StringBuilder("self.period = :period AND self.statusSelect != :cancelled");
    if (company != null) {
      filter.append(" AND self.employmentContract.payCompany = :company");
    }

    com.axelor.db.Query<BgPaySlip> query =
        paySlipRepository
            .all()
            .filter(filter.toString())
            .bind("period", period)
            .bind("cancelled", BgPaySlipResolver.CANCELLED);
    if (company != null) {
      query = query.bind("company", company);
    }

    return query.order("reference").order("id").fetch();
  }
}

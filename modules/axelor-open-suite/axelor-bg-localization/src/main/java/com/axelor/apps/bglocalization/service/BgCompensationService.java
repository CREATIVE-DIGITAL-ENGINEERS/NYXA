package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.bglocalization.db.repo.BgCompensationRepository;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaAttachment;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Слоят с база за обезщетението-документ. Всяко ПРАВИЛО живее в {@link BgCompensationResolver} —
 * тук са само четенето от каталога, замразяването и записът.
 *
 * <p>Същото разделение като {@code BgTerminationCompensationService} / {@code
 * BgCompensationProposalResolver} и {@code BgMinInsuranceIncomeService} / {@code
 * BgMinIncomeResolver}: нито едно правило не иска база, за да бъде проверено.
 *
 * <p><b>Замразяването</b> е цялата причина този клас да съществува. При потвърждаване стойностите,
 * които каталогът дава днес, се ПРЕПИСВАТ върху документа и оттам нататък документът не пита
 * каталога за нищо. Заповедта е подписана с конкретно число.
 */
public class BgCompensationService {

  /** КТ чл. 326 ал. 2: срокът при безсрочен трудов договор е 30 дни, ако не е уговорен по-дълъг. */
  protected static final int DEFAULT_NOTICE_CALENDAR_DAYS = 30;

  /**
   * Попълва документа от вида — при създаване и при всяка смяна на вида, докато е чернова.
   *
   * <p>Копира вида база и посоката, предлага количество от {@code baseMonths}, и извежда двете дати
   * по чл. 228: месеца на базата (ал. 1) и срока за плащане (ал. 3). Всичко остава редактируемо:
   * подразбиране, не решение.
   *
   * <p>⚠️ Не пипа нищо, ако документът вече не е чернова.
   */
  public void prepare(BgCompensation doc) {
    if (doc == null || !BgCompensationResolver.DRAFT.equals(doc.getStatusSelect())) {
      return;
    }
    BgCompensationType type = doc.getBgCompensationType();
    if (type != null) {
      doc.setBgBaseKind(type.getBaseKind());
      doc.setBgDirection(type.getBgDirection());
      // ⚠️ signum(), НЕ == null: генерираният getter за decimal връща ZERO, тоест „още
      // непопълнено“ се изразява само с нулата.
      // ⚠️ И ПРЕДЛОЖЕНИЕТО ИДВА ОТ РЕЗОЛВЕРА, а не направо от baseMonths: при видовете с ТАВАН
      // (днес чл. 225 ал. 1 КТ) числото в каталога е горна граница, не размер, и предложено като
      // стойност то надплаща всеки, който е останал без работа за по-малко от този срок. Виж
      // BgCompensationResolver#prefillQuantity.
      if (doc.getBgQuantity().signum() == 0) {
        BigDecimal proposed = BgCompensationResolver.prefillQuantity(type);
        if (proposed != null) {
          doc.setBgQuantity(proposed);
        }
      }
    }
    if (BgCompensationResolver.NOTICE_DAYS.equals(doc.getBgBaseKind())) {
      deriveNoticeQuantity(doc);
    }
    if (doc.getBgEventDate() != null) {
      if (doc.getBgBaseMonth() == null) {
        doc.setBgBaseMonth(BgCompensationResolver.baseMonthFor(doc.getBgEventDate()));
      }
      if (doc.getBgDueDate() == null) {
        doc.setBgDueDate(BgCompensationResolver.dueDateFor(doc.getBgEventDate()));
      }
    }
  }

  /**
   * Преизчислява сумата. ⚠️ Работи САМО в чернова — потвърденият документ е замразен.
   *
   * <p>Когато сумата за изплащане още не е пипана ръчно, тя следва изчислената. Разминаването е
   * решение на човек и се пази заедно с причината си.
   */
  public void recompute(BgCompensation doc) {
    if (doc == null || !BgCompensationResolver.DRAFT.equals(doc.getStatusSelect())) {
      return;
    }
    BigDecimal computed =
        BgCompensationResolver.compute(
            doc.getBgBaseKind(),
            doc.getBgQuantity(),
            doc.getBgBaseMonthGross(),
            doc.getBgBaseWorkingDays());
    BigDecimal previous = doc.getBgComputedAmount();
    doc.setBgComputedAmount(computed);
    // ⚠️ И двата null-а тук бяха мъртви (getter-ите за decimal връщат ZERO), но условието работеше
    // по случайност: на нов документ изчисленото и окончателното са нула и съвпадат, тоест
    // окончателното се презаписва — точно каквото трябва. Остава същественото: окончателната сума
    // се
    // пипа само докато още повтаря изчислената, тоест докато никой не я е сменил на ръка. ⚠️ НЕ се
    // пренаписва на „signum() == 0“ — така въведената на ръка нула би минала за непопълнена.
    if (computed != null && previous.compareTo(doc.getBgFinalAmount()) == 0) {
      doc.setBgFinalAmount(computed);
    }
  }

  /**
   * Потвърждаване: проверява, ЗАМРАЗЯВА и минава в CONFIRMED.
   *
   * <p>Замразяват се данъчното и осигурителното третиране — сумата, базата и видът ѝ вече стоят на
   * документа от чернова. Оттук нататък промяна в {@code BgCompensationType} не докосва този запис.
   */
  @Transactional(rollbackOn = {Exception.class})
  public void confirm(BgCompensation doc) throws AxelorException {
    refuse(
        BgCompensationResolver.refuseTransition(
            doc.getStatusSelect(), BgCompensationResolver.CONFIRMED));

    // ПРАВО ПРЕДИ ПАРИ — преди всяка проверка на сумата: ако правото липсва, числото няма
    // какво да признава. После фактите, без които видът няма основание (чл. 220, 221, 225, 226).
    refuse(BgCompensationResolver.refuseWithoutEntitlement(doc));
    refuse(BgCompensationResolver.refuseMissingFacts(doc));

    BgCompensationType type = doc.getBgCompensationType();
    // ⚠️ ДВАТА ПАЗАЧА НА ТАВАНА СА ПЪРВИ, ПРЕДИ ВСЯКА ПРОВЕРКА НА СУМАТА, и редът е част от
    // правилото. И двата случая — празно количество и количество над тавана — стигат до сумата и я
    // правят съответно нула или завишена. Питани по-късно, операторът получава „сумата за изплащане
    // е празна“ или „корекция НАДОЛУ не се допуска“ и тръгва да поправя ЧИСЛОТО, докато сгрешен е
    // ПЕРИОДЪТ, от който то излиза. Особено подвеждащо е второто: чл. 225 ал. 1 е в
    // STATUTORY_MINIMUM, тоест сваляне на сумата под изчислената се отказва с довод по чл. 228 ал.
    // 2 — верен довод върху грешна изчислена сума.
    refuse(BgCompensationResolver.refuseMissingActualPeriod(type, doc.getBgQuantity()));
    refuse(
        BgCompensationResolver.refuseAboveStatutoryMaximum(
            type, doc.getBgBaseKind(), doc.getBgQuantity()));
    refuse(
        BgCompensationResolver.refuseAdjustment(
            type, doc.getBgComputedAmount(), doc.getBgFinalAmount(), doc.getBgAdjustmentReason()));
    refuse(
        BgCompensationResolver.refuseConfirm(
            type, doc.getBgFinalAmount(), hasAttachment(doc), doc.getNote()));
    refuse(
        BgCompensationResolver.refuseAgainstEntitlement(
            doc.getBgFinalAmount(), paidOnEntitlement(doc)));
    if (BgCompensationResolver.NOTICE_DAYS.equals(doc.getBgBaseKind())
        && doc.getBgNoticeStartDate() != null) {
      BgWorkingDayService.Count count = noticeCount(doc);
      refuse(
          BgCompensationResolver.refuseOnUncoveredCalendar(
              count.getWorkingDays(), doc.getBgQuantity(), count.uncoveredYearsAsText()));
    }

    if (type != null) {
      doc.setBgIsTaxable(type.getIsTaxable());
      doc.setBgSocialContributionsDue(type.getSocialContributionsDue());
      if (doc.getBgBaseKind() == null) {
        doc.setBgBaseKind(type.getBaseKind());
      }
      if (doc.getBgDirection() == null) {
        doc.setBgDirection(type.getBgDirection());
      }
    }
    doc.setStatusSelect(BgCompensationResolver.CONFIRMED);
    save(doc);
  }

  /**
   * „Създай документ“ от ПОТВЪРДЕНО право — единственият писач на връзката документ → право.
   *
   * <p>Документът се ражда като чернова за същия договор и вид, със същата дата на основанието
   * (датата на прекратяване от правото) и основанието от договора. Подразбиранията от вида и
   * изчислението минават през {@link #prepare} и {@link #recompute} — като при нов документ от
   * формата. По едно право — най-много един документ в работа: поправката е отказ и нов документ.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgCompensation createFromEntitlement(BgTerminationCompensation right)
      throws AxelorException {
    refuse(BgCompensationResolver.refuseDocumentFromEntitlement(right, openDocumentOf(right)));
    EmploymentContract contract = right.getEmploymentContract();
    EndOfContractReason reason = contract == null ? null : contract.getEndOfContractReason();

    BgCompensation doc = new BgCompensation();
    doc.setStatusSelect(BgCompensationResolver.DRAFT);
    doc.setEmploymentContract(contract);
    doc.setBgCompensationType(right.getBgCompensationType());
    doc.setBgTerminationCompensation(right);
    doc.setBgTerminationGround(reason == null ? null : reason.getBgTerminationGround());
    doc.setBgEventDate(right.getTerminationDate());
    if (right.getBaseQuantity().signum() > 0) {
      doc.setBgQuantity(right.getBaseQuantity());
    }
    prepare(doc);
    recompute(doc);
    return Beans.get(BgCompensationRepository.class).save(doc);
  }

  /** Номерът на документ по правото, който още е в работа; {@code null} — няма. */
  protected String openDocumentOf(BgTerminationCompensation right) {
    if (right == null || right.getId() == null) {
      return null;
    }
    BgCompensation open =
        Beans.get(BgCompensationRepository.class)
            .all()
            .filter("self.bgTerminationCompensation.id = :id AND self.statusSelect != :cancelled")
            .bind("id", right.getId())
            .bind("cancelled", BgCompensationResolver.CANCELLED)
            .fetchOne();
    if (open == null) {
      return null;
    }
    return open.getReference() != null ? open.getReference() : "без номер, id " + open.getId();
  }

  /** Изплащане: иска дата и потвърден документ. */
  @Transactional(rollbackOn = {Exception.class})
  public void pay(BgCompensation doc) throws AxelorException {
    refuse(
        BgCompensationResolver.refuseTransition(
            doc.getStatusSelect(), BgCompensationResolver.PAID));
    if (doc.getBgPaidDate() == null) {
      refuse("Изплащането иска дата на изплащане.");
    }
    doc.setStatusSelect(BgCompensationResolver.PAID);
    save(doc);
  }

  /** Отказ с причината, която документът вече носи — вратата за заварените повикващи. */
  @Transactional(rollbackOn = {Exception.class})
  public void cancel(BgCompensation doc) throws AxelorException {
    cancel(doc, doc.getBgCancelReason());
  }

  /**
   * Отказ: единственият изход от потвърден документ. Иска причина.
   *
   * <p><b>⚠️ ПРИЧИНАТА СЕ ПОДАВА, А НЕ СЕ ЧЕТЕ ОТ ДОКУМЕНТА.</b> Формата не можеше да я въведе:
   * полето се показваше едва след отказа. Сега идва от входа на бутона и се записва в {@code
   * bgCancelReason} ТУК, след проверките, в една транзакция с отказа. Провал — нито причината, нито
   * статусът.
   */
  @Transactional(rollbackOn = {Exception.class})
  public void cancel(BgCompensation doc, String reason) throws AxelorException {
    refuse(
        BgCompensationResolver.refuseTransition(
            doc.getStatusSelect(), BgCompensationResolver.CANCELLED));
    refuse(BgCompensationResolver.refuseCancel(reason));
    doc.setBgCancelReason(reason.trim());
    doc.setStatusSelect(BgCompensationResolver.CANCELLED);
    save(doc);
  }

  /** Просрочен ли е документът към днес — само за отбелязване, лихва не се смята. */
  public boolean isOverdue(BgCompensation doc) {
    return doc != null
        && BgCompensationResolver.isOverdue(
            doc.getBgDueDate(), doc.getBgPaidDate(), LocalDate.now());
  }

  /**
   * Има ли прикачен файл. Доказването по {@code requiresProof} е или файл, или бележка.
   *
   * <p>Заявка към {@code MetaAttachment}, а не към DMS: прикачването през формата минава именно
   * оттам, а незаписан документ (без id) няма как да носи файл.
   */
  protected boolean hasAttachment(BgCompensation doc) {
    if (doc == null || doc.getId() == null) {
      return false;
    }
    Long count =
        JPA.em()
            .createQuery(
                "SELECT count(a) FROM MetaAttachment a WHERE a.objectName = :name"
                    + " AND a.objectId = :id",
                Long.class)
            .setParameter("name", BgCompensation.class.getName())
            .setParameter("id", doc.getId())
            .getSingleResult();
    return count != null && count > 0;
  }

  /**
   * Извежда количеството при чл. 220 от срока на предизвестието — вторият етап на правилото.
   *
   * <p>Срокът е календарен (КТ чл. 326 ал. 2 и ал. 4), обезщетението покрива работните дни в него
   * (НСОРЗ чл. 19 ал. 1). Подразбирането за безсрочен договор е 30 календарни дни.
   *
   * <p>Изведеното се записва в {@code bgQuantity} само когато то е празно — въведеното от човек не
   * се презаписва. Разминаването се показва като предупреждение от контролера.
   */
  protected void deriveNoticeQuantity(BgCompensation doc) {
    // ⚠️ Само нулата, без null: генерираният getter за Integer връща 0. Датата отдолу е LocalDate —
    // ТЯ наистина може да е null и проверката ѝ остава.
    if (doc.getBgNoticeCalendarDays() == 0) {
      doc.setBgNoticeCalendarDays(DEFAULT_NOTICE_CALENDAR_DAYS);
    }
    if (doc.getBgNoticeStartDate() == null) {
      return;
    }
    if (doc.getBgQuantity().signum() == 0) {
      doc.setBgQuantity(BigDecimal.valueOf(noticeCount(doc).getWorkingDays()));
    }
  }

  /**
   * Броенето за срока на предизвестието — заедно с това дали календарът покрива интервала.
   *
   * <p>⚠️ Броенето НЕ живее тук. Единственото място е {@link BgWorkingDayService}: всяко следващо
   * перо, което мери период, минава оттам, за да не се преписва проверката за покритие.
   */
  protected BgWorkingDayService.Count noticeCount(BgCompensation doc) {
    int days =
        doc.getBgNoticeCalendarDays() == 0
            ? DEFAULT_NOTICE_CALENDAR_DAYS
            : doc.getBgNoticeCalendarDays();
    return workingDayService().count(doc.getBgNoticeStartDate(), days);
  }

  /** Изведеното количество, без да се пише по документа — за предупреждението във формата. */
  public int derivedNoticeQuantity(BgCompensation doc) {
    if (doc == null || doc.getBgNoticeStartDate() == null) {
      return 0;
    }
    return noticeCount(doc).getWorkingDays();
  }

  /** Годините без календар, които срокът докосва — празно при пълно покритие. */
  public String uncoveredCalendarYears(BgCompensation doc) {
    if (doc == null || doc.getBgNoticeStartDate() == null) {
      return "";
    }
    return noticeCount(doc).uncoveredYearsAsText();
  }

  /** Отделен метод, за да може тестът да подмени сервиза без база. */
  protected BgWorkingDayService workingDayService() {
    return Beans.get(BgWorkingDayService.class);
  }

  /**
   * Сборът на плащанията по правото, към което сочи документът. {@code null}, когато право няма или
   * то още няма нито едно плащане — тогава няма какво да се сверява.
   *
   * <p>Сборът се чете от {@code BgCompensationPayment}, а не от поле върху правото: плащанията са
   * няколко нарочно (регистърът по ПМС 267/2024 брои седем дни от ВСЯКО), тоест единственото вярно
   * число е техният сбор.
   */
  protected BigDecimal paidOnEntitlement(BgCompensation doc) {
    if (doc == null
        || doc.getBgTerminationCompensation() == null
        || doc.getBgTerminationCompensation().getId() == null) {
      return null;
    }
    return JPA.em()
        .createQuery(
            "SELECT sum(p.amount) FROM BgCompensationPayment p"
                + " WHERE p.bgTerminationCompensation.id = :id",
            BigDecimal.class)
        .setParameter("id", doc.getBgTerminationCompensation().getId())
        .getSingleResult();
  }

  protected void save(BgCompensation doc) {
    Beans.get(BgCompensationRepository.class).save(doc);
  }

  /** Празно съобщение значи „няма пречка“ — всичко друго спира действието. */
  private static void refuse(String message) throws AxelorException {
    if (message != null && !message.isEmpty()) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, "%s", message);
    }
  }

  /** Пази вносът на {@link MetaAttachment} явен — заявката горе е по име на класа. */
  private static final Class<MetaAttachment> ATTACHMENT = MetaAttachment.class;
}

package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.repo.BgArt73Para1ReportRepository;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para1FileService;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para1ReportService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Екранът на справката по чл. 73 ал. 1 — извеждане, файл, закачане и ръчният изход от портала.
 *
 * <p>Тънък слой нарочно: нито едно правило на образеца не живее тук. Виж {@code
 * BgArt73Para1Aggregator} за сливането, {@code BgArt73Para1FileValidator} за указанията и {@code
 * BgArt73Para1FileLayout} за липсващия формат.
 *
 * <p><b>⚠️ БУТОНЪТ ЗА ФАЙЛ Е БЛОКИРАН, ДОКАТО ФОРМАТЪТ НЕ ДОЙДЕ — и това се пита ПРЕДИ опита.</b>
 * Услугата хвърля при непотвърдено оформление; извикана сляпо, тя би повалила и извеждането на
 * редовете, което работи. Затова контролерът пита {@code isLayoutConfirmed()} и, ако отговорът е
 * „не“, извежда редовете и КАЗВА, че файл няма — без да прави нито едното наполовина.
 */
@Singleton
public class BgArt73Para1ReportController {

  /**
   * ГЕНЕРИРА редовете на Част ІІ от регистъра и, ако форматът позволява, файла.
   *
   * <p>⚠️ Иска ЗАПИСАНА справка. Строителят търси съществуващата по (дружество, година) и при
   * незаписан екран не би я намерил — тоест би създал ВТОРА за същата двойка и уникалността щеше да
   * я отхвърли със съобщение за база вместо с обяснение.
   *
   * <p><b>⚠️ ОТКАЗВА ПРИ ВЕЧЕ ПРОИЗВЕДЕН ФАЙЛ.</b> Редовете зад него са раздадени: човек ги е
   * свалил, може да ги е качил. Мълчаливо презаписани, екранът и свалèният файл се разминават, без
   * нищо да го каже. Пресъздаването има СВОЙ бутон.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para1Report report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRole("generate an art. 73 report", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt73Para1Report.class, report.getId());
      if (BgDeclarationStatus.isLocked(
          report.getStatusSelect(), report.getGeneratedFile() != null)) {
        response.setInfo(
            "Справката вече е заключена (има генериран файл или е подадена). Ако наистина искаш да"
                + " я построиш наново, ползвай „Генерирай НАНОВО“ — той обезсилва стария файл.");
        return;
      }
      rebuild(report, response);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ИЗРИЧНО ПРЕСЪЗДАВАНЕ — обезсилва стария файл и строи справката наново.
   *
   * <p>⚠️ Указание т. 2 дава право на промени в данните за конкретно лице до 30 септември на
   * годината на предоставяне — тоест пресъздаването не е авария, а предвиден ход. Затова не е
   * забранено, а е ИЗРИЧНО: отделен бутон и обезсилен стар файл.
   */
  public void regenerate(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para1Report report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRole("regenerate an art. 73 report", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt73Para1Report.class, report.getId());
      boolean wasAttached = report.getNapSubmission() != null;
      Beans.get(BgArt73Para1ReportService.class).invalidateFile(report);
      rebuild(report, response);
      if (wasAttached) {
        response.setNotify(
            "⚠️ Справката вече е закачена към подаване. Старото подаване и неговият файл ОСТАВАТ в"
                + " одитната следа; ново закачане ще посочи старото като предходно — корекцията по"
                + " указание т. 2 е НОВА справка с всички данни, а не редакция на подадената.");
      }
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Сваля генерирания файл — прецедентът е {@code BgVatReturnController}. */
  public void downloadFile(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para1Report report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRead(BgArt73Para1Report.class, report.getId());
      MetaFile metaFile = report.getGeneratedFile();
      if (metaFile == null) {
        response.setInfo(I18n.get("Няма генериран файл за изтегляне."));
        return;
      }
      response.setView(
          ActionView.define(metaFile.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + metaFile.getId()
                      + "/content/download?v="
                      + metaFile.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ОТБЕЛЯЗВА образеца като ПОДАДЕН през портала — БЕЗ реално подаване.
   *
   * <p><b>⚠️ НЕ ИСКА НАШ ФАЙЛ.</b> Докато форматът на записа не дойде със заповед на НАП, човек
   * подава със свой файл; периодът пак трябва да има следа. Свидетелят е ВХОДЯЩИЯТ НОМЕР, а не
   * файлът — него услугата изисква. Нашият файл, ако е генериран, тръгва заедно с подаването.
   *
   * <p>⚠️ Отказва при вече отбелязан и НЕпресъздаден образец: второ отбелязване би оставило два
   * записа в одитната следа за едно подаване и „кое от двете е подадено“ няма отговор.
   */
  public void attach(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para1Report form = request.getContext().asType(BgArt73Para1Report.class);
      BgArt73Para1Report report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRole("attach an art. 73 report file", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt73Para1Report.class, report.getId());
      if (report.getNapSubmission() != null
          && !BgDeclarationStatus.GENERATED.equals(report.getStatusSelect())) {
        response.setInfo(
            "Справката вече е отбелязан(а) като подаден(а) — "
                + report.getNapSubmission().getName()
                + ". За корекция ползвай „Генерирай НАНОВО“ и отбележи пак.");
        return;
      }
      boolean withFile = report.getGeneratedFile() != null;
      Beans.get(BgArt73Para1ReportService.class)
          .attach(report, form.getNapIncomingNumber(), form.getNapResponseDate());
      response.setNotify(
          withFile
              ? "Отбелязано като подадено през портала; нашият файл е закачен към подаването."
                  + " Нищо не е изпратено към НАП."
              : "Отбелязано като подадено през портала. ⚠️ БЕЗ прикачен файл от Nyxa — форматът на"
                  + " записа още не е потвърден, тоест подаването сочи файла, който ти си качил.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** РЪЧЕН ИЗХОД: приета. */
  public void markAccepted(ActionRequest request, ActionResponse response) {
    recordOutcome(request, response, true);
  }

  /** РЪЧЕН ИЗХОД: отхвърлена. */
  public void markRejected(ActionRequest request, ActionResponse response) {
    recordOutcome(request, response, false);
  }

  /**
   * Общото тяло на двата ръчни изхода.
   *
   * <p>⚠️ Чете входящия номер, датата и бележката от ФОРМАТА (незаписаните стойности на екрана), а
   * не от базата: човек ги въвежда и натиска бутона в един ход, без междинно записване.
   */
  protected void recordOutcome(ActionRequest request, ActionResponse response, boolean accepted) {
    try {
      BgArt73Para1Report form = request.getContext().asType(BgArt73Para1Report.class);
      BgArt73Para1Report report = managed(request, response);
      if (report == null) {
        return;
      }
      CapabilityGate.requireRole(
          "record the НАП outcome of an art. 73 report", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgArt73Para1Report.class, report.getId());
      Beans.get(BgArt73Para1ReportService.class)
          .recordOutcome(
              report,
              accepted,
              form.getNapIncomingNumber(),
              form.getNapResponseDate(),
              form.getNapResponseNote());
      response.setNotify(
          accepted ? "Отбелязана като ПРИЕТА от НАП." : "Отбелязана като ОТХВЪРЛЕНА.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Извежда редовете и — само ако форматът е потвърден — файла.
   *
   * <p><b>⚠️ ПИТАНЕ ПРЕДИ ОПИТ, А НЕ ХВАЩАНЕ НА ИЗКЛЮЧЕНИЕ.</b> Хванат, отказът щеше да прилича на
   * авария; питан, той е обявено състояние — редовете се извеждат докрай и човек научава ТОЧНО
   * какво липсва и откъде идва.
   */
  protected void rebuild(BgArt73Para1Report report, ActionResponse response) throws Exception {
    BgArt73Para1ReportService service = Beans.get(BgArt73Para1ReportService.class);
    service.build(report.getCompany(), report.getTaxYear());

    BgArt73Para1FileService fileService = Beans.get(BgArt73Para1FileService.class);
    if (!fileService.isLayoutConfirmed()) {
      response.setNotify(
          "Редовете на Част ІІ са изведени. ⚠️ ФАЙЛ НЕ Е ГЕНЕРИРАН: физическият формат на "
              + fileService.formName()
              + " не е потвърден — структурата на записа идва със заповед на изпълнителния директор"
              + " на НАП, която още не е в проекта. Затова „Изтегли файла“ остава неизползваем."
              + " Подаването НЕ е блокирано: качи свой файл на портала и отбележи входящия"
              + " номер.");
      return;
    }
    service.generateFile(report);
  }

  /** Записаната справка по id — или обяснение вместо мълчание. */
  protected BgArt73Para1Report managed(ActionRequest request, ActionResponse response) {
    BgArt73Para1Report form = request.getContext().asType(BgArt73Para1Report.class);
    if (form.getId() == null) {
      response.setInfo(
          I18n.get("Запиши справката (дружество и данъчна година), преди да продължиш."));
      return null;
    }
    BgArt73Para1Report report = Beans.get(BgArt73Para1ReportRepository.class).find(form.getId());
    if (report == null) {
      response.setInfo(I18n.get("Справката вече не съществува — презареди екрана."));
    }
    return report;
  }
}

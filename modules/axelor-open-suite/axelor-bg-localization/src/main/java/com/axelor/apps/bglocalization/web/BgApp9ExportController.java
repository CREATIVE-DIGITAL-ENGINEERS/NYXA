package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.repo.BgApp9ExportRepository;
import com.axelor.apps.bglocalization.service.noi.BgApp9Emission;
import com.axelor.apps.bglocalization.service.noi.BgApp9ExportService;
import com.axelor.apps.bglocalization.service.noi.BgApp9Gap;
import com.axelor.apps.bglocalization.service.noi.BgApp9SubmissionService;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.List;

/**
 * БУТОНИТЕ НА ИЗНОСА КЪМ НОИ — „Генерирай“ и „Отбележи като подаден“ (вторият — през {@link
 * BgApp9SubmissionService}).
 *
 * <p><b>⚠️ ДВА БУТОНА, И ВТОРИЯТ Е НАРОЧНО РЪЧЕН.</b> „Подаден“ не се вдига от код, защото кодът не
 * подава: подаването към портала на НОИ иска КЕП и човек пред устройството. Статус, вдиган
 * автоматично при генериране, би твърдял пред всеки следващ читател, че файлът е стигнал до НОИ — а
 * той е стигнал само до диска.
 *
 * <p><b>⚠️ ГЕНЕРИРАНЕТО ПРЕЗАПИСВА ДНЕВНИКА ВСЕКИ ПЪТ, ВКЛЮЧИТЕЛНО ПРИ ОТКАЗ.</b> Описът трябва да
 * казва колко болнични е имало в прозореца, а не колко са влезли във файла — иначе тихо изпуснатият
 * лист изглежда като лист, който не е съществувал.
 */
@Singleton
public class BgApp9ExportController {

  /** Емитерът, валидаторът и закачането — при отказ показва списъка „какво липсва“. */
  public void generate(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgApp9Export context = request.getContext().asType(BgApp9Export.class);
      CapabilityGate.requireRole("export App. 9 to НОИ", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgApp9Export.class, context.getId());
      // Дружеството — от ЗАПИСАНОТО: клиентът изпраща каквото дружество поиска.
      BgApp9Export stored =
          context.getId() == null
              ? context
              : Beans.get(BgApp9ExportRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(stored == null ? null : stored.getCompany());
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете износа, преди да генерирате файла."));
        return;
      }

      // Транзакцията и записът са в услугата — виж BgApp9ExportService#generate.
      respond(response, Beans.get(BgApp9ExportService.class).generate(stored));

    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Действието, което презарежда формата СЛЕД диалога за отказ — виж {@link #respond}.
   *
   * <p>⚠️ Името е договор с изгледите: {@code action-bg-app9-method-reload} в bg-app9-views.xml.
   */
  public static final String RELOAD_ACTION = "action-bg-app9-method-reload";

  /**
   * ОТГОВОРЪТ КЪМ ОПЕРАТОРА — отделен, за да се провери без база.
   *
   * <p><b>⚠️ ОТКАЗЪТ ПРЕЗАРЕЖДА ФОРМАТА ПРЕЗ ДЕЙСТВИЕТО НА ГРЕШКАТА, НЕ ПРЕЗ {@code reload}.</b>
   * Клиентът при „error“ показва диалога и спира веригата ПРЕДИ да прочете „reload“ — тоест отказът
   * е записан в базата, а формата би продължила да показва „Какво липсва“ от предишния опит.
   * Действието на грешката се пуска след затварянето на диалога и презарежда записа; веригата пак
   * спира — последното „save“ не върви.
   */
  static void respond(com.axelor.rpc.ActionResponse response, BgApp9Emission emission) {
    if (emission.isRefused()) {
      StringBuilder message = new StringBuilder();
      message
          .append("Файлът НЕ Е генериран. ")
          .append(emission.gapCount())
          .append(" липси спират износа:\n\n");
      int shown = 0;
      for (BgApp9Gap gap : emission.getGaps()) {
        if (shown++ >= 8) {
          message.append("\n… и още ").append(emission.gapCount() - 8).append(".");
          break;
        }
        message
            .append("• ")
            .append(gap.document)
            .append(" · ")
            .append(gap.clause)
            .append(" — ")
            .append(gap.reason)
            .append('\n');
      }
      message.append("\nЦелият списък е в поле „Какво липсва, за да е валидна“.");
      response.setError(message.toString(), null, null, RELOAD_ACTION);
      return;
    }

    StringBuilder message = new StringBuilder();
    message
        .append("Файлът е генериран (")
        .append(emission.getFileName())
        .append(") с ")
        .append(emission.getDocumentCount())
        .append(" удостоверения и е закачен като НЕИЗМЕНЯЕМ артефакт с отпечатък SHA-256.");
    List<String> excluded = emission.excludedDocuments();
    if (!excluded.isEmpty()) {
      // Изключените — в съобщението, не само в дневника.
      message
          .append("\n\n⚠️ Изключени: ")
          .append(excluded.size())
          .append(excluded.size() == 1 ? " лист" : " листа")
          .append(" — ")
          .append(String.join("; ", excluded))
          .append(". Причините са в дневника и в „Какво липсва, за да е валидна“; листовете")
          .append(" остават „не е генерирано“ в „Прил. 9 за подаване“.\n");
    }
    message
        .append(" Проверен е ОФЛАЙН срещу схемата на НОИ — виж „Проверки“.")
        .append(" ⚠️ НЕ Е ПОДАДЕН: подаването иска КЕП и се прави от човек през портала.");
    response.setInfo(message.toString());
    response.setReload(true);
  }

  /** Само презарежда записа — действието на диалога за отказ. */
  public void reload(com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    response.setReload(true);
  }

  /**
   * ЧОВЕКЪТ ОТБЕЛЯЗВА, ЧЕ Е ПОДАЛ — през {@link BgApp9SubmissionService}, същата услуга като при
   * единичното удостоверение.
   *
   * <p>⚠️ Иска входящия номер и датата от портала на НОИ и отказва без файл. Само вдигнат статус на
   * износа — без номер, без дата, без одит и без отбелязани удостоверения в него — би оставил т. 26
   * да не вижда подаването.
   */
  public void markSubmitted(
      com.axelor.rpc.ActionRequest request, com.axelor.rpc.ActionResponse response) {
    try {
      BgApp9Export context = request.getContext().asType(BgApp9Export.class);
      if (context.getId() == null) {
        response.setError(I18n.get("Запишете износа, преди да го отбележите като подаден."));
        return;
      }
      CapabilityGate.requireRole("mark an App. 9 export as submitted", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgApp9Export.class, context.getId());
      BgApp9Export export = Beans.get(BgApp9ExportRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(export == null ? null : export.getCompany());
      NapSubmission submission =
          Beans.get(BgApp9SubmissionService.class)
              .markSubmitted(
                  export.getBgNoiArtifact(),
                  context.getBgPortalIncomingNumber(),
                  context.getBgPortalSubmittedDate(),
                  AuthUtils.getUser(),
                  LocalDate.now());
      response.setInfo(
          "Отбелязано като подадено в НОИ с вх. № "
              + submission.getNapIncomingNumber()
              + ". ⚠️ Това е ОТБЕЛЯЗВАНЕ, а не подаване: приложението не изпраща към НОИ и не"
              + " подписва с КЕП.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

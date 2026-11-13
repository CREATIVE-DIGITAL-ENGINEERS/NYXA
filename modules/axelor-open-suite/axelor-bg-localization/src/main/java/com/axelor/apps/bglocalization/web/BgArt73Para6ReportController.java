package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.repo.BgArt73Para6ReportRepository;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para6ReportService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Екранът на справката по чл. 73 ал. 6 — генериране от фишовете и закачане към транспорта.
 *
 * <p>Тънък слой нарочно: нито едно правило на образеца не живее тук. Виж {@code
 * BgArt73Para6Aggregator} за сумирането по код, {@code BgArt49Recalculation} за годишното
 * преизчисляване и {@code BgArt73Para6ReportService} за прочита и границите.
 */
@Singleton
public class BgArt73Para6ReportController {

  /**
   * ГЕНЕРИРА редовете на т. 8 и годишното преизчисляване по чл. 49.
   *
   * <p>⚠️ Иска ЗАПИСАНА справка. Строителят търси съществуващата по (дружество, година) и при
   * незаписан екран не би я намерил — тоест би създал ВТОРА за същата двойка и уникалността щеше да
   * я отхвърли със съобщение за база вместо с обяснение.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para6Report form = request.getContext().asType(BgArt73Para6Report.class);
      if (form.getId() == null) {
        response.setInfo(
            I18n.get("Запиши справката (дружество и данъчна година), преди да я генерираш."));
        return;
      }
      BgArt73Para6Report report = Beans.get(BgArt73Para6ReportRepository.class).find(form.getId());
      CapabilityGate.requireRole("generate an art. 73(6) report", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgArt73Para6Report.class, form.getId());
      if (report == null) {
        response.setInfo(I18n.get("Справката вече не съществува — презареди екрана."));
        return;
      }
      CapabilityGate.requireActiveCompany(report.getCompany());
      Beans.get(BgArt73Para6ReportService.class).build(report.getCompany(), report.getTaxYear());
      response.setNotify(
          "Справката е генерирана. ⚠️ Прочети „Граници на изведеното“ ПРЕДИ подаване: там пише за"
              + " колко лица е направено годишното преизчисляване по чл. 49 и кое НЕ е попълнено.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ЗАПИСВА справката във файл {@code SPR73_6.xml} по схемата {@code dec736}.
   *
   * <p>⚠️ Форматът Е потвърден — заповед № З-ЦУ-1877/05.12.2019 г. Без изход към файл справката би
   * останала нещо, което се ГЛЕДА, но не се ПОДАВА.
   */
  public void generateFile(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para6Report report = managed(request, response);
      if (report == null) {
        return;
      }
      Beans.get(BgArt73Para6ReportService.class).generateFile(report);
      response.setNotify(
          "Файлът е генериран и валидиран срещу схемата dec736. Свали го и сравни SHA-256 преди"
              + " да го качиш на портала.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** ОБЕЗСИЛВА генерирания файл и връща справката в „Чернова“. */
  public void invalidateFile(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para6Report report = managed(request, response);
      if (report == null) {
        return;
      }
      Beans.get(BgArt73Para6ReportService.class).invalidateFile(report);
      response.setNotify(
          "Файлът е обезсилен. Артефактът НЕ е изтрит — той е непроменима следа; изчистено е само"
              + " посочването му от справката.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ОТБЕЛЯЗВА справката като ПОДАДЕНА през портала — с входящ номер и дата.
   *
   * <p><b>⚠️ ВХОДЯЩИЯТ НОМЕР Е ЗАДЪЛЖИТЕЛЕН.</b> Без него бутонът би сложил „Подадена“ без нито
   * едно доказателство. Каналът е {@code manual_portal} и по устройство не извършва транспорт —
   * свидетелят на подаването е НОМЕРЪТ. Същото правило като при ал. 1.
   */
  public void attach(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para6Report form = request.getContext().asType(BgArt73Para6Report.class);
      BgArt73Para6Report report = managed(request, response);
      if (report == null) {
        return;
      }
      if (report.getNapSubmission() != null
          && !BgDeclarationStatus.GENERATED.equals(report.getStatusSelect())) {
        response.setInfo(
            "Справката вече е отбелязана като подадена — "
                + report.getNapSubmission().getName()
                + ". За корекция генерирай НАНОВО и отбележи пак.");
        return;
      }
      boolean withFile = report.getGeneratedFile() != null;
      Beans.get(BgArt73Para6ReportService.class)
          .attach(report, form.getNapIncomingNumber(), form.getNapResponseDate());
      response.setNotify(
          withFile
              ? "Отбелязано като подадено през портала; нашият файл е закачен към подаването."
                  + " Нищо не е изпратено към НАП."
              : "Отбелязано като подадено през портала. ⚠️ БЕЗ прикачен файл от Nyxa —"
                  + " подаването сочи файла, който ти си качил.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ОТТЕГЛЯ ОТМЕТКАТА „ПОДАДЕНА“ — с причина. Единственият път към нова версия на подадена справка:
   * „Генерирай“, „Запиши файл“ и „Обезсили“ са заключени, докато справката е подадена.
   */
  public void withdraw(ActionRequest request, ActionResponse response) {
    try {
      BgArt73Para6Report form = request.getContext().asType(BgArt73Para6Report.class);
      BgArt73Para6Report report = managed(request, response, "withdraw an art. 73(6) filing");
      if (report == null) {
        return;
      }
      Beans.get(BgArt73Para6ReportService.class).withdraw(report, form.getWithdrawReason());
      response.setNotify(
          "Отметката „Подадена“ е оттеглена; подаването остава в историята като оттеглено."
              + " Справката може да се генерира и отбележи наново.");
      response.setReload(true);
    } catch (IllegalStateException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ЗАПИСАНАТА справка от контекста на екрана.
   *
   * <p>⚠️ Чете се НАНОВО по {@code id}, а не се ползва формата: екранът може да носи неприключени
   * промени, а файлът трябва да се строи от ЗАПИСАНОТО.
   */
  private BgArt73Para6Report managed(ActionRequest request, ActionResponse response) {
    return managed(request, response, "change an art. 73(6) report");
  }

  /**
   * Същото, с името на действието в отказа. Гейтът: {@code hr.payroll.write} + запис върху
   * справката + АКТИВНОТО дружество е нейното.
   */
  private BgArt73Para6Report managed(
      ActionRequest request, ActionResponse response, String action) {
    BgArt73Para6Report form = request.getContext().asType(BgArt73Para6Report.class);
    if (form.getId() == null) {
      response.setInfo(I18n.get("Запиши и генерирай справката, преди тази стъпка."));
      return null;
    }
    BgArt73Para6Report report = Beans.get(BgArt73Para6ReportRepository.class).find(form.getId());
    CapabilityGate.requireRole(action, Capabilities.PAYROLL_WRITE);
    CapabilityGate.requireWrite(BgArt73Para6Report.class, form.getId());
    if (report == null) {
      response.setInfo(I18n.get("Справката вече не съществува — презареди екрана."));
      return null;
    }
    CapabilityGate.requireActiveCompany(report.getCompany());
    return report;
  }
}

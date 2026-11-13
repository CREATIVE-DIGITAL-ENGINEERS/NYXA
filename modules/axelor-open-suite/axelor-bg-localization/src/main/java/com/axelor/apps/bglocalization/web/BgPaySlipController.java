package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.BgCalculationRefusedException;
import com.axelor.apps.bglocalization.service.BgPaySlipFingerprint;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipPrintService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.auth.AuthUtils;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.ContextEntity;
import com.axelor.rpc.ContextHandlerFactory;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Бутоните на фиша. Тънък слой: нито едно правило не живее тук. */
@Singleton
public class BgPaySlipController {

  /**
   * Нов фиш, смяна на договор, регистрация или период. Същият път като всяка друга промяна — {@link
   * #calculate}; името остава заради заварените препратки в изгледа.
   */
  public void prepare(ActionRequest request, ActionResponse response) {
    calculate(request, response);
  }

  /**
   * ПРЕСМЯТА ФИША НА ЕКРАНА: нов фиш, смяна на договор или период, всяка промяна на перата и на
   * замразените входове. Виж {@code BgPaySlipService#preview}. Бутонът „Преизчисли“ е {@link
   * #recalculate} — той и записва.
   *
   * <p><b>⚠️ ПРОМЯНАТА НА ПЕРАТА НЕ Е САМО recompute()</b>, който сумира: тогава перо 180 с
   * въведени 200,00 остава със сума 0,00, а вноските и данъкът — от предишното пресмятане.
   *
   * <p><b>⚠️ ОТКАЗЪТ НА ГЕНЕРАТОР СЕ ВИЖДА ТУК, А НЕ СЕ ЧАКА НЯКОЙ ДА РАЗГЪНЕ ПАНЕЛ.</b> {@code
   * setNotify}, а не {@code setError}: ръчното въвеждане е правило, не изключение, и ТРЗ има право
   * да добави перото сам. Спирането е при ПОТВЪРЖДАВАНЕТО и си има свои пазачи. Броят излиза от
   * {@code bgGeneratorRefusalCount}, а не от броене на редове в текста.
   *
   * <p><b>⚠️ НИЩО НЕ СЕ ЗАПИСВА ТУК — НИТО ФИШЪТ, НИТО ДРУГ ЗАПИС.</b> Ако този път пише т. 9 на
   * болничния лист веднага, а фишът остава незаписан, т. 9 оцелява и след „Откажи промените“.
   * Затова услугата е без транзакция и без запис извън фиша, а пресмятането върви върху {@link
   * #screenCopy}, в което няма нито един управляван ред: следата на {@code TraceBackService} след
   * грешка комитва всичко управлявано и мръсно в заявката. Отпечатъкът се изтрива — потвърждава се
   * чак след „Преизчисли“.
   */
  public void calculate(ActionRequest request, ActionResponse response) {
    try {
      BgPaySlip slip = screenCopy(request.getContext().asType(BgPaySlip.class));
      if (Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
        response.setError(I18n.get(HISTORICAL_IMPORT));
        return;
      }
      try {
        Beans.get(BgPaySlipService.class).preview(slip);
      } catch (BgCalculationRefusedException e) {
        // ⚠️ ОТКАЗ, НЕ СРИВ: копие на заварен фиш няма замразен режим или дни, а живите данни не
        // се четат тихо; или „само здравно“ е остаряло. Текстът назовава причината и пътя.
        response.setError(I18n.get(e.getMessage()));
        return;
      }
      response.setValues(slip);
      notifyAbout(slip, response);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * „ПРЕИЗЧИСЛИ“ — ПРЕСМЯТА И ЗАПИСВА ЧЕРНОВАТА, ЗАЕДНО С Т. 9 НА БОЛНИЧНИТЕ ЛИСТОВЕ, В ЕДНА
   * ТРАНЗАКЦИЯ. Виж {@code BgPaySlipService#calculateAndSave}.
   *
   * <p><b>⚠️ БЕЗ „save,“ ПРЕД ДЕЙСТВИЕТО, И ТОВА Е ЦЕЛТА.</b> Стандартният запис първо би записал
   * промените на екрана в СВОЯ транзакция; провал в пресмятането след него щеше да остави записан
   * фиш с непресметнати суми. Тук стойностите на формата влизат в същата транзакция като
   * пресмятането и т. 9 — всичко или нищо. След успех формата се презарежда от базата (новият фиш
   * получава идентификатора си в отговора), тоест показва точно записаното.
   *
   * <p>⚠️ Отказ и провал НЕ оставят нищо: rollback-ът кара Hibernate да изчисти контекста, преди
   * следата на {@code TraceBackService} да комитне — същото като при „Отрази пристигнал болничен“.
   */
  public void recalculate(ActionRequest request, ActionResponse response) {
    try {
      BgPaySlip onScreen = request.getContext().asType(BgPaySlip.class);
      // фишове — hr.payroll.write. „Преизчисли“ ЗАПИСВА черновата (calculateAndSave).
      CapabilityGate.requireRole("recalculate a pay slip", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgPaySlip.class, onScreen.getId());
      if (Boolean.TRUE.equals(onScreen.getBgIsHistoricalImport())) {
        response.setError(I18n.get(HISTORICAL_IMPORT));
        return;
      }
      BgPaySlip saved;
      try {
        saved = Beans.get(BgPaySlipService.class).calculateAndSave(formValues(request));
      } catch (BgCalculationRefusedException e) {
        response.setError(I18n.get(e.getMessage()));
        return;
      }
      response.setValue("id", saved.getId());
      response.setReload(true);
      notifyAbout(saved, response);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  private static final String HISTORICAL_IMPORT =
      "Исторически внесен фиш (миграция) — генераторите не пипат перата му. Ако сумите трябва да се"
          + " поправят, редактирайте ги на ръка.";

  /** Стойностите на формата — суровият контекст, по който и стандартният запис на Axelor пише. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> formValues(ActionRequest request) {
    Object context = request.getData() == null ? null : request.getData().get("context");
    return context instanceof Map ? (Map<String, Object>) context : new HashMap<>();
  }

  /**
   * ⚠️ Нулево бруто ПРИ НАЛИЧНИ ПЕРА е законен случай (цял месец неплатен отпуск) и затова само се
   * казва. Празният фиш е друго нещо и се спира при потвърждаване — виж refuseEmptyPaySlip.
   */
  private static void notifyAbout(BgPaySlip slip, ActionResponse response) {
    List<String> notes = new ArrayList<>();
    String refusals = BgPaySlipResolver.generatorRefusalWarning(slip);
    if (refusals != null) {
      notes.add(refusals);
    }
    String zeroGross = BgPaySlipResolver.zeroGrossWarning(slip.getBgPaySlipLineList());
    if (zeroGross != null) {
      notes.add(zeroGross);
    }
    if (!notes.isEmpty()) {
      response.setNotify(String.join("<br><br>", notes));
    }
  }

  /**
   * „ПОПЪЛНИ ОТ ТЕКУЩИТЕ ДАННИ“ — изричното действие за копие на заварен фиш (отпреди замразяването
   * на режима). Пише върху копието на екрана; одитът и режимът се записват с „Запиши“, тоест отказ
   * от формата не оставя нищо.
   */
  public void fillRegime(ActionRequest request, ActionResponse response) {
    try {
      BgPaySlip slip = screenCopy(request.getContext().asType(BgPaySlip.class));
      Beans.get(BgPaySlipService.class).fillRegimeFromCurrent(slip, AuthUtils.getUser());
      response.setValues(slip);
      response.setNotify(
          "Режимът е попълнен от текущите данни на договора и служителя. Проверете стойностите —"
              + " всяка промяна след това също остава в одита.");
    } catch (AxelorException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * ⚠️ КОПИЕТО НА ЕКРАНА — БЕЗ НИТО ЕДИН УПРАВЛЯВАН РЕД.
   *
   * <p>{@code ContextHandler#createOrFind} подава ред с идентификатор и без {@code version} като
   * УПРАВЛЯВАНАТА инстанция от базата, а без списък в контекста getter-ът връща списъка на
   * управлявания фиш. Генераторите пипат перата, а {@code calculate()} е в транзакция: commit-ът
   * щеше да запише тези промени при всяко onChange — върху фиш, който операторът още не е записал и
   * може да откаже. Затова всеки ред с идентификатор, който не е контекстно копие, се заменя с
   * прокси: стойностите му се четат от базата, а промените отиват в копието. Списъкът също е нов.
   *
   * <p>⚠️ И отговорът става верен: управляван ред се сериализира компактно (само идентификатор и
   * име), тоест пресметнатата му сума никога не би стигнала до екрана.
   *
   * <p><b>⚠️ ПРОКСИТО НОСИ ВСИЧКИ СТОЙНОСТИ НА РЕДА, НЕ САМО ИДЕНТИФИКАТОРА.</b> Отговорът
   * сериализира прокси само с полетата в него, тоест прокси само с {@code id} и {@code version}
   * дава идентификатора, версията и каквото генераторите са пипнали. Формата не дочита ред с
   * версия, а го показва такъв, какъвто е дошъл: преизчислените пера биха излезли без № и посока
   * (само име и сума), а непипнатите — без сума, докато нетото ги включва.
   */
  static BgPaySlip screenCopy(BgPaySlip slip) {
    List<BgPaySlipLine> lines = new ArrayList<>();
    if (slip.getBgPaySlipLineList() != null) {
      for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
        boolean unmanaged = line instanceof ContextEntity || line.getId() == null;
        lines.add(unmanaged ? line : proxyOf(line));
      }
    }
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  private static BgPaySlipLine proxyOf(BgPaySlipLine managed) {
    Mapper mapper = Mapper.of(BgPaySlipLine.class);
    Map<String, Object> values = new HashMap<>();
    for (Property p : mapper.getProperties()) {
      if (p.isCollection() || p.isVirtual() || mapper.getSetter(p.getName()) == null) {
        continue;
      }
      values.put(p.getName(), mapper.get(managed, p.getName()));
    }
    values.put("id", managed.getId());
    values.put("version", managed.getVersion());
    return ContextHandlerFactory.newHandler(BgPaySlipLine.class, values).getProxy();
  }

  /**
   * КОПИРА стойностите от речника върху перото при избор на вид.
   *
   * <p>Тук е единственият момент, в който речникът се чете. След това перото живее само — по-късна
   * редакция на речника не го докосва.
   */
  public void lineFromType(ActionRequest request, ActionResponse response) {
    BgPaySlipLine line = request.getContext().asType(BgPaySlipLine.class);
    BgPaySlipLineType type = line.getBgPaySlipLineType();
    if (type == null || type.getId() == null) {
      return;
    }
    type = Beans.get(BgPaySlipLineTypeRepository.class).find(type.getId());
    BgPaySlipLine filled = Beans.get(BgPaySlipService.class).lineFrom(type, line.getBgAmount());
    response.setValue("bgLineNumber", filled.getBgLineNumber());
    response.setValue("name", filled.getName());
    response.setValue("bgSign", filled.getBgSign());
    response.setValue("bgIsTaxable", filled.getBgIsTaxable());
    response.setValue("bgIsInsuranceIncome", filled.getBgIsInsuranceIncome());
    response.setValue("bgIsPermanentCharacter", filled.getBgIsPermanentCharacter());
    // ⚠️ ТЕЗИ ДВА И ЗАЩО ГАРАНЦИЯТА НЕ Е ТУК. Ехото пълни ЕКРАНА — операторът трябва да види
    // третирането, преди да запише. Но гаранцията е в
    // BgPaySlipManagementRepository#fillTreatmentFromType: REST-ът, вносът и решетката не минават
    // оттук изобщо. Само тук флагът би паднал при първия друг вход; само в услугата — би останал
    // празен на екрана.
    response.setValue("bgFormsGrossWage", filled.getBgFormsGrossWage());
    response.setValue("bgIsArt17Base", filled.getBgIsArt17Base());
  }

  /**
   * „Коригирай“: отваря коригиращо копие и ГО ПОКАЗВА.
   *
   * <p>⚠️ Показването не е удобство. Без него операторът натиска бутона, вижда „запазено“ и остава
   * пред ОРИГИНАЛА — тоест или мисли, че нищо не е станало, или започва да редактира замразения
   * фиш. Копието се отваря ПО ПРЕПРАТКАТА си ({@code bgCorrectionOf}), а не по идентификатора си,
   * защото идентификаторът се появява чак след flush и „кога точно“ е обещание на слоя за достъп,
   * не наше.
   */
  public void correct(ActionRequest request, ActionResponse response) {
    openRetro(request, response, false);
  }

  /**
   * Общият път на двата бутона. Копието се отваря ПО ПРЕПРАТКАТА си, а не по идентификатора си,
   * защото идентификаторът се появява чак след flush и „кога точно“ е обещание на слоя за достъп,
   * не наше.
   */
  private void openRetro(ActionRequest request, ActionResponse response, boolean deletion) {
    try {
      BgPaySlip context = request.getContext().asType(BgPaySlip.class);
      if (context.getId() == null) {
        response.setError(
            (deletion ? "Заличава" : "Коригира") + " се записан фиш — запишете този първо.");
        return;
      }
      BgPaySlip original = Beans.get(BgPaySlipRepository.class).find(context.getId());
      CapabilityGate.requireRole("correct or void a pay slip", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgPaySlip.class, context.getId());
      BgPaySlipService service = Beans.get(BgPaySlipService.class);
      if (deletion) {
        service.voidRecord(original);
      } else {
        service.correct(original);
      }
      response.setView(
          ActionView.define(deletion ? "Заличаващ фиш" : "Коригиращ фиш")
              .model(BgPaySlip.class.getName())
              .add("grid", "bg-payslip-grid")
              .add("form", "bg-payslip-form")
              .domain(
                  deletion
                      ? "self.bgDeletionOf.id = :originalId"
                      : "self.bgCorrectionOf.id = :originalId")
              .context("originalId", original.getId())
              .map());
    } catch (AxelorException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Заличи“: симетрично на {@link #correct} — отваря заличаващото копие и го показва. */
  public void voidRecord(ActionRequest request, ActionResponse response) {
    openRetro(request, response, true);
  }

  /**
   * ПЕЧАТ НА ЕДИН ФИШ — PDF-ът се ражда и се ЗАКАЧА за записа.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ОТ БАЗАТА, А НЕ ОТ КОНТЕКСТА.</b> Бутонът е вързан като {@code save,action-…},
   * тоест екранът вече е записан. Печат по контекста би отпечатал непотвърдени редакции от екрана и
   * ЗАКАЧИЛ листа за запис, който казва друго — тоест приложеният документ щеше да спори със
   * собствения си носител.
   *
   * <p><b>⚠️ И ЧЕРНОВАТА СЕ ПЕЧАТА.</b> За разлика от Д1/Д6, където печатът чака генериране, фишът
   * се разпечатва на всеки статус: ТРЗ сверява черновата ИМЕННО на хартия, преди да я потвърди.
   * Състоянието е НАПИСАНО върху листа — виж {@code BgPaySlipPrintService#statusLabel}.
   */
  public void print(ActionRequest request, ActionResponse response) {
    try {
      BgPaySlip context = request.getContext().asType(BgPaySlip.class);
      if (context.getId() == null) {
        response.setInfo(I18n.get("Запишете фиша, преди да го печатате."));
        return;
      }
      CapabilityGate.requireRead(BgPaySlip.class, context.getId());
      String fileLink =
          Beans.get(BgPaySlipPrintService.class)
              .print(
                  Beans.get(BgPaySlipRepository.class).find(context.getId()),
                  IReport.BG_PAY_SLIP,
                  ReportSettings.FORMAT_PDF,
                  "Фиш");
      response.setView(
          ActionView.define("Фиш").add("html", fileLink).param("download", "false").map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  public void confirm(ActionRequest request, ActionResponse response) {
    run(request, response, BgPaySlipResolver.CONFIRMED);
  }

  public void pay(ActionRequest request, ActionResponse response) {
    run(request, response, BgPaySlipResolver.PAID);
  }

  public void cancel(ActionRequest request, ActionResponse response) {
    run(request, response, BgPaySlipResolver.CANCELLED);
  }

  /** Записът се чете НАНОВО: контекстът носи екрана, а преходите съдят по базата. */
  private void run(ActionRequest request, ActionResponse response, String target) {
    try {
      BgPaySlip context = request.getContext().asType(BgPaySlip.class);
      BgPaySlipRepository repo = Beans.get(BgPaySlipRepository.class);
      BgPaySlip slip = context.getId() == null ? context : repo.find(context.getId());
      CapabilityGate.requireRole("change the status of a pay slip", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireWrite(BgPaySlip.class, context.getId());
      if (Boolean.TRUE.equals(slip.getBgIsHistoricalImport())) {
        response.setError(
            "Исторически внесен фиш (миграция) — статусните преходи (потвърждаване/плащане/отказ) са"
                + " изключени, за да не тръгне счетоводна статия или преизчисление за минал период,"
                + " който вече е бил приключен в Microinvest.");
        return;
      }
      if (context.getId() != null && BgPaySlipResolver.CONFIRMED.equals(target)) {
        // ⚠️ ПОТВЪРЖДАВАНЕТО НЕ ЗАПИСВА НИЩО ОТ ЕКРАНА ПРЕДИ ПРОВЕРКИТЕ. Пренесени от контекста в
        // записа точно преди confirm(), работните и отработените дни биха влезли в потвърдения
        // фиш като числа, по които нищо не е пресметнато, покрай всички пазачи. Бутонът записва
        // фиша преди това действие, тоест екран и запис съвпадат; различни значи, че записът не е
        // минал, и се казва.
        List<String> differs = BgPaySlipFingerprint.screenDiffers(slip, context);
        if (!differs.isEmpty()) {
          response.setError(
              "Данните на екрана се различават от записаните ("
                  + String.join("; ", differs)
                  + "). Запишете и натиснете „Преизчисли“.");
          return;
        }
      } else if (context.getId() != null) {
        slip.setBgPaidDate(context.getBgPaidDate());
        slip.setNote(context.getNote());
      }
      BgPaySlipService service = Beans.get(BgPaySlipService.class);
      if (BgPaySlipResolver.CONFIRMED.equals(target)) {
        service.confirm(slip);
      } else if (BgPaySlipResolver.PAID.equals(target)) {
        service.pay(slip);
      } else {
        // ⚠️ ПРИЧИНАТА Е ОТ ВХОДА НА БУТОНА, НЕ ОТ bgCancelReason. То се показва само СЛЕД
        // отказа и само за четене, тоест оттук винаги идва празно.
        if (context.getId() == null) {
          response.setError(I18n.get("Отказва се записан фиш — незаписаният просто се затваря."));
          return;
        }
        service.cancel(slip, context.getBgCancelReasonInput());
      }
      response.setReload(true);
    } catch (AxelorException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

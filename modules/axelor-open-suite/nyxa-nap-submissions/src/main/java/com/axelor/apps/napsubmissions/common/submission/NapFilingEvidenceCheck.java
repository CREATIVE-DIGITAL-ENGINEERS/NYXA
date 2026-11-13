package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.common.immutability.NapSubmissionEvidence;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.db.mapper.Mapper;
import com.axelor.i18n.I18n;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ПРАВИЛОТО НА #334 — ПРЕДИ ЗАПИСА, С БЪЛГАРСКИ ОТКАЗ.
 *
 * <p>Подаденото подаване е доказателство; какво от него още може да се пише решава само {@link
 * NapSubmissionEvidence#checkSubmissionUpdate} (и неговият слушател при запис). Тук то се пита
 * ПРЕДИ действието да е направило каквото и да е — преди клиента на API-то, преди одита — със
 * същите полета и стойности, които действието ще запише. Логиката не се копира: всяко поле се
 * подава на правилото поотделно, за да може отказът да назове КОЕ поле е замразено.
 */
public final class NapFilingEvidenceCheck {

  /** Идентификаторите — правилото по тях решава „повторно изпращане“; винаги са в сравнението. */
  private static final List<String> CONTEXT =
      List.of("submissionStatusSelect", "napIncomingNumber", "entryNumber", "documentId");

  private static final Map<String, String> LABELS =
      Map.of(
          "submissionStatusSelect", "status",
          "channelSelect", "channel",
          "napIncomingNumber", "incoming number",
          "submittedOn", "time of submission",
          "submittedBy", "submitted by");

  private NapFilingEvidenceCheck() {}

  /**
   * @param planned поле → стойността, която действието ще запише
   * @param action кое действие, за отказа (напр. „Ръчното отбелязване“)
   * @throws NapFilingLockedException ако правилото на #334 не позволява някое от полетата
   */
  public static void requireWritable(
      NapSubmission submission, Map<String, Object> planned, String action) {
    Mapper mapper = Mapper.of(NapSubmission.class);
    for (Map.Entry<String, Object> change : planned.entrySet()) {
      Map<String, Object> before = new LinkedHashMap<>();
      for (String name : CONTEXT) {
        before.put(name, mapper.get(submission, name));
      }
      before.put(change.getKey(), mapper.get(submission, change.getKey()));
      Map<String, Object> after = new LinkedHashMap<>(before);
      after.put(change.getKey(), change.getValue());

      String[] names = before.keySet().toArray(new String[0]);
      try {
        NapSubmissionEvidence.checkSubmissionUpdate(
            names, before.values().toArray(), after.values().toArray());
      } catch (RegulatoryImmutabilityException e) {
        throw new NapFilingLockedException(
            String.format(
                I18n.get(
                    "The submission has already been filed (status “%s”) and is evidence: the field"
                        + " “%s” cannot be changed — it is part of what was filed. %s was not"
                        + " saved."),
                submission.getSubmissionStatusSelect(),
                I18n.get(LABELS.getOrDefault(change.getKey(), change.getKey())),
                I18n.get(action)));
      }
    }
  }
}

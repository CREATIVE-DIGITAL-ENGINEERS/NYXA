package com.axelor.apps.bglocalization.service.demo;

import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * „НАТИСНИ БУТОНА“ — действие от формата, както го вика екранът: контролерът получава контекст на
 * записа, а върнатите стойности се прилагат върху записа и той се записва (бутоните са {@code
 * save,action,save}).
 *
 * <p>Така наливането минава през същите проверки и съобщения като ръчната работа — например ДДС
 * справката: декларатор, адрес за кореспонденция, снимка на дневниците, пакет, статус „Генерирана“.
 * Грешка в отговора ({@code error}) спира наливането с текста ѝ.
 */
final class BgDemoForm {

  interface Action {
    void call(ActionRequest request, ActionResponse response);
  }

  private BgDemoForm() {}

  /** Натиска действието върху записа {@code id} и записва върнатите стойности. */
  static <T extends Model> ActionResponse press(Class<T> type, Long id, Action action)
      throws Exception {
    return press(type, id, Map.of(), action);
  }

  /** Същото, с полета от екрана в контекста (например версията или дата, въведена преди бутона). */
  static <T extends Model> ActionResponse press(
      Class<T> type, Long id, Map<String, Object> screen, Action action) throws Exception {
    ActionRequest request = new ActionRequest();
    request.setModel(type.getName());
    Map<String, Object> context = new HashMap<>(screen);
    context.put("_model", type.getName());
    context.put("id", id);
    request.setData(Map.of("context", context));
    ActionResponse response = new ActionResponse();
    action.call(request, response);
    Object error = item(response).get("error");
    if (error != null) {
      throw new IllegalStateException(String.valueOf(error));
    }
    apply(type, id, response);
    return response;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> item(ActionResponse response) {
    Object data = response.getData();
    if (data instanceof List && !((List<?>) data).isEmpty()) {
      Object first = ((List<?>) data).get(0);
      if (first instanceof Map) {
        return (Map<String, Object>) first;
      }
    }
    return new HashMap<>();
  }

  /** Стойностите от отговора → записът (както формата преди {@code save}). */
  @SuppressWarnings("unchecked")
  private static <T extends Model> void apply(Class<T> type, Long id, ActionResponse response)
      throws Exception {
    Object values = item(response).get("values");
    if (!(values instanceof Map) || ((Map<?, ?>) values).isEmpty()) {
      return;
    }
    BgDemoTx.run(
        () -> {
          T record = JPA.find(type, id);
          Mapper mapper = Mapper.of(type);
          for (Map.Entry<String, Object> e : ((Map<String, Object>) values).entrySet()) {
            Property p = mapper.getProperty(e.getKey());
            if (p == null) {
              continue; // поле само на изгледа
            }
            Object value = e.getValue();
            if (value instanceof Map && p.getTarget() != null) {
              Object targetId = ((Map<?, ?>) value).get("id");
              value =
                  targetId == null
                      ? null
                      : JPA.em().find(p.getTarget(), ((Number) targetId).longValue());
            }
            p.set(record, value);
          }
          JPA.save(record);
        });
  }
}

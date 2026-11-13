package com.axelor.apps.aiaccountant.tool.impl;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.base.db.Company;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Harmless read-only tool that proves the whole loop: chat → Foundry → tool request → Java handler
 * → tool result → Foundry → final answer.
 *
 * <p>Takes no arguments and touches no business data, so it can be exercised on any environment.
 */
@Singleton
public class GetCurrentUserInfoTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_current_user_info";
  }

  @Override
  public String description() {
    // The [Nyxa context] block already carries user, active company and today's date on every
    // turn, so this is now a fallback for the rare turn that has no context. Saying so keeps the
    // model from spending a request re-asking for what it was just told.
    return "The current user's login code, name, language, active company and today's date. The "
        + "[Nyxa context] block normally states all of this already — call this only if that block "
        + "is missing what the user asked for.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.set("properties", MAPPER.createObjectNode());
    schema.putArray("required");
    // The model must not invent arguments for a no-arg tool.
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.CORE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    User user = AuthUtils.getUser();
    if (user == null) {
      throw new AiToolException("No authenticated user in context.");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("userCode", user.getCode());
    out.put("userName", user.getName());

    // Report the company the assistant is actually confined to, not just the raw preference field.
    // Those can differ (an unset activeCompany resolves to the single permitted company), and if
    // the answer here disagreed with what every other tool can see, the model would be describing
    // one company while reading another.
    Company company = null;
    try {
      company = AiCompanyScope.requireCompany();
    } catch (Exception e) {
      company = null;
    }
    out.put("activeCompany", company == null ? null : company.getName());
    out.put("activeCompanyId", company == null ? null : company.getId());
    out.put(
        "dataScope",
        company == null
            ? "No company is selected, so no company data can be read."
            : "Every record you can read or change belongs to "
                + company.getName()
                + ". Other companies' data is not visible to you at all.");
    out.put("language", user.getLanguage());
    out.put("today", LocalDate.now().toString());
    return out;
  }
}

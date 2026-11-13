package com.axelor.apps.aiaccountant.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool as declared to the model: {@code {type:"function", name, description, parameters}}.
 *
 * <p>The schema describes arguments to the model. It is NOT a runtime validator — handlers validate
 * and deserialise their own typed arguments (see {@code AiToolArgs}).
 */
public class AiToolSpec {

  private final String name;
  private final String description;
  private final JsonNode parameters;

  public AiToolSpec(String name, String description, JsonNode parameters) {
    this.name = name;
    this.description = description;
    this.parameters = parameters;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public JsonNode getParameters() {
    return parameters;
  }
}

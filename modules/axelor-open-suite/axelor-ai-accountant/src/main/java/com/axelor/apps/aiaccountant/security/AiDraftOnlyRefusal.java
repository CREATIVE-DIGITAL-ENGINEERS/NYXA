package com.axelor.apps.aiaccountant.security;

import javax.persistence.PersistenceException;

/**
 * An AI write tool tried to make an accounting entry Accounted. The assistant only prepares drafts
 * ({@code accounting.ai.write}); validating is the user's, from the form.
 *
 * <p>Thrown when the entry is flushed, so it can arrive wrapped by whatever service the tool went
 * through; {@link #find} digs it out.
 */
public class AiDraftOnlyRefusal extends PersistenceException {

  private static final long serialVersionUID = 1L;

  /** Written for the model to relay: what happened, and what the user does next. */
  public static final String MESSAGE =
      "The assistant only prepares drafts and cannot post to the books. Nothing was posted: the"
          + " invoice or entry stays saved as a draft. Tell the user it is saved as a draft and"
          + " that they need to validate it themselves from its form. Do not try to post it again.";

  public AiDraftOnlyRefusal() {
    super(MESSAGE);
  }

  /** The refusal somewhere in the cause chain of {@code error}, or null. */
  public static AiDraftOnlyRefusal find(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof AiDraftOnlyRefusal) {
        return (AiDraftOnlyRefusal) t;
      }
    }
    return null;
  }
}

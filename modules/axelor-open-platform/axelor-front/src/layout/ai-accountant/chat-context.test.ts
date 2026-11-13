import { describe, expect, it } from "vitest";

import { hasOpenRecord, resolvePageContext } from "./chat-context";

const INVOICE = "com.axelor.apps.account.db.Invoice";
const UPLOAD = "com.axelor.apps.aiocr.db.AiOcrUpload";

describe("resolvePageContext", () => {
  it("reports the record when the form view is the one on screen", () => {
    const ctx = resolvePageContext(UPLOAD, "form", 946, "ai-ocr-upload-form");
    expect(ctx).toMatchObject({
      model: UPLOAD,
      recordId: 946,
      viewType: "form",
    });
    expect(hasOpenRecord(ctx)).toBe(true);
  });

  // The C6 bug. `routes.form` is a remembered route and survives navigation back to the grid, so
  // the panel kept sending a record the user had closed - and the assistant argued about it when
  // asked to search.
  it("drops a remembered form route once the tab shows a list", () => {
    const ctx = resolvePageContext(UPLOAD, "grid", 946, "ai-ocr-upload-grid");
    expect(ctx?.recordId).toBeUndefined();
    expect(ctx?.viewType).toBe("grid");
    expect(hasOpenRecord(ctx)).toBe(false);
  });

  it("says which list it is, rather than saying nothing", () => {
    const ctx = resolvePageContext(INVOICE, "grid", undefined, "invoice-grid");
    expect(ctx).toMatchObject({ model: INVOICE, viewType: "grid" });
    expect(ctx?.recordId).toBeUndefined();
  });

  it("treats an unsaved new record as no record", () => {
    expect(
      resolvePageContext(UPLOAD, "form", "", undefined)?.recordId,
    ).toBeUndefined();
    expect(
      resolvePageContext(UPLOAD, "form", 0, undefined)?.recordId,
    ).toBeUndefined();
  });

  it("sends nothing at all when no module is open", () => {
    expect(
      resolvePageContext(undefined, "grid", 946, undefined),
    ).toBeUndefined();
  });
});

import { useAtom, useAtomValue } from "jotai";
import { selectAtom } from "jotai/utils";
import { ChangeEvent, useMemo, useRef, useState } from "react";

import { clsx, Box, Button, ButtonGroup } from "@axelor/ui";
import { MaterialIcon } from "@axelor/ui/icons/material-icon";

import { alerts } from "@/components/alerts";
import { DataStore } from "@/services/client/data-store";
import { DataRecord } from "@/services/client/data.types";
import { download } from "@/utils/download";

import { FieldControl, FieldProps } from "../../builder";
import {
  META_FILE_MODEL,
  makeImageURL,
  validateFileSize,
} from "../image/utils";

import styles from "./binary-link.module.scss";
import { i18n } from "@/services/client/i18n.ts";

export function BinaryLink(props: FieldProps<DataRecord | undefined | null>) {
  const { schema, readonly, invalid, formAtom, widgetAtom, valueAtom } = props;
  const { name, accept, $json, widgetAttrs } = schema;

  const [value, setValue] = useAtom(valueAtom);
  const { attrs } = useAtomValue(widgetAtom);
  const inputRef = useRef<HTMLInputElement>(null);
  const formRef = useRef<HTMLFormElement>(null);

  // The upload is a bare XMLHttpRequest (DataStore.upload), so it bypasses the platform's HttpWatch
  // indicator: not even the usual "Loading…" pill appears, and on a hosted server a multi-MB file
  // can take ~20 s with no sign of life at all. Users conclude it failed and navigate away.
  const [uploading, setUploading] = useState(false);

  // Opt-in per field via `x-clear-before-replace="true"`. dataStore.save is called with the existing
  // MetaFile id + version, so picking a second file REPLACES the bytes server-side before any
  // onChange validation can run — a rejected upload has by then destroyed the previously valid file.
  // Where the flag is set, replacing means pressing × first (value becomes null, so the upload
  // creates a NEW MetaFile). Absent, every other binary-link keeps one-step in-place replacement.
  // Arbitrary x-* attributes are not in meta-utils' boolean coercion list, so this arrives as the
  // string "true" — compare, don't Boolean() (which would also accept "false").
  const clearBeforeReplace =
    String(widgetAttrs?.clearBeforeReplace).toLowerCase() === "true";
  const uploadDisabled = uploading || (clearBeforeReplace && value != null);

  const recordAtom = useMemo(
    () =>
      selectAtom(formAtom, (x) => {
        const { record } = x;
        return $json ? record?.$record : record;
      }),
    [formAtom, $json],
  );

  const parentId = useAtomValue(
    useMemo(() => selectAtom(recordAtom, (x) => x.id), [recordAtom]),
  );

  const parentVersion = useAtomValue(
    useMemo(() => selectAtom(recordAtom, (x) => x.version), [recordAtom]),
  );

  const parentModel = useAtomValue(
    useMemo(() => selectAtom(formAtom, (x) => x.model), [formAtom]),
  );

  const parent = {
    id: parentId,
    version: parentVersion,
    _model: parentModel,
  } as DataRecord;

  function handleUpload() {
    if (uploadDisabled) return;
    const input = inputRef.current;
    input && input.click();
  }

  function handleDownload() {
    const { target, name } = schema;
    const fileURL = makeImageURL(value, target, name, parent, true);
    download(fileURL, value?.fileName || name);
  }

  function handleRemove() {
    // Never clear mid-flight: the XHR is already carrying this value's id, so removing it here
    // would strand the response on a field that no longer expects it.
    if (uploading) return;
    const input = inputRef.current;
    input && (input.value = "");
    setValue(null, true);
  }

  async function handleInputChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e?.target?.files?.[0];

    inputRef.current && (inputRef.current.value = "");

    if (file && validateFileSize(file)) {
      const dataStore = new DataStore(META_FILE_MODEL);
      // The toast self-dismisses after 3 s while the transfer can run much longer, so it announces
      // and the inline "Uploading…" text below sustains. Nothing blocks the UI: no overlay, no
      // modal — the user can still navigate away (see the caveat in the OCR upload plan).
      setUploading(true);
      alerts.info({
        message: i18n.get("Uploading {0}…", file.name),
      });
      try {
        const metaFile = await dataStore.save({
          id: value?.id,
          version: value?.version ?? value?.$version,
          fileName: file.name,
          fileType: file.type,
          fileSize: file.size,
          $upload: { file },
        });
        if (metaFile.id) {
          setValue(metaFile, true, value?.id == null);
        }
      } catch (err) {
        // This catch used to be empty, with a comment claiming the error was "already handled
        // upstream" — it is not: DataStore.upload uses a raw XMLHttpRequest, which bypasses the
        // fetch interceptor entirely, so a failed upload was silently invisible.
        console.error("File upload failed", err);
        alerts.error({
          message:
            (err as { message?: string })?.message ||
            i18n.get("Could not upload {0}. Please try again.", file.name),
        });
      } finally {
        setUploading(false);
      }
    }
  }

  const text = value?.fileName;

  if (attrs.hidden) return null;

  return (
    <FieldControl {...props}>
      <Box d="flex">
        {!readonly && (
          <>
            <form ref={formRef}>
              <Box
                as={"input"}
                onChange={handleInputChange}
                type="file"
                ref={inputRef}
                d="none"
                accept={accept}
              />
            </form>
            <Box
              d="flex"
              alignItems="center"
              className={clsx(styles.container, { [styles.invalid]: invalid })}
            >
              <ButtonGroup border className={styles.btns}>
                <Button
                  variant="light"
                  d="flex"
                  alignItems="center"
                  title={
                    clearBeforeReplace && value != null
                      ? i18n.get("Remove the current file first")
                      : i18n.get("Upload")
                  }
                  disabled={uploadDisabled}
                  onClick={handleUpload}
                >
                  <MaterialIcon icon="upload" />
                </Button>
                <Button
                  variant="light"
                  d="flex"
                  alignItems="center"
                  title={i18n.get("Remove")}
                  disabled={uploading}
                  onClick={handleRemove}
                >
                  <MaterialIcon icon="close" />
                </Button>
              </ButtonGroup>
            </Box>
          </>
        )}
        {uploading && (
          <Box d="flex" alignItems="center" className={styles.uploading}>
            {i18n.get("Uploading…")}
          </Box>
        )}
        {text && (
          <Button
            textAlign="start"
            variant="link"
            title={name}
            onClick={() => handleDownload()}
          >
            {text}
          </Button>
        )}
      </Box>
    </FieldControl>
  );
}

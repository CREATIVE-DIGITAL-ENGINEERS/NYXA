import { useCallback, useEffect, useRef, useState } from "react";

import { Dialog, DialogContent, Fade, Portal } from "@axelor/ui";

import { FORM_CLIENT_NAME, requestLogin } from "@/routes/login";
import { i18n } from "@/services/client/i18n";
import { SessionInfo, session } from "@/services/client/session";

import { Loader } from "../loader/loader";
import { LoginForm } from "../login-form";
import { useHttpBlock } from "./use-block";
import { useHttpWatch } from "./use-watch";

import styles from "./http-watch.module.scss";

export function HttpWatch() {
  const { count, resume } = useHttpWatch();
  return (
    <>
      <HttpIndicator count={count} />
      <HttpBlock count={count} disabled={Boolean(resume)} />
      <HttpAuth resume={resume} />
    </>
  );
}

function HttpIndicator({ count }: { count: number }) {
  const [show, setShow] = useState(false);

  const timerRef = useRef<number>();
  const mountRef = useRef(false);
  const countRef = useRef(count);

  const handleShow = useCallback(() => {
    countRef.current = count;
    if (timerRef.current && count > 0) return;
    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      if (mountRef.current) setShow(countRef.current > 0);
      timerRef.current = undefined;
    }, 300);
  }, [count]);

  useEffect(() => {
    mountRef.current = true;
    return () => {
      mountRef.current = false;
    };
  }, []);

  useEffect(() => () => window.clearTimeout(timerRef.current), []);
  useEffect(handleShow, [handleShow]);

  return (
    <Portal>
      <Fade in={show} mountOnEnter unmountOnExit>
        <div className={styles.indicator}>{i18n.get("Loading...")}</div>
      </Fade>
    </Portal>
  );
}

function HttpBlock({ count, disabled }: { count: number, disabled?: boolean }) {
  const [wait, setWait] = useState(false);
  const [block, setBlock] = useState(false);
  const [blocked] = useHttpBlock();

  const _block = !disabled && (blocked || wait);
  const active = blocked || count > 0;

  useEffect(() => {
    const delay = active ? 5000 : 300;
    const timer = setTimeout(() => {
      setWait(active);
    }, delay);
    return () => {
      clearTimeout(timer);
    };
  }, [active, count]);

  useEffect(() => {
    if (_block) {
      // Delay block component in order to have correct target in click event listeners.
      // Needed for correct event target in click away handling.
      const timer = setTimeout(() => setBlock(_block), 100);

      // Block keyboard events
      const handler = (event: KeyboardEvent) => {
        const activeElement = document.activeElement;

        if (
          activeElement &&
          ["INPUT", "TEXTAREA"].includes(activeElement.tagName) &&
          !["Enter", "Tab"].includes(event.key)
        ) {
          return;
        }

        event.stopPropagation();
        event.preventDefault();
      };
      document.addEventListener("keydown", handler, true);

      return () => {
        clearTimeout(timer);
        document.removeEventListener("keydown", handler, true);
      };
    } else {
      setBlock(_block);
    }
  }, [_block]);

  return (
    <Portal>
      <Fade in={block} mountOnEnter unmountOnExit>
        <div className={styles.block}>
          <Fade in={wait} mountOnEnter unmountOnExit>
            <div className={styles.wait}>
              <Loader delay={10} text={i18n.get("Please wait...")} />
            </div>
          </Fade>
        </div>
      </Fade>
    </Portal>
  );
}

function HttpAuth({ resume }: { resume?: () => void }) {
  const prev = session.info?.user?.login;

  // With a central provider as the only registered client there is nothing to re-authenticate
  // against locally, so the resume dialog would offer a form whose submit cannot succeed. Send
  // the browser through the provider instead; a live SSO session makes the trip invisible.
  const { exclusive, defaultClient } = session.info?.authentication ?? {};
  const central = Boolean(
    exclusive || (defaultClient && defaultClient !== FORM_CLIENT_NAME),
  );

  useEffect(() => {
    if (resume && central) requestLogin();
  }, [resume, central]);

  const handleSuccess = useCallback(
    (info: SessionInfo) => {
      const curr = info.user?.login;
      if (prev !== curr) {
        window.location.reload();
      } else {
        resume?.();
      }
    },
    [prev, resume],
  );

  if (central) return null;

  return (
    <Portal>
      <Dialog open={Boolean(resume)} backdrop>
        <DialogContent>
          <LoginForm onSuccess={handleSuccess} />
        </DialogContent>
      </Dialog>
    </Portal>
  );
}

import { useMemo } from "react";

import { useTheme } from "@axelor/ui/core";

import { i18n } from "@/services/client/i18n";
import { useSession } from "../use-session";

export const APPLICATION_NAME = "Axelor";
const DESCRIPTION = "Axelor Enterprise Application";
export const COPYRIGHT = `© ${new Date().getFullYear()} Axelor. ${i18n.get(
  "All Rights Reserved",
)}.`;

// application.copyright arrives with {year} already filled in by the server; put it back as {0}
// so the notice can be translated in messages_*.csv with one key for every year. Untranslated
// text comes back as it was.
function translateCopyright(copyright: string) {
  const year = String(new Date().getFullYear());
  return copyright.includes(year)
    ? i18n.get(copyright.replace(year, "{0}"), year)
    : i18n.get(copyright);
}

export function useAppSettings() {
  const { data: info, state } = useSession();
  const { mode = "light", theme } = useTheme();

  return useMemo(() => {
    const themeMode = theme === "dark" ? theme : mode;
    const isReady = state === "hasData";
    const name = info?.application.name ?? APPLICATION_NAME;
    const description = info?.application.description ?? DESCRIPTION;
    const appCopyright = (
      info?.application.copyright?.replace("&copy;", "©") ?? ""
    ).trim();
    const copyright = appCopyright
      ? translateCopyright(appCopyright)
      : COPYRIGHT;

    return {
      isReady,
      name,
      description,
      copyright,
      themeMode,
    };
  }, [
    mode,
    state,
    theme,
    info?.application.copyright,
    info?.application.description,
    info?.application.name,
  ]);
}

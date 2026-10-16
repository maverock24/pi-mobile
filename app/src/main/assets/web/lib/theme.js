"use strict";

// The appearance and theme controls, matching the phone's own Settings screen:
// the same three appearances and the same four dark palettes. The phone keeps
// its choice in SettingsStore and the browser cannot read that file, so this is
// the browser's own copy of the choice rather than the phone's. Syncing the two
// would need a new relay setting route, which is not built; the settings screen
// says so. The choice is a display preference and not a credential, so it is
// kept in localStorage and survives a reload.
//
// The palettes are the phone's own values from AnswerStyle.kt: midnight, indigo,
// amber and forest on the dark side, and the single light scheme, which ignores
// the theme exactly as the phone's does.
const THEMES = ["midnight", "indigo", "amber", "forest"];
const APPEARANCES = ["dark", "light", "system"];

const STORAGE_THEME = "pi-web-theme";
const STORAGE_APPEARANCE = "pi-web-appearance";

function stored(key, allowed, fallback) {
  try {
    const value = window.localStorage.getItem(key);
    return allowed.includes(value) ? value : fallback;
  } catch (error) {
    return fallback;
  }
}

// Resolves "system" against the browser's own preference, so a browser set to
// dark follows the operating system the way the phone does.
function resolvedMode(appearance) {
  if (appearance === "dark" || appearance === "light") return appearance;
  return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function createTheme() {
  let appearance = stored(STORAGE_APPEARANCE, APPEARANCES, "dark");
  let theme = stored(STORAGE_THEME, THEMES, "midnight");
  const listeners = new Set();

  const media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  function apply() {
    const root = document.documentElement;
    root.dataset.mode = resolvedMode(appearance);
    root.dataset.theme = theme;
    listeners.forEach((listener) => listener({ appearance, theme }));
  }

  function setAppearance(value) {
    if (!APPEARANCES.includes(value)) return;
    appearance = value;
    try {
      window.localStorage.setItem(STORAGE_APPEARANCE, value);
    } catch (error) {
      // A browser that refuses storage still gets the choice for this page.
    }
    apply();
  }

  function setTheme(value) {
    if (!THEMES.includes(value)) return;
    theme = value;
    try {
      window.localStorage.setItem(STORAGE_THEME, value);
    } catch (error) {
      // As above.
    }
    apply();
  }

  if (media) {
    // A system change only matters while the appearance follows the system.
    media.addEventListener("change", () => {
      if (appearance === "system") apply();
    });
  }
  apply();

  return {
    get appearance() {
      return appearance;
    },
    get theme() {
      return theme;
    },
    get themes() {
      return THEMES.slice();
    },
    get appearances() {
      return APPEARANCES.slice();
    },
    setAppearance,
    setTheme,
    subscribe(listener) {
      listeners.add(listener);
      listener({ appearance, theme });
      return () => listeners.delete(listener);
    },
  };
}

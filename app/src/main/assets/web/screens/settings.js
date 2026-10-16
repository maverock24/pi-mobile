"use strict";

import { readJson } from "../lib/relay.js";

// The Settings screen. The phone's Settings has sections a browser session can
// act on and sections it cannot, and the rule is to say which is which rather
// than draw a control that does nothing. Appearance applies (the browser gets
// its own copy of the phone's palettes, see lib/theme.js); the session identity
// applies. Pairing, the app's own updates, the crash log and clearing the
// phone's data are phone work, so they are stated as such and not offered.
export function createSettingsScreen({ relay, onUnauthorized, theme }) {
  const state = { sessionId: null, sessionName: null, model: null, idle: null, error: null };

  const root = document.createElement("div");
  root.className = "settings";

  const head = document.createElement("div");
  head.className = "screen-head";
  const title = document.createElement("span");
  title.className = "screen-title";
  title.textContent = "Settings";
  head.append(title);

  const notice = document.createElement("p");
  notice.className = "notice bad";
  notice.hidden = true;
  notice.setAttribute("role", "status");

  root.append(head, notice);

  // ---- session ----
  const sessionSection = section("Session", "This browser's approved session on the phone");
  const sessionBody = sessionSection.body;
  root.append(sessionSection.element);

  function render() {
    notice.hidden = !state.error;
    notice.textContent = state.error || "";
    sessionBody.textContent = "";
    if (state.error) {
      sessionBody.append(line(state.error, "bad"));
    }
    sessionBody.append(
      field("Session", state.sessionId ? state.sessionId.slice(0, 8) : "…"),
      field("Name", state.sessionName || "…"),
      field("Model", state.model || "…"),
      field("Run", state.idle === null ? "…" : state.idle ? "idle" : "working"),
    );
    sessionBody.append(
      note(
        "The phone mints this session when it answers the approval, and its idle and absolute " +
          "timeouts end it. Disconnect in the header ends it now.",
      ),
    );
  }

  // ---- appearance ----
  const appearanceSection = section("Appearance", "Dark, light or the browser's own preference");
  root.append(appearanceSection.element);
  const appearanceRow = document.createElement("div");
  appearanceRow.className = "segmented";
  const appearanceLabels = { dark: "Dark", light: "Light", system: "System" };
  const appearanceButtons = {};
  for (const value of theme.appearances) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = appearanceLabels[value] || value;
    button.addEventListener("click", () => theme.setAppearance(value));
    appearanceButtons[value] = button;
    appearanceRow.append(button);
  }
  appearanceSection.body.append(appearanceRow);

  const themeLabel = document.createElement("div");
  themeLabel.className = "section-label";
  themeLabel.textContent = "Theme";
  const themeRow = document.createElement("div");
  themeRow.className = "segmented theme-row";
  const themeButtons = {};
  const themeLabels = { midnight: "Midnight", indigo: "Indigo", amber: "Amber", forest: "Forest" };
  for (const value of theme.themes) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "theme-button";
    const swatch = document.createElement("span");
    swatch.className = "theme-swatch";
    swatch.dataset.theme = value;
    const label = document.createElement("span");
    label.textContent = themeLabels[value] || value;
    button.append(swatch, label);
    button.addEventListener("click", () => theme.setTheme(value));
    themeButtons[value] = button;
    themeRow.append(button);
  }
  appearanceSection.body.append(themeLabel, themeRow);
  appearanceSection.body.append(
    note(
      "This is the browser's own copy of the phone's appearance and palettes, kept in this " +
        "browser. The phone's own Appearance setting is separate; following it here would need " +
        "a new setting plumbed through the relay, which is not built.",
    ),
  );

  function reflectTheme() {
    for (const [value, button] of Object.entries(appearanceButtons)) {
      button.classList.toggle("on", theme.appearance === value);
    }
    for (const [value, button] of Object.entries(themeButtons)) {
      button.classList.toggle("on", theme.theme === value);
    }
  }
  theme.subscribe(reflectTheme);

  // ---- the web server, which lives on the phone ----
  const serverSection = section("Web access server", "Started and stopped on the phone");
  serverSection.body.append(
    note(
      "The server runs on the phone, not here. Start and stop it in the app's Settings, and " +
        "compare the certificate fingerprint the app shows against the warning this browser " +
        "gave when it first connected.",
    ),
  );
  root.append(serverSection.element);

  // ---- phone-only settings, named rather than offered ----
  const phoneSection = section("Only on the phone", "Settings that do not apply to a browser session");
  phoneSection.body.append(
    note(
      "Pairing and the bridge address and token belong to the phone, which is the paired device; " +
        "a browser session never holds the token. App updates install the Android APK. " +
        "Troubleshooting and clearing the saved data act on the phone's storage. Search, slash " +
        "commands, dictation and car mode are phone features and are not in the browser.",
    ),
  );
  root.append(phoneSection.element);

  // ---- helpers ----
  function section(titleText, subtitleText) {
    const element = document.createElement("section");
    element.className = "settings-section";
    const heading = document.createElement("h2");
    heading.textContent = titleText;
    const sub = document.createElement("p");
    sub.className = "settings-sub";
    sub.textContent = subtitleText;
    const body = document.createElement("div");
    body.className = "settings-body";
    element.append(heading, sub, body);
    return { element, body };
  }

  function field(label, value) {
    const row = document.createElement("div");
    row.className = "settings-field";
    const name = document.createElement("span");
    name.className = "settings-field-name";
    name.textContent = label;
    const val = document.createElement("span");
    val.className = "settings-field-value";
    val.textContent = value;
    row.append(name, val);
    return row;
  }

  function note(text) {
    const p = document.createElement("p");
    p.className = "settings-note";
    p.textContent = text;
    return p;
  }

  function line(text, kind) {
    const p = document.createElement("p");
    p.className = "settings-note" + (kind ? " " + kind : "");
    p.textContent = text;
    return p;
  }

  async function load() {
    let response;
    try {
      response = await relay.get("/api/state");
    } catch (error) {
      state.error = "Could not reach the phone.";
      render();
      return;
    }
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      state.error = "The phone is up but the bridge did not answer.";
      render();
      return;
    }
    const body = await readJson(response);
    state.error = null;
    state.sessionId = body.sessionId || null;
    state.sessionName = body.sessionName || null;
    state.model = body.model && body.model.id ? body.model.id : null;
    state.idle = typeof body.idle === "boolean" ? body.idle : null;
    render();
  }

  function start() {
    render();
    load();
  }

  function stop() {
    // Nothing to tear down.
  }

  return { element: root, start, stop };
}

"use strict";

import { Relay, readJson } from "./lib/relay.js";
import { createEventBus } from "./lib/events.js";
import { createTheme } from "./lib/theme.js";
import { createChatScreen } from "./screens/chat.js";
import { createFilesScreen } from "./screens/files.js";
import { createPinsScreen } from "./screens/pins.js";
import { createSettingsScreen } from "./screens/settings.js";

// The shell: the session loop from design section 5.3, the one event stream the
// screens share, and the nav that mounts one screen at a time. It owns the Relay
// (and so the CSRF secret), the approval flow and the appearance choice, and
// hands each screen what it needs. The screens know nothing about approval,
// which is what lets them be added beside the chat (section 13).

const relay = new Relay();
const events = createEventBus();
const theme = createTheme();
const statusEl = document.getElementById("status");
const screenEl = document.getElementById("screen");
const toolbarEl = document.getElementById("toolbar");
const navEl = document.getElementById("nav");
const disconnectButton = document.getElementById("disconnect");

const SCREENS = [
  { name: "chat", label: "Chat" },
  { name: "files", label: "Files" },
  { name: "pins", label: "Pins" },
  { name: "settings", label: "Settings" },
];

let current = null;

function setStatus(text, bad) {
  statusEl.textContent = text;
  statusEl.classList.toggle("bad", Boolean(bad));
}

function showConnected(show) {
  toolbarEl.hidden = !show;
  navEl.hidden = !show;
}

// Builds the screen a nav button names. The chat and files screens take the
// shared event stream; pins and settings are request-only.
function buildScreen(name) {
  if (name === "files") {
    return createFilesScreen({ relay, onUnauthorized: sessionLost, events });
  }
  if (name === "pins") {
    return createPinsScreen({ relay, onUnauthorized: sessionLost, onOpenChat: () => mount("chat") });
  }
  if (name === "settings") {
    return createSettingsScreen({ relay, onUnauthorized: sessionLost, theme });
  }
  return createChatScreen({ relay, onUnauthorized: sessionLost, events });
}

function reflectNav() {
  for (const button of navEl.querySelectorAll("button")) {
    button.classList.toggle("on", button.dataset.screen === (current && current.name));
  }
}

function unmount() {
  if (current) {
    current.stop();
    current.element.remove();
    current = null;
  }
}

function mount(name) {
  unmount();
  const screen = buildScreen(name);
  screenEl.append(screen.element);
  screen.start();
  current = { name, element: screen.element, stop: screen.stop };
  reflectNav();
}

function buildNav() {
  for (const item of SCREENS) {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.screen = item.name;
    button.textContent = item.label;
    button.addEventListener("click", () => mount(item.name));
    navEl.append(button);
  }
}

// A screen's request came back 401, so the session is gone. Drop it and ask the
// phone to approve again rather than showing an error the human cannot act on.
function sessionLost() {
  // A screen can report the lost session more than once as its requests settle;
  // only the first one starts a fresh approval.
  if (!relay.csrf) {
    return;
  }
  relay.csrf = null;
  events.stop();
  unmount();
  showConnected(false);
  requestApproval();
}

// Ask to be let in. The relay holds this fetch open until the phone answers, so
// the session cookie is written to this exact connection and not to a poll.
async function requestApproval() {
  setStatus("Waiting for approval, check the phone…");
  let response;
  try {
    response = await fetch("/api/approve/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
  } catch (error) {
    setStatus("Could not reach the phone.", true);
    return;
  }
  const body = await readJson(response);
  if (response.ok) {
    relay.csrf = body.csrf;
    setStatus("Approved. Connected.");
    showConnected(true);
    events.start();
    mount("chat");
    return;
  }
  if (response.status === 403) {
    setStatus("The request was denied on the phone.", true);
  } else if (response.status === 408) {
    setStatus("The approval timed out. Reload to try again.", true);
  } else if (response.status === 429) {
    setStatus("Another approval is waiting, or too many requests. Try again shortly.", true);
  } else {
    setStatus("Approval failed: " + (body.error || response.status), true);
  }
}

async function disconnect() {
  if (!relay.csrf) {
    return;
  }
  await relay.post("/api/logout", {});
  relay.csrf = null;
  events.stop();
  unmount();
  showConnected(false);
  requestApproval();
}

async function start() {
  buildNav();
  showConnected(false);
  let response;
  try {
    response = await fetch("/api/session");
  } catch (error) {
    setStatus("Could not reach the phone.", true);
    return;
  }
  if (response.status === 401) {
    requestApproval();
    return;
  }
  const body = await readJson(response);
  if (response.ok && body.csrf) {
    relay.csrf = body.csrf;
    setStatus("Connected.");
    showConnected(true);
    events.start();
    mount("chat");
    return;
  }
  requestApproval();
}

disconnectButton.addEventListener("click", disconnect);
start();

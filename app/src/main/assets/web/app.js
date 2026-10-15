"use strict";

import { Relay, readJson } from "./lib/relay.js";
import { createChatScreen } from "./screens/chat.js";

// The shell: the session loop from design section 5.3 and the mount point for
// the screens. It owns the Relay (and so the CSRF secret) and the approval flow,
// and hands the chat screen into the page once a session exists. The screens
// themselves know nothing about approval, which is what lets the later ones from
// section 13 be dropped in beside the chat.

const relay = new Relay();
const statusEl = document.getElementById("status");
const screenEl = document.getElementById("screen");
const toolbarEl = document.getElementById("toolbar");
const disconnectButton = document.getElementById("disconnect");

let chat = null;

function setStatus(text, bad) {
  statusEl.textContent = text;
  statusEl.classList.toggle("bad", Boolean(bad));
}

function showConnected(show) {
  toolbarEl.hidden = !show;
}

function unmountChat() {
  if (chat) {
    chat.stop();
    chat.element.remove();
    chat = null;
  }
}

function mountChat() {
  unmountChat();
  chat = createChatScreen({ relay, onUnauthorized: sessionLost });
  screenEl.append(chat.element);
  chat.start();
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
  unmountChat();
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
    mountChat();
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
  unmountChat();
  showConnected(false);
  requestApproval();
}

async function start() {
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
    mountChat();
    return;
  }
  requestApproval();
}

disconnectButton.addEventListener("click", disconnect);
start();

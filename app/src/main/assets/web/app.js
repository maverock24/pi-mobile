"use strict";

// The session loop from design section 5.3. The CSRF secret is held in this
// variable only, never in localStorage or sessionStorage, so a reload asks the
// relay for a fresh one and a scripting bug cannot read a stored copy.
let csrf = null;

const statusEl = document.getElementById("status");
const connectedEl = document.getElementById("connected");
const stateEl = document.getElementById("state");
const refreshButton = document.getElementById("refresh");
const disconnectButton = document.getElementById("disconnect");

function setStatus(text, bad) {
  statusEl.textContent = text;
  statusEl.classList.toggle("bad", Boolean(bad));
}

function showConnected(show) {
  connectedEl.hidden = !show;
}

async function readJson(response) {
  try {
    return await response.json();
  } catch (error) {
    return {};
  }
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
    csrf = body.csrf;
    setStatus("Approved. Connected.");
    showConnected(true);
    loadState();
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

async function loadState() {
  if (!csrf) {
    return;
  }
  setStatus("Connected.");
  let response;
  try {
    response = await fetch("/api/state");
  } catch (error) {
    setStatus("Could not reach the phone.", true);
    return;
  }
  if (response.status === 401) {
    csrf = null;
    showConnected(false);
    requestApproval();
    return;
  }
  const body = await readJson(response);
  if (!response.ok) {
    setStatus("The phone is up but the bridge did not answer.", true);
    stateEl.textContent = JSON.stringify(body, null, 2);
    return;
  }
  stateEl.textContent = JSON.stringify(body, null, 2);
}

async function disconnect() {
  if (!csrf) {
    return;
  }
  await fetch("/api/logout", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
    body: "{}",
  });
  csrf = null;
  stateEl.textContent = "";
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
    csrf = body.csrf;
    showConnected(true);
    loadState();
    return;
  }
  requestApproval();
}

refreshButton.addEventListener("click", loadState);
disconnectButton.addEventListener("click", disconnect);
start();

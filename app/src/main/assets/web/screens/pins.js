"use strict";

import { readJson } from "../lib/relay.js";

// The Pins screen, the same list and the same one-pin detail the phone shows.
// Pins are the phone's own data, kept in PinsStore, so the relay serves them
// out of that store rather than proxying the bridge; renaming or deleting here
// changes the phone's pins. Sending a pinned prompt reuses the prompt route the
// chat already uses, then hands the browser to the chat so the answer is
// visible, which is the browser's version of the phone's "Prompt sent".
export function createPinsScreen({ relay, onUnauthorized, onOpenChat }) {
  const state = { pins: [], loading: false, error: null, openId: null };

  const root = document.createElement("div");
  root.className = "pins";

  const head = document.createElement("div");
  head.className = "screen-head";
  const title = document.createElement("span");
  title.className = "screen-title";
  title.textContent = "Pins";
  const status = document.createElement("span");
  status.className = "screen-status";
  const refresh = document.createElement("button");
  refresh.type = "button";
  refresh.className = "quiet";
  refresh.textContent = "Refresh";
  head.append(title, status, refresh);

  const notice = document.createElement("p");
  notice.className = "notice bad";
  notice.hidden = true;
  notice.setAttribute("role", "status");

  const body = document.createElement("div");
  body.className = "pins-body";

  root.append(head, notice, body);

  let confirmingDelete = false;

  function render() {
    status.textContent = state.loading ? "loading…" : state.pins.length + (state.pins.length === 1 ? " pin" : " pins");
    notice.hidden = !state.error;
    notice.textContent = state.error || "";
    body.textContent = "";
    const open = state.pins.find((pin) => pin.id === state.openId);
    if (open) {
      body.append(pinDetail(open));
    } else if (state.pins.length === 0) {
      const empty = document.createElement("p");
      empty.className = "empty";
      empty.textContent = state.loading
        ? "Loading pins…"
        : "No pins yet. Pin an answer on the phone to keep its prompt and its reply here.";
      body.append(empty);
    } else {
      for (const pin of state.pins) {
        body.append(pinRow(pin));
      }
    }
  }

  // One pin in the list: its title, then the prompt, or the answer when the
  // prompt is empty, the same snippet the phone shows.
  function pinRow(pin) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "pin-row";
    const pinTitle = document.createElement("span");
    pinTitle.className = "pin-title";
    pinTitle.textContent = pin.title || "Pin";
    const snippet = document.createElement("span");
    snippet.className = "pin-snippet";
    snippet.textContent = pin.prompt || pin.answer || "";
    row.append(pinTitle, snippet);
    row.addEventListener("click", () => {
      state.openId = pin.id;
      confirmingDelete = false;
      render();
    });
    return row;
  }

  function pinDetail(pin) {
    const wrap = document.createElement("div");
    wrap.className = "pin-detail";

    const back = document.createElement("button");
    back.type = "button";
    back.className = "quiet";
    back.textContent = "Back to pins";
    back.addEventListener("click", () => {
      state.openId = null;
      confirmingDelete = false;
      render();
    });
    wrap.append(back);

    const heading = document.createElement("div");
    heading.className = "pin-detail-title";
    heading.textContent = pin.title || "Pin";
    const edit = document.createElement("button");
    edit.type = "button";
    edit.className = "quiet";
    edit.textContent = "Edit title";
    edit.addEventListener("click", () => beginRename(pin, heading, edit));
    const headingRow = document.createElement("div");
    headingRow.className = "pin-detail-head";
    headingRow.append(heading, edit);
    wrap.append(headingRow);

    if (pin.prompt) {
      const prompt = document.createElement("div");
      prompt.className = "pin-prompt";
      prompt.textContent = pin.prompt;
      wrap.append(prompt);
    }

    const answer = document.createElement("div");
    answer.className = "pin-answer";
    answer.textContent = pin.answer;
    wrap.append(answer);

    const actions = document.createElement("div");
    actions.className = "pin-actions";
    const send = document.createElement("button");
    send.type = "button";
    send.textContent = "Send prompt";
    send.disabled = !pin.prompt;
    send.addEventListener("click", () => sendPrompt(pin));
    actions.append(send);
    wrap.append(actions);

    if (confirmingDelete) {
      const ask = document.createElement("p");
      ask.className = "pin-delete-ask";
      ask.textContent = "Delete this pin? This cannot be undone.";
      const confirmRow = document.createElement("div");
      confirmRow.className = "pin-actions";
      const confirm = document.createElement("button");
      confirm.type = "button";
      confirm.textContent = "Delete";
      confirm.addEventListener("click", () => remove(pin));
      const cancel = document.createElement("button");
      cancel.type = "button";
      cancel.className = "quiet";
      cancel.textContent = "Cancel";
      cancel.addEventListener("click", () => {
        confirmingDelete = false;
        render();
      });
      confirmRow.append(confirm, cancel);
      wrap.append(ask, confirmRow);
    } else {
      const del = document.createElement("button");
      del.type = "button";
      del.className = "quiet";
      del.textContent = "Delete pin";
      del.addEventListener("click", () => {
        confirmingDelete = true;
        render();
      });
      wrap.append(del);
    }
    return wrap;
  }

  // Editing a title in place, with a Save and a Cancel, matching the phone's
  // detail view. The Save is disabled on a blank title for the same reason the
  // phone disables it.
  function beginRename(pin, heading, edit) {
    const input = document.createElement("input");
    input.type = "text";
    input.className = "pin-title-edit";
    input.value = pin.title || "";
    const save = document.createElement("button");
    save.type = "button";
    save.textContent = "Save";
    save.disabled = !input.value.trim();
    input.addEventListener("input", () => {
      save.disabled = !input.value.trim();
    });
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "quiet";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", render);
    heading.replaceWith(input);
    edit.replaceWith(save);
    save.after(cancel);
    input.focus();
    save.addEventListener("click", () => rename(pin, input.value));
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && input.value.trim()) rename(pin, input.value);
    });
  }

  async function load() {
    state.loading = true;
    render();
    let response;
    try {
      response = await relay.get("/api/pins");
    } catch (error) {
      state.loading = false;
      state.error = "Could not reach the phone.";
      render();
      return;
    }
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    state.loading = false;
    if (!response.ok) {
      state.error = "The pins could not be read.";
      render();
      return;
    }
    const body = await readJson(response);
    state.error = null;
    state.pins = Array.isArray(body.pins) ? body.pins : [];
    if (state.openId && !state.pins.some((pin) => pin.id === state.openId)) state.openId = null;
    render();
  }

  async function rename(pin, title) {
    const trimmed = title.trim();
    if (!trimmed) return;
    const response = await relay.post("/api/pins/rename", { id: pin.id, title: trimmed });
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      state.error = "The title was not saved.";
      render();
      return;
    }
    state.error = null;
    await load();
  }

  async function remove(pin) {
    const response = await relay.post("/api/pins/delete", { id: pin.id });
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      state.error = "The pin was not deleted.";
      render();
      return;
    }
    confirmingDelete = false;
    state.openId = null;
    state.error = null;
    await load();
  }

  async function sendPrompt(pin) {
    const response = await relay.post("/api/prompt", { text: pin.prompt });
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      state.error = "The bridge did not accept the prompt.";
      render();
      return;
    }
    state.error = null;
    if (onOpenChat) onOpenChat();
  }

  function start() {
    render();
    refresh.addEventListener("click", load);
    load();
  }

  function stop() {
    // Nothing to tear down: pins do not need the stream.
  }

  return { element: root, start, stop };
}

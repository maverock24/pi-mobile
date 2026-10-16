"use strict";

import { readJson } from "../lib/relay.js";

// The Files library, the same list the phone draws from the bridge's own
// library route. A row carries what the phone carries: the kind, the render
// state of a talk, its size, its duration (marked as an estimate when the
// bridge estimated it from a talk's word count) and, for a failed render, the
// error. A rendering file is not silent and a failed one is not empty: the
// three cases read differently, which is the bug the app already had and this
// must not repeat.
//
// An audio row plays through the phone's relay, the same source the phone's own
// player uses. Pressing Play holds one <audio> element in that row, so the
// scrubber lives in the talk's own row exactly as it does on the phone, and it
// is taken out when playback stops. The design refuses any non-audio bytes on
// the session origin, so a video or a text file still states that it cannot be
// shown here rather than offering a control that does nothing.
export function createFilesScreen({ relay, onUnauthorized, events }) {
  const state = { rows: [], loading: false, error: null, playingKey: null, audioEl: null };

  let unsubscribe = null;
  let refreshTimer = null;

  const root = document.createElement("div");
  root.className = "files";

  const head = document.createElement("div");
  head.className = "screen-head";
  const title = document.createElement("span");
  title.className = "screen-title";
  title.textContent = "Files";
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

  const list = document.createElement("div");
  list.className = "file-list";

  root.append(head, notice, list);

  // The row key a talk is played and tracked by. The id is stable across a
  // refresh and the name is what the download path uses, so either identifies
  // the same row when the library is re-fetched while it is playing.
  function keyOf(row) {
    return row.id || row.name || "";
  }

  // Takes the player out of the row without touching the list. Used both when
  // Stop is pressed and when the talk it was playing has left the library.
  function releaseAudio() {
    state.playingKey = null;
    const el = state.audioEl;
    state.audioEl = null;
    if (el) {
      try {
        el.pause();
      } catch (error) {
        // A browser that will not pause a broken element is not a failure here.
      }
      el.removeAttribute("src");
      el.load();
    }
  }

  function stopPlayback() {
    releaseAudio();
    render();
  }

  // Play one row. Only one row plays at a time, so any previous element is
  // released first, and the row is re-rendered with the player in it.
  function playRow(row) {
    const key = keyOf(row);
    if (state.playingKey === key) return;
    releaseAudio();
    const el = document.createElement("audio");
    el.controls = true;
    el.preload = "metadata";
    el.className = "file-audio";
    el.addEventListener("ended", stopPlayback);
    // The session cookie rides along on this same-origin request with no code
    // naming it, so the audio authenticates exactly like the page. The file name
    // is in the path and there is no token in the URL, not in the query and not
    // in a fragment, which is the one place a media route usually leaks one.
    el.src = "/api/files/" + encodeURIComponent(row.name || row.id);
    state.audioEl = el;
    state.playingKey = key;
    render();
    // A browser that blocks the autoplay still shows the native controls, so the
    // human can press play; that is not an error worth naming.
    const started = el.play();
    if (started && typeof started.catch === "function") started.catch(() => {});
  }

  function render() {
    // If the talk that was playing is no longer in the library, take the player
    // out rather than leaving an element with no row to live in.
    if (state.playingKey && !state.rows.some((row) => keyOf(row) === state.playingKey)) {
      releaseAudio();
    }
    status.textContent = state.loading ? "loading…" : state.rows.length + (state.rows.length === 1 ? " file" : " files");
    // An error over a list that still has rows belongs above it; when the list
    // is empty the empty state below names the error instead, so the failure is
    // stated once and never reads as the plain "No files yet" case.
    notice.hidden = !(state.error && state.rows.length > 0);
    notice.textContent = state.error && state.rows.length > 0 ? state.error : "";
    list.textContent = "";
    if (state.rows.length === 0) {
      const empty = document.createElement("p");
      empty.className = "empty";
      if (state.loading) {
        empty.textContent = "Loading files…";
      } else if (state.error) {
        empty.classList.add("bad");
        empty.textContent = state.error;
      } else {
        empty.textContent = "No files yet";
      }
      list.append(empty);
      return;
    }
    for (const row of state.rows) {
      list.append(fileRow(row));
    }
  }

  function fileRow(row) {
    const card = document.createElement("div");
    card.className = "file-card";

    const name = document.createElement("div");
    name.className = "file-title";
    name.textContent = row.title || row.name || "File";
    card.append(name);

    const details = [
      kindLabel(row.kind),
      row.state && row.state !== "ready" ? row.state : null,
      durationLabel(row),
      sizeLabel(row.bytes),
      row.voice || null,
    ].filter(Boolean);
    const meta = document.createElement("div");
    meta.className = "file-meta";
    meta.textContent = details.join(" · ");
    card.append(meta);

    if (row.state === "failed" && row.error) {
      const error = document.createElement("div");
      error.className = "file-error";
      error.textContent = row.error;
      card.append(error);
    } else if (row.state === "rendering") {
      // The bridge reports the state but not how far the render has got, so the
      // bar is indeterminate and there is no control a half-rendered talk could
      // be tapped into.
      const progress = document.createElement("div");
      progress.className = "file-progress";
      card.append(progress);
    } else if (row.kind === "audio") {
      const actions = document.createElement("div");
      actions.className = "file-actions";
      if (state.playingKey === keyOf(row)) {
        // The player lives in the row while it plays and carries the scrubber,
        // which is the phone's own layout: the row is the whole control.
        if (state.audioEl) actions.append(state.audioEl);
        const stop = document.createElement("button");
        stop.type = "button";
        stop.className = "quiet";
        stop.textContent = "Stop";
        stop.addEventListener("click", stopPlayback);
        actions.append(stop);
      } else {
        const play = document.createElement("button");
        play.type = "button";
        play.textContent = "Play";
        play.addEventListener("click", () => playRow(row));
        actions.append(play);
      }
      card.append(actions);
    } else if (row.kind === "video") {
      // The relay serves audio only, so a video byte would be a non-audio
      // document on the session origin. It is named as unavailable rather than
      // given a Play that cannot work.
      const note = document.createElement("div");
      note.className = "file-note";
      note.textContent = "Only audio plays in the browser. Open this on the phone.";
      card.append(note);
    } else {
      // Text, image and anything else: the design refuses non-audio bytes on
      // the session origin, so there is nothing here to open.
      const note = document.createElement("div");
      note.className = "file-note";
      note.textContent = "This file cannot be shown in the browser.";
      card.append(note);
    }
    return card;
  }

  function kindLabel(kind) {
    if (kind === "audio" || kind === "video" || kind === "image" || kind === "text") return kind;
    return "file";
  }

  function durationLabel(row) {
    const seconds = Number(row.durationSeconds) || 0;
    if (seconds <= 0) return null;
    if (row.durationEstimated) {
      const minutes = Math.max(1, Math.round(seconds / 60));
      return "about " + minutes + " min";
    }
    return formatMillis(seconds * 1000);
  }

  function formatMillis(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return minutes + ":" + String(seconds).padStart(2, "0");
  }

  function sizeLabel(bytes) {
    const value = Number(bytes) || 0;
    if (value <= 0) return null;
    if (value < 1024 * 1024) return Math.round(value / 1024) + " kB";
    return (value / (1024 * 1024)).toFixed(1) + " MB";
  }

  async function load() {
    state.loading = true;
    render();
    let response;
    try {
      response = await relay.get("/api/files");
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
    let body = {};
    try {
      body = await readJson(response);
    } catch (error) {
      body = {};
    }
    state.loading = false;
    if (!response.ok) {
      // A bridge that is down is a failed hop, not an empty library, and it is
      // named as its own thing.
      state.error = body.error ? "The phone is up but the bridge did not answer: " + body.error : "The phone is up but the bridge did not answer.";
      render();
      return;
    }
    state.error = null;
    const rows = Array.isArray(body.files) ? body.files : [];
    state.rows = rows.map((row) => ({ ...row }));
    render();
  }

  // Upsert or remove one talk row in place, so a render advancing does not wait
  // for the next poll and the stream stays the freshest source.
  function applyTalk(frame) {
    const talk = frame.data || {};
    const id = talk.id;
    if (!id) return;
    const index = state.rows.findIndex((row) => row.id === id || row.name === talk.name);
    if (index >= 0) {
      state.rows[index] = { ...state.rows[index], ...talk };
    } else {
      state.rows.unshift({ ...talk });
    }
    render();
  }

  function removeTalk(frame) {
    const id = (frame.data || {}).id;
    if (!id) return;
    const before = state.rows.length;
    state.rows = state.rows.filter((row) => row.id !== id);
    if (state.rows.length !== before) render();
  }

  function start() {
    render();
    refresh.addEventListener("click", load);
    unsubscribe = events.subscribe({
      onopen: load,
      onmessage: (frame) => {
        if (frame.type === "talk") applyTalk(frame);
        else if (frame.type === "talk_removed") removeTalk(frame);
      },
    });
    // A slow poll beside the stream, the same safety net the chat screen keeps,
    // so a talk event missed while the stream was down still lands.
    refreshTimer = window.setInterval(load, 10000);
  }

  function stop() {
    releaseAudio();
    if (unsubscribe) {
      unsubscribe();
      unsubscribe = null;
    }
    if (refreshTimer) {
      window.clearInterval(refreshTimer);
      refreshTimer = null;
    }
  }

  return { element: root, start, stop };
}

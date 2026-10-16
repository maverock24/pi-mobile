"use strict";

import { readJson } from "../lib/relay.js";

// The chat screen: the core loop from design section 11 (send a prompt, stream
// the answer, answer a questionnaire). It keeps the same state the phone's
// ChatViewModel keeps, a transcript, the answer still streaming, the pending
// question and whether a run is busy, so the browser reads the session the way
// the phone does rather than inventing a second model that can drift.
//
// It is built as one screen with a small, explicit interface (element, start,
// stop) so the later screens from section 13 can be added the same way instead
// of this being a one-off console.
export function createChatScreen({ relay, onUnauthorized, events }) {
  const state = {
    messages: [],
    liveAnswer: null,
    pending: null,
    runCandidates: [],
    activeAnswer: null,
    activeHasToolCalls: false,
    busy: false,
    sessionId: null,
    sessionTitle: "pi session",
    connected: false,
    notice: null,
  };

  let unsubscribe = null;
  let poll = null;
  let streamedBody = null;

  // ---- DOM, built once ------------------------------------------------

  const root = document.createElement("div");
  root.className = "chat";

  const head = document.createElement("div");
  head.className = "chat-head";
  const dot = document.createElement("span");
  dot.className = "conn-dot";
  const title = document.createElement("span");
  title.className = "chat-title";
  const busy = document.createElement("span");
  busy.className = "chat-busy";
  head.append(dot, title, busy);

  const notice = document.createElement("p");
  notice.className = "notice bad";
  notice.hidden = true;
  notice.setAttribute("role", "status");

  const transcript = document.createElement("div");
  transcript.className = "transcript";
  transcript.setAttribute("aria-live", "polite");

  const questionBox = document.createElement("div");
  questionBox.className = "question-box";

  const composer = document.createElement("div");
  composer.className = "composer";
  const input = document.createElement("textarea");
  input.rows = 2;
  input.placeholder = "Ask pi. Enter sends, Shift+Enter adds a line.";
  input.setAttribute("aria-label", "Prompt");
  const send = document.createElement("button");
  send.type = "button";
  send.textContent = "Send";
  composer.append(input, send);

  root.append(head, notice, transcript, questionBox, composer);

  // ---- rendering ------------------------------------------------------

  function renderHead() {
    dot.classList.toggle("on", state.connected);
    dot.classList.toggle("off", !state.connected);
    title.textContent = state.sessionTitle + (state.sessionId ? " · " + state.sessionId.slice(0, 8) : "");
    busy.textContent = state.busy ? "working…" : "";
    notice.hidden = !state.notice;
    notice.textContent = state.notice || "";
  }

  function setNotice(text) {
    state.notice = text;
    renderHead();
  }

  function renderTranscript() {
    // A full rebuild keeps the message order simple; the text of a streaming
    // answer is appended in place by appendDelta so a token does not rebuild it.
    transcript.textContent = "";
    streamedBody = null;
    for (const message of state.messages) {
      transcript.append(messageNode(message.role, message.text, message.failed));
    }
    if (state.liveAnswer != null) {
      const node = messageNode("assistant", state.liveAnswer, false);
      node.classList.add("streaming");
      streamedBody = node.querySelector(".msg-body");
      transcript.append(node);
    }
    scrollToEnd();
  }

  function messageNode(role, text, failed) {
    const wrap = document.createElement("div");
    wrap.className = "msg role-" + role + (failed ? " failed" : "");
    const label = document.createElement("div");
    label.className = "msg-role";
    label.textContent = roleLabel(role);
    const body = document.createElement("div");
    body.className = "msg-body";
    body.textContent = text;
    wrap.append(label, body);
    return wrap;
  }

  function roleLabel(role) {
    if (role === "user") return "Prompt";
    if (role === "assistant") return "Pi";
    if (role === "question") return "Question";
    return role;
  }

  function appendDelta(delta) {
    state.liveAnswer = (state.liveAnswer || "") + delta;
    if (streamedBody) {
      streamedBody.textContent += delta;
      scrollToEnd();
    } else {
      renderTranscript();
    }
  }

  function scrollToEnd() {
    transcript.scrollTop = transcript.scrollHeight;
  }

  function renderQuestion() {
    questionBox.textContent = "";
    const pending = state.pending;
    if (!pending) {
      return;
    }
    const card = document.createElement("div");
    card.className = "question-card";

    const status = document.createElement("div");
    status.className = "question-status";
    const several = pending.questions.length > 1;
    const answered = pending.questions.filter((question) => question.answer != null).length;
    status.textContent = several
      ? answered + " of " + pending.questions.length + " answered"
      : "pi is waiting for an answer";
    card.append(status);

    const heading = document.createElement("div");
    heading.className = "question-title";
    heading.textContent = pending.title;
    card.append(heading);

    const firstUnanswered = pending.questions.find((question) => question.answer == null);
    for (const question of pending.questions) {
      if (several) {
        const line = document.createElement("div");
        line.className = "question-prompt";
        line.textContent = question.label + ": " + question.prompt;
        card.append(line);
      }
      if (question.answer != null) {
        const done = document.createElement("div");
        done.className = "question-answer";
        done.textContent = "✓ " + question.answer;
        card.append(done);
        continue;
      }
      const options = document.createElement("div");
      options.className = "question-options";
      question.options.forEach((option, index) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "option";
        const name = document.createElement("span");
        name.className = "option-name";
        name.textContent = index + 1 + ". " + option.label;
        button.append(name);
        if (option.description) {
          const description = document.createElement("span");
          description.className = "option-desc";
          description.textContent = option.description;
          button.append(description);
        }
        button.addEventListener("click", () => answerQuestion(question.id, option.value, false, false));
        options.append(button);
      });
      card.append(options);

      // The free-text field belongs to the first question still open, the same
      // as the phone's widget.
      if (firstUnanswered && question.id === firstUnanswered.id) {
        const row = document.createElement("div");
        row.className = "question-typed";
        const field = document.createElement("input");
        field.type = "text";
        field.placeholder = "Or type your own answer";
        const submit = document.createElement("button");
        submit.type = "button";
        submit.textContent = "Send";
        const sendTyped = () => {
          const value = field.value.trim();
          if (value) answerQuestion(question.id, value, true, false);
        };
        submit.addEventListener("click", sendTyped);
        field.addEventListener("keydown", (event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            sendTyped();
          }
        });
        row.append(field, submit);
        card.append(row);
      }
    }

    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "quiet";
    cancel.textContent = "Cancel question";
    const target = firstUnanswered || pending.questions[0];
    cancel.addEventListener("click", () => answerQuestion(target.id, "", false, true));
    card.append(cancel);

    questionBox.append(card);
  }

  // ---- the loop -------------------------------------------------------

  async function sendPrompt() {
    const text = input.value.trim();
    if (!text) {
      return;
    }
    input.value = "";
    const entry = { role: "user", text: text };
    state.messages.push(entry);
    renderTranscript();
    setNotice(null);
    const response = await relay.post("/api/prompt", { text: text, sessionId: state.sessionId });
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      entry.failed = true;
      renderTranscript();
      setNotice("The bridge did not accept the prompt.");
    }
  }

  async function answerQuestion(questionId, value, custom, cancel) {
    const pending = state.pending;
    if (!pending) {
      return;
    }
    const question = pending.questions.find((item) => item.id === questionId) || pending.questions[0];
    const label = custom || cancel ? value : optionLabel(question, value);
    // The tool result that records this choice arrives with the next history
    // fetch, so the choice is put on the transcript now, as the phone does, and
    // removed if the answer did not reach the bridge.
    const trace = {
      role: "question",
      text: cancel ? question.prompt + " (cancelled)" : question.prompt + " → " + label,
    };
    state.messages.push(trace);
    renderTranscript();
    setNotice(null);
    const response = await relay.post("/api/answer", {
      questionId: questionId,
      value: value,
      custom: custom,
      cancel: cancel,
      sessionId: state.sessionId,
    });
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      state.messages = state.messages.filter((item) => item !== trace);
      renderTranscript();
      setNotice("The bridge did not accept the answer.");
    }
  }

  function optionLabel(question, value) {
    const option = question.options.find((item) => item.value === value);
    return option ? option.label : value;
  }

  async function refreshState() {
    const response = await relay.get("/api/state");
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      setNotice("The phone is up but the bridge did not answer.");
      return;
    }
    const body = await readJson(response);
    if (body.sessionId) {
      state.sessionId = body.sessionId;
    }
    state.sessionTitle = body.sessionName || state.sessionTitle;
    state.busy = body.idle === false;
    const pending = Boolean(body.question && body.question.pending);
    if (pending && !state.pending) {
      await loadQuestion();
    } else if (!pending && state.pending) {
      applyQuestion(null);
    }
    renderHead();
  }

  async function loadQuestion() {
    const response = await relay.get("/api/question");
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      return;
    }
    const body = await readJson(response);
    applyQuestion(body.pending);
  }

  async function reloadHistory() {
    const response = await relay.get("/api/history?limit=200");
    if (response.status === 401) {
      onUnauthorized();
      return;
    }
    if (!response.ok) {
      setNotice("The phone is up but the bridge did not answer.");
      return;
    }
    const body = await readJson(response);
    const messages = Array.isArray(body.messages) ? body.messages : [];
    state.messages = messages.map((message) => ({ role: message.role, text: message.text }));
    renderTranscript();
  }

  function handleEvent(frame) {
    const data = frame.data || {};
    switch (frame.type) {
      case "question":
        applyQuestion(data.pending);
        break;
      case "message_start":
        onMessageStart(data);
        break;
      case "message_update":
        onMessageUpdate(data);
        break;
      case "message_end":
        onMessageEnd(data);
        break;
      case "prompt_accepted":
      case "agent_start":
        state.busy = true;
        renderHead();
        break;
      case "agent_end":
        refreshState();
        break;
      case "agent_settled":
        state.busy = false;
        commitRun();
        renderHead();
        reloadHistory();
        break;
      default:
        break;
    }
  }

  function onMessageStart(data) {
    const message = data.message || {};
    if (message.role === "assistant") {
      state.activeAnswer = message.text || "";
      state.activeHasToolCalls = Boolean(message.toolCall);
      state.liveAnswer = state.activeAnswer;
      renderTranscript();
    } else if (message.role === "user") {
      // A prompt typed at the laptop arrives here too, and history alone would
      // not show it until the next reconnect. An identical prompt already on
      // screen is this same turn arriving back.
      state.runCandidates = [];
      const prompt = (message.text || "").trim();
      const echoed = lastUserText();
      if (prompt && prompt !== echoed) {
        state.messages.push({ role: "user", text: prompt });
        renderTranscript();
      }
    }
  }

  function onMessageUpdate(data) {
    const update = data.assistantMessageEvent;
    if (update && update.type === "text_delta" && update.delta) {
      if (state.activeAnswer == null) {
        state.activeAnswer = "";
      }
      state.activeAnswer += update.delta;
      appendDelta(update.delta);
    }
  }

  function onMessageEnd(data) {
    const message = data.message || {};
    if (message.role !== "assistant") {
      return;
    }
    const text = state.activeAnswer != null && state.activeAnswer.length ? state.activeAnswer : message.text || "";
    const hasTool = state.activeHasToolCalls || Boolean(message.toolCall);
    state.runCandidates.push({ text: text, hasTool: hasTool });
    state.activeAnswer = null;
    state.activeHasToolCalls = false;
    state.liveAnswer = text;
    renderTranscript();
  }

  // Commit the finished run's answer, mirroring the phone: the answer is the
  // last message that is not mainly tool calls, and the streamed copy gives way
  // to it so the two are never shown together.
  function commitRun() {
    const candidate = state.runCandidates.slice().reverse().find((item) => !item.hasTool && item.text.trim());
    state.runCandidates = [];
    state.activeAnswer = null;
    state.liveAnswer = null;
    if (!candidate) {
      renderTranscript();
      return;
    }
    const answer = candidate.text.trim();
    if (answer === lastAssistantText()) {
      renderTranscript();
      return;
    }
    state.messages.push({ role: "assistant", text: answer });
    renderTranscript();
  }

  function applyQuestion(pending) {
    const previous = state.pending;
    state.pending = parsePending(pending);
    renderQuestion();
    // The widget closed, so pi has recorded the choice as a tool result by now.
    // Pull the transcript a moment later so it can replace the optimistic trace.
    if (previous && !state.pending) {
      setTimeout(reloadHistory, 500);
    }
  }

  function lastUserText() {
    for (let index = state.messages.length - 1; index >= 0; index -= 1) {
      if (state.messages[index].role === "user") {
        return state.messages[index].text.trim();
      }
    }
    return "";
  }

  function lastAssistantText() {
    for (let index = state.messages.length - 1; index >= 0; index -= 1) {
      if (state.messages[index].role === "assistant") {
        return state.messages[index].text.trim();
      }
    }
    return "";
  }

  // ---- the question payload, the same shape the phone's parser reads ------

  function parsePending(json) {
    if (!json || json === null) {
      return null;
    }
    if (!json.id || !Array.isArray(json.questions)) {
      return null;
    }
    const questions = [];
    for (const raw of json.questions) {
      if (!raw) continue;
      const options = [];
      for (const option of raw.options || []) {
        if (!option) continue;
        const label = option.label || "";
        options.push({
          value: option.value || label,
          label: label,
          description: option.description || null,
        });
      }
      questions.push({
        id: raw.id || "",
        label: raw.label || "Question " + (questions.length + 1),
        prompt: raw.prompt || "",
        options: options,
        answer: raw.answer && raw.answer.label ? raw.answer.label : null,
      });
    }
    if (questions.length === 0) {
      return null;
    }
    return {
      id: json.id,
      tool: json.tool || "question",
      title: json.title || questions[0].prompt,
      multiple: Boolean(json.multiple || questions.length > 1),
      questions: questions,
    };
  }

  // ---- lifecycle ------------------------------------------------------

  function onStreamOpen() {
    state.connected = true;
    renderHead();
    refreshState();
    reloadHistory();
    loadQuestion();
  }

  function start() {
    renderHead();
    renderTranscript();
    renderQuestion();
    send.addEventListener("click", sendPrompt);
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        sendPrompt();
      }
    });
    // The shell owns the one event stream, so a screen switch does not drop it.
    // Subscribing after the stream is already open replays the open signal, so
    // returning to this screen refetches state and history.
    unsubscribe = events.subscribe({
      onopen: onStreamOpen,
      onerror: () => {
        // EventSource reconnects on its own; the poll below is the safety net
        // for a question that arrived while the stream was down (finding UX3).
        state.connected = false;
        renderHead();
      },
      onmessage: handleEvent,
    });
    poll = window.setInterval(() => {
      refreshState();
    }, 5000);
  }

  function stop() {
    if (unsubscribe) {
      unsubscribe();
      unsubscribe = null;
    }
    if (poll) {
      window.clearInterval(poll);
      poll = null;
    }
  }

  return { element: root, start, stop };
}

"use strict";

// The one event stream the shell owns. Every screen that needs live bridge
// events subscribes here rather than opening its own EventSource, so switching
// screens does not drop the stream, and the relay keeps the server alive while
// the browser is open (a live stream counts as in flight). The session cookie
// rides along on the EventSource automatically, which is why the credential is
// a cookie and not a header (design section 5). The relay forwards only the
// frames the screens render, so nothing here has to filter a frame again.
export function createEventBus() {
  const listeners = new Set();
  let source = null;
  let connected = false;

  function setConnected(value) {
    if (connected === value) return;
    connected = value;
    listeners.forEach((listener) => {
      if (value && listener.onopen) listener.onopen();
      else if (!value && listener.onerror) listener.onerror();
    });
  }

  return {
    get connected() {
      return connected;
    },

    start() {
      if (source) return;
      source = new EventSource("/api/events");
      source.onopen = () => setConnected(true);
      source.onerror = () => setConnected(false);
      source.onmessage = (event) => {
        let frame;
        try {
          frame = JSON.parse(event.data);
        } catch (error) {
          // A frame the relay did not write is not for any screen.
          return;
        }
        listeners.forEach((listener) => listener.onmessage(frame));
      };
    },

    subscribe(listener) {
      listeners.add(listener);
      // A screen mounted after the stream is already open still needs its
      // catch-up, so the open signal is replayed to whoever joins late.
      if (connected && listener.onopen) listener.onopen();
      return () => listeners.delete(listener);
    },

    stop() {
      if (source) {
        source.close();
        source = null;
      }
      connected = false;
    },
  };
}

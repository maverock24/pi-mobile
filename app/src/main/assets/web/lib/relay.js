"use strict";

// The relay client the screens talk through. It is the only place the session
// loop's credential is used: the CSRF secret lives in this object's memory and
// nowhere else, never in localStorage or sessionStorage, so a reload asks the
// relay for a fresh one and a scripting bug cannot read a stored copy (design
// section 5.3). The session cookie is HttpOnly and SameSite=Strict, so the
// browser attaches it to these same-origin calls without any code naming it.
export class Relay {
  constructor() {
    this.csrf = null;
  }

  async get(path) {
    return fetch(path, {
      headers: { Accept: "application/json" },
      credentials: "same-origin",
    });
  }

  async post(path, body) {
    return fetch(path, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        // A cross-site request cannot set this header, which is the mutation
        // defence on top of the cookie's SameSite=Strict.
        "X-CSRF-Token": this.csrf || "",
      },
      body: JSON.stringify(body || {}),
    });
  }
}

export async function readJson(response) {
  try {
    return await response.json();
  } catch (error) {
    return {};
  }
}

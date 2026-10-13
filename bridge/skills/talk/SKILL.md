---
name: talk
description: |
  Turn a subject into a spoken talk and render it to an MP3 the phone can play.
  Use when the human asks for a talk, lecture, briefing or audio essay about a
  subject, especially a length in minutes such as "a 30 minute talk about the
  Roman roads". It writes the script, renders it on the laptop through the
  pi-remote bridge, and hands back a download link. No on-device synthesis and
  no confirmation step: the human is usually driving.
---

# Generate a talk

You write the script; the bridge renders it; the phone plays the file. Follow
these steps in order and do not ask the human to confirm anything along the way.
The only reason to ask a question is a subject that is missing or genuinely
ambiguous.

## 1. Write the script

Length is speech, not reading: edge-tts speaks at about **150 words per minute**,
so a talk of N minutes is about N x 150 words.

- 30 minutes -> about **4500 words**
- 10 minutes -> about **1500 words**
- 5 minutes -> about **750 words**

When the human asks for a length ("a 30 minute talk about X"), write to that
word count; when no length is given, write a 10 minute talk. Write the script as
plain prose for the ear: full sentences, no headings, no bullet points, no
markdown, nothing that only makes sense on a screen. Open with a sentence that
introduces the subject and close with one that ends it.

Save it to a file under the agent directory, creating the folder if needed:

```bash
mkdir -p "${PI_AGENT_DIR:-$HOME/.pi/agent}/talks/scripts"
# then write e.g. ${PI_AGENT_DIR:-$HOME/.pi/agent}/talks/scripts/roman-roads.txt
```

Do not paste the whole script into the reply. The file is the artifact.

## 2. Register and render it

Run the helper beside this skill file. Resolve `render-talk.sh` against this
skill's directory (the parent of SKILL.md), which the skill prompt names as the
`<location>` tag:

```bash
bash render-talk.sh "Roman roads" "${PI_AGENT_DIR:-$HOME/.pi/agent}/talks/scripts/roman-roads.txt"
```

A third argument overrides the voice (default `en-US-AriaNeural`). The helper
registers the talk with the bridge, then waits for the background render and
prints a download link. A 30 minute talk takes a few minutes, so give the command
a generous timeout.

## 3. Tell the human

The helper prints, on success, a `play:` line with a URL. Report that link and
the rough length to the human in one short message, for example:

> Roman roads is ready, about 30 minutes: http://link

The link is single-use and expires in about five minutes, so say so if the human
will not tap it immediately; they can ask the bridge for a fresh one. If the
helper exits with an error, repeat exactly what it printed. If the bridge says
`edge-tts is not installed`, tell the human to run `pipx install edge-tts` (or
`pip install --user edge-tts`) on the laptop and then try again.

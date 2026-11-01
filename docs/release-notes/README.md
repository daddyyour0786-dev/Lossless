# Release notes

One file per release, named after the version it describes:

```
docs/release-notes/0.1.8.md
```

If a file exists for the version being released, the release workflow publishes
it verbatim as the body of the GitHub release (a Downloads footer and the
comparison link are appended underneath). If there is no file for that version,
the workflow falls back to a bullet list of the commit subjects since the
previous tag — readable, but it can only say what the commits say.

Write the file in the same commit as the version bump, so the notes and the tag
describe the same tree. Plain Markdown; no front matter, no heading needed for
the version itself, since the release title already carries it.

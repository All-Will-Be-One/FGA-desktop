# Desktop foundation validation (2026-10-16)

- JDK 21, upstream Gradle 9.8.0 wrapper, independent desktop settings.
- `:desktop-cli:installDist :scripts:test`: successful.
- Original scripts suite: 18 tests, 0 failures/errors/skips.
- `python3 -m unittest discover -s desktop/tests -v`: 4 passed.
- CLI exercised the original AutoSkillCommand parser (valid/invalid commands), original game geometry, explicit unavailable battle capability, and PNG capture through a fake ADB executable.
- Original app/prefs/scripts/libautomata/build files: no changes against the upstream base.
- Actual phone gestures, native desktop image matching/OCR and end-to-end battles: not verified / not yet wired.

The passing suite establishes a reusable JVM host foundation, not a completed desktop battle engine.

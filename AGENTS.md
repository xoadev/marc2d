# Instructions for agents

marc2d is an Android app that turns a phone fixed to the wall into the console of the house. It connects
to Home Assistant and the intercoms, and exposes their functions. It is not the control hub: anything that is
house logic belongs in Home Assistant.

## Documents

- `SPEC.md` is the source of truth for scope, delivery order and decisions. If a change contradicts it,
  update it in the same commit.
- `DESIGN.md` is the style of the app and the droid: everything visual follows it.
- `README.md` has the architecture diagram: update it if the pieces or their connections change.

## Work cycle

Spec, change, `./kotlin check` and `./kotlin build -m app`, commit, PR. Nothing is finished until those
commands are green. Everything reaches `main` through a PR, from its own branch.

```sh
./kotlin check               # ktlint, Compose rules and tests
./kotlin build -m app        # builds the APK
./kotlin do ktlintFormat     # fixes the formatting
```

The build uses Kotlin Toolchain: do not add Gradle. CI also runs markdownlint with
`.markdownlint-cli2.yaml`.

## Open project

The repo is for anyone: nothing from a specific install, such as IPs, real entities or panel names.
Examples use documentation addresses (`192.0.2.x`). Each home's configuration lives in its own
repo.

## Conventions

- **Kotlin:** tabs, trailing commas and 160 columns at most; the details are in `.editorconfig`.
- **Package:** all code lives under `dev.xoa.marc2d`.
- **Documentation and comments:** in English. Comments explain why, not what.
- **Commits:** in English, with Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`).
- **Linter:** fix the cause; disabling a rule requires a comment with the reason. Test a change in
  `plugins/ktlint` with a file that breaks the rules: it has to fail.
- **No backward compatibility:** the project has no users, so break whatever needs breaking.

## Dependencies

- Only stable versions of libraries with recent releases: no alphas, betas or abandoned
  libraries.
- All versions go in `libs.versions.toml`, and a library is added when it is first used.
  The exception is ktlint and its rules, which are in `plugins/ktlint/plugin.yaml`.
- Nothing can depend on Google services: the panels run LineageOS without them.
- A new library is justified in `SPEC.md`, under Decisions. If the Android API is enough, do not add it.

## Secrets

Tokens, passwords and the addresses of other installs are kept in the app's private storage, never in the
repo, logs or tests. Android backups are disabled for this reason: do not re-enable them.

## Testing with real devices

- Always use `adb` with `-s <serial>`: other phones may be connected, including personal ones. Check the
  model before installing anything.
- In kiosk mode, `am force-stop` does not close the app. To restart it, use `run-as dev.xoa.marc2d kill <pid>`.
- Tests against an intercom are done from the direct LAN. Over the Tailscale subnet route the traffic
  leaves with SNAT and the result is not valid.
- The verified VTO details are in `SPEC.md`: do not change them without checking again against the
  device.
- Sounding the siren, speaking through the speakers or calling Home Assistant services affects a house with
  people inside: ask for permission first.

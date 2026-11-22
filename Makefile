# The Python tools, pinned in uv.lock: --frozen fails instead of changing it.
UV_RUN := uv run --frozen
PY_PATHS := custom_components tests
# v0.24.0, the markdownlint-cli2 the CI action used. Dependabot does not read Makefiles: bump it by hand.
MARKDOWNLINT := docker run --rm -v "$(CURDIR):/w" -w /w \
	davidanson/markdownlint-cli2:v0.24.0@sha256:70ead7c0352d22d04bbe612cd96b54ec591538d8f4626cafe391bb2ecf3639ca
# The shared head of AGENTS.md, above its <!-- xoadev:end --> line, comes from AGENTS_URL.
AGENTS_URL := https://xoa.dev/_dev/OSS_AGENTS.md
AGENTS_SCRIPT := https://xoa.dev/_dev/agents.sh

.DEFAULT_GOAL := help
.PHONY: help check lint test format build release app-check app-format integration-check integration-format \
	lint-app test-app check-release lint-integration test-integration lint-markdown lint-typos lint-shell \
	agents-sync agents-check

help: ## Show this help
	@grep -E '^[a-z][a-z -]*:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-20s %s\n", $$1, $$2}'

check: lint test build ## Run everything CI runs locally

lint: lint-app lint-integration lint-markdown lint-typos lint-shell agents-check ## Every linter: ktlint and Compose rules, ruff, markdownlint, typos, shellcheck and the shared AGENTS.md head

test: test-app test-integration ## Every test: the app's and the integration's, with coverage

format: app-format integration-format ## Fix the formatting of all the code

build: ## Build the APK
	./kotlin build -m app

# It changes module.yaml only while building: the version of a release comes from convco.
release: ## Build the signed APK: needs VERSION, VERSION_CODE and app/keystore.properties
	@test -n "$(VERSION)" -a -n "$(VERSION_CODE)" || (echo "VERSION and VERSION_CODE are needed" && exit 1)
	@test -f app/keystore.properties || (echo "app/keystore.properties is needed" && exit 1)
	mkdir -p build
	cp app/module.yaml build/module.yaml.keep
	sed -i 's/^    versionName: .*/    versionName: $(VERSION)\n    versionCode: $(VERSION_CODE)\n    signing:\n      enabled: true/' app/module.yaml
	./kotlin build -m app -v release; status=$$?; cp build/module.yaml.keep app/module.yaml; exit $$status
	cp build/tasks/_app_buildAndroidRelease/gradle-project-release.apk build/marc2d.apk

# R8 shrinks and renames the release, and native code finds Java fields by name: v0.1.0 crashed on start because JNA's
# Pointer.peer was renamed. A debug build never shows it, so this builds the release, unsigned, and looks inside.
RELEASE_DIR := build/tasks/_app_buildAndroidRelease
RELEASE_DEX := build/release-dex
check-release: ## App: build the release and check R8 kept what native code looks up by name
	@# The build does not count app/proguard-rules.pro as an input: a cached release would hide a change to it.
	rm -rf $(RELEASE_DIR) $(RELEASE_DEX)
	./kotlin build -m app -v release
	unzip -q -o $(RELEASE_DIR)/gradle-project-release-unsigned.apk 'classes*.dex' -d $(RELEASE_DEX)
	@sdk=$$(sed -n 's/^sdk.dir=//p' $(RELEASE_DIR)/gradle-project/local.properties); \
	dexdump=$$(ls -d "$$sdk"/build-tools/*/dexdump | sort -V | tail -n 1); \
	for file in $(RELEASE_DEX)/*.dex; do "$$dexdump" "$$file"; done > $(RELEASE_DEX)/dump.txt; \
	grep -A 20 "Class descriptor  : 'Lcom/sun/jna/Pointer;'" $(RELEASE_DEX)/dump.txt | grep -q "name          : 'peer'" \
		|| { echo "R8 renamed com.sun.jna.Pointer.peer: voice would crash the release. Keep it in app/proguard-rules.pro" >&2; exit 1; }; \
	echo "R8 kept com.sun.jna.Pointer.peer"

app-check: lint-app test-app ## App: ktlint, Compose rules and tests

lint-app: ## App: ktlint and Compose rules
	./kotlin check ktlint

test-app: ## App: tests
	./kotlin test

app-format: ## App: fix the formatting
	./kotlin do ktlintFormat

integration-check: lint-integration test-integration ## Integration: format, lint and tests with coverage

lint-integration: ## Integration: format and lint
	$(UV_RUN) ruff format --check $(PY_PATHS)
	$(UV_RUN) ruff check $(PY_PATHS)

test-integration: ## Integration: tests, failing below 90% coverage
	$(UV_RUN) pytest

integration-format: ## Integration: fix the formatting and what can be fixed by itself
	$(UV_RUN) ruff format $(PY_PATHS)
	$(UV_RUN) ruff check --fix $(PY_PATHS)

lint-markdown: ## Lint the Markdown files (needs Docker)
	$(MARKDOWNLINT) "**/*.md"

lint-typos: ## Look for typos in every file
	$(UV_RUN) typos

lint-shell: ## shellcheck every shell script, found by its shebang
	git grep -lzIE '^#!.*/(env )?(sh|bash|dash|ksh)( |$$)' | xargs -0r $(UV_RUN) shellcheck

agents-sync agents-check: ## Sync (agents-sync) or check (agents-check) the shared head of AGENTS.md; agents-check warns when offline
	@if s=$$(curl -fsS --max-time 20 '$(AGENTS_SCRIPT)'); then sh -c "$$s" agents $(@:agents-%=%) '$(AGENTS_URL)'; \
	else echo "warning: $(AGENTS_SCRIPT) unreachable, $@ not run" >&2; [ $@ = agents-check ]; fi

HAPY := uv run --no-project --python 3.14 --with-requirements tests/requirements.txt
PY_PATHS := custom_components tests
MARKDOWNLINT := docker run --rm -v "$(CURDIR):/w" -w /w davidanson/markdownlint-cli2
# The shared head of AGENTS.md, above its <!-- xoadev:end --> line, comes from AGENTS_URL.
AGENTS_URL := https://xoa.dev/_dev/OSS_AGENTS.md
AGENTS_SCRIPT := https://xoa.dev/_dev/agents.sh

.DEFAULT_GOAL := help
.PHONY: help check lint test format build release app-check app-format integration-check integration-format \
	lint-app test-app lint-integration test-integration lint-markdown agents-sync agents-check

help: ## Show this help
	@grep -E '^[a-z][a-z -]*:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-20s %s\n", $$1, $$2}'

check: lint test build ## Run everything CI runs locally

lint: lint-app lint-integration lint-markdown agents-check ## Every linter: ktlint and Compose rules, ruff, markdownlint and the shared AGENTS.md head

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

app-check: lint-app test-app ## App: ktlint, Compose rules and tests

lint-app: ## App: ktlint and Compose rules
	./kotlin check ktlint

test-app: ## App: tests
	./kotlin test

app-format: ## App: fix the formatting
	./kotlin do ktlintFormat

integration-check: lint-integration test-integration ## Integration: format, lint and tests with coverage

lint-integration: ## Integration: format and lint
	$(HAPY) ruff format --check $(PY_PATHS)
	$(HAPY) ruff check $(PY_PATHS)

test-integration: ## Integration: tests, failing below 90% coverage
	$(HAPY) pytest

integration-format: ## Integration: fix the formatting and what can be fixed by itself
	$(HAPY) ruff format $(PY_PATHS)
	$(HAPY) ruff check --fix $(PY_PATHS)

lint-markdown: ## Lint the Markdown files (needs Docker)
	$(MARKDOWNLINT) "**/*.md"

agents-sync agents-check: ## Sync (agents-sync) or check (agents-check) the shared head of AGENTS.md; agents-check warns when offline
	@if s=$$(curl -fsS --max-time 20 '$(AGENTS_SCRIPT)'); then sh -c "$$s" agents $(@:agents-%=%) '$(AGENTS_URL)'; \
	else echo "warning: $(AGENTS_SCRIPT) unreachable, $@ not run" >&2; [ $@ = agents-check ]; fi

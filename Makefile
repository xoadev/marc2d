HAPY := uv run --no-project --python 3.14 --with-requirements tests/requirements.txt
PY_PATHS := custom_components tests
MARKDOWNLINT := docker run --rm -v "$(CURDIR):/w" -w /w davidanson/markdownlint-cli2

.DEFAULT_GOAL := help
.PHONY: help check format build release app-check app-format integration-check integration-format lint-markdown

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-20s %s\n", $$1, $$2}'

check: app-check build integration-check lint-markdown ## Run everything CI runs locally

format: app-format integration-format ## Fix the formatting of all the code

build: ## Build the APK
	./kotlin build -m app

# It changes module.yaml only while building: the version of a release comes from its tag.
release: ## Build the signed APK: needs VERSION, VERSION_CODE and app/keystore.properties
	@test -n "$(VERSION)" -a -n "$(VERSION_CODE)" || (echo "VERSION and VERSION_CODE are needed" && exit 1)
	@test -f app/keystore.properties || (echo "app/keystore.properties is needed" && exit 1)
	mkdir -p build
	cp app/module.yaml build/module.yaml.keep
	sed -i 's/^    versionName: .*/    versionName: $(VERSION)\n    versionCode: $(VERSION_CODE)\n    signing:\n      enabled: true/' app/module.yaml
	./kotlin build -m app -v release; status=$$?; cp build/module.yaml.keep app/module.yaml; exit $$status
	cp build/tasks/_app_buildAndroidRelease/gradle-project-release.apk build/marc2d.apk

app-check: ## App: ktlint, Compose rules and tests
	./kotlin check

app-format: ## App: fix the formatting
	./kotlin do ktlintFormat

integration-check: ## Integration: format, lint and tests with coverage
	$(HAPY) ruff format --check $(PY_PATHS)
	$(HAPY) ruff check $(PY_PATHS)
	$(HAPY) pytest

integration-format: ## Integration: fix the formatting and what can be fixed by itself
	$(HAPY) ruff format $(PY_PATHS)
	$(HAPY) ruff check --fix $(PY_PATHS)

lint-markdown: ## Lint the Markdown files (needs Docker)
	$(MARKDOWNLINT) "**/*.md"

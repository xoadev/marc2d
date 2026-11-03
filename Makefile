HAPY := uv run --no-project --python 3.14 --with-requirements tests/requirements.txt
PY_PATHS := custom_components tests
MARKDOWNLINT := docker run --rm -v "$(CURDIR):/w" -w /w davidanson/markdownlint-cli2

.DEFAULT_GOAL := help
.PHONY: help check format build app-check app-format integration-check integration-format lint-markdown

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-20s %s\n", $$1, $$2}'

check: app-check build integration-check lint-markdown ## Run everything CI runs locally

format: app-format integration-format ## Fix the formatting of all the code

build: ## Build the APK
	./kotlin build -m app

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

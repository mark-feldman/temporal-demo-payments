SHELL := /bin/bash
JAVA_HOME := $(HOME)/.local/share/mise/installs/java/temurin-21.0.11+10.0.LTS
GRADLE := cd backend/kotlin && JAVA_HOME=$(JAVA_HOME) ./gradlew

.PHONY: help preflight start stop reset seed test test-unit test-integration css css-watch build logs workers workers-kill workers-down workers-status

help:
	@echo "make preflight        verify images, tools, fonts and JDK are present"
	@echo "make start            Temporal + Caddy/Prometheus/Grafana + backend"
	@echo "make stop             stop everything"
	@echo "make reset            stop and clear all demo state (history included)"
	@echo "make seed             six workflows in six states"
	@echo "make test             contract tests        (needs a running stack)"
	@echo "make test-unit        JUnit unit tests      (no stack needed)"
	@echo "make test-integration Spring + in-memory Temporal test server (no stack needed)"
	@echo "make build            compile + both JUnit suites"
	@echo "make css              compile Tailwind once  (css-watch to watch)"

preflight:
	@echo "== Docker images =="
	@for i in caddy:2-alpine prom/prometheus:v3.14.0 grafana/grafana:13.2.1; do \
	  docker image inspect $$i >/dev/null 2>&1 && echo "  OK   $$i" || echo "  MISS $$i"; done
	@echo "== Tools =="
	@# 80MB binary, deliberately not in git. Fetched here so a clean checkout can build CSS.
	@if [ ! -x tools/tailwindcss ]; then \
	  echo "  ..   fetching tailwindcss v4.3.3"; mkdir -p tools; \
	  curl -sLo tools/tailwindcss https://github.com/tailwindlabs/tailwindcss/releases/download/v4.3.3/tailwindcss-macos-arm64 \
	    && chmod +x tools/tailwindcss; fi
	@test -x tools/tailwindcss && echo "  OK   tools/tailwindcss" || echo "  MISS tools/tailwindcss"
	@# Assert, do not just report. Two CLIs are installed and PATH order decides which one
	@# `make start` gets, which silently changes the bundled Server and Web UI versions.
	@v=$$(temporal --version 2>/dev/null); \
	  case "$$v" in \
	    *"1.8.3"*) echo "  OK   $$v" ;; \
	    "")        echo "  MISS temporal CLI" ;; \
	    *)         echo "  WRONG $$v"; echo "       expected 1.8.3 - run 'mise install' or check PATH order" ;; \
	  esac
	@echo "== JDK (must be 21, not the machine default 26) =="
	@JAVA_HOME=$(JAVA_HOME) java -version 2>&1 | head -1 | sed 's/^/  /'
	@echo "== Fonts =="
	@ls backend/kotlin/src/main/resources/static/assets/fonts/*.woff2 2>/dev/null | sed 's/^/  OK   /' || echo "  MISS fonts"

start:
	@bash scripts/start-demo.sh

stop:
	@bash scripts/stop-demo.sh

reset:
	@bash scripts/reset-demo.sh

seed:
	@bash scripts/seed-demo-data.sh

workers:
	@bash scripts/scale-workers.sh up $(or $(N),2)

workers-kill:
	@bash scripts/scale-workers.sh kill

workers-down:
	@bash scripts/scale-workers.sh down

workers-status:
	@bash scripts/scale-workers.sh status

test:
	@bash scripts/contract-test.sh

# Both JUnit suites run entirely in-JVM: no dev server, no Docker, no worker JVM.
test-unit:
	@$(GRADLE) test

test-integration:
	@$(GRADLE) integrationTest

css:
	@./tools/tailwindcss -i backend/kotlin/src/css/app.css -o backend/kotlin/src/main/resources/static/assets/app.css

css-watch:
	@./tools/tailwindcss -i backend/kotlin/src/css/app.css -o backend/kotlin/src/main/resources/static/assets/app.css --watch

build:
	@$(GRADLE) build

logs:
	@tail -f /tmp/payout-demo-backend.log

SHELL := /bin/bash
# Discovered rather than hardcoded, so any JDK 21 works. Empty when none was found; the
# helper's own message comes from require-jdk, at the point where a build actually needs it.
JAVA_HOME := $(shell bash scripts/java-home.sh 2>/dev/null)
GRADLE := cd backend/kotlin && JAVA_HOME=$(JAVA_HOME) ./gradlew

.PHONY: help preflight require-jdk start stop reset seed test test-unit test-integration css css-watch build logs workers workers-kill workers-down workers-status

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
	@echo "make workers N=3      scale the worker fleet to N JVMs, cap 10 (workers-kill / -down / -status)"
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
	@pin=$$(sed -n 's|.*temporalio/cli.*version = "v\{0,1\}\([0-9.]*\)".*|\1|p' .mise.toml); \
	  source scripts/temporal-bin.sh; v=$$($$(temporal_bin) --version 2>/dev/null); \
	  case "$$v" in \
	    *"$$pin"*) echo "  OK   $$v" ;; \
	    "")        echo "  MISS temporal CLI" ;; \
	    *)         echo "  WRONG $$v"; echo "       .mise.toml pins $$pin - run 'mise install' or check PATH order" ;; \
	  esac
	@echo "== JDK =="
	@if [[ -n "$(JAVA_HOME)" ]]; then \
	  echo "  OK   $$("$(JAVA_HOME)/bin/java" -version 2>&1 | head -1)"; \
	  echo "       $(JAVA_HOME)"; \
	else \
	  echo "  MISS JDK 21"; bash scripts/java-home.sh >/dev/null || true; \
	fi
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

require-jdk:
	@bash scripts/java-home.sh >/dev/null

# Both JUnit suites run entirely in-JVM: no dev server, no Docker, no worker JVM.
test-unit: require-jdk
	@$(GRADLE) test

test-integration: require-jdk
	@$(GRADLE) integrationTest

css:
	@./tools/tailwindcss -i backend/kotlin/src/css/app.css -o backend/kotlin/src/main/resources/static/assets/app.css

css-watch:
	@./tools/tailwindcss -i backend/kotlin/src/css/app.css -o backend/kotlin/src/main/resources/static/assets/app.css --watch

build: require-jdk
	@$(GRADLE) build

logs:
	@tail -f /tmp/payout-demo-backend.log

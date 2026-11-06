# Checks to run before a release: `make release-check`.
# Gradle runs on the JBR bundled with the IDE from gradle.properties (localIdePath), so JAVA_HOME may point anywhere.

IDE_PATH := $(shell sed -n 's/^localIdePath=//p' gradle.properties)
JAVA_HOME := $(IDE_PATH)/Contents/jbr/Contents/Home
export JAVA_HOME

VERSION := $(shell sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
ZIP := build/distributions/JetbrainsOctopusMerge-$(VERSION).zip
GRADLE := ./gradlew --console=plain

.PHONY: help lint format test verify build run check release-ready release-check clean

help:
	@echo "make lint           ktlint: Kotlin coding conventions (.editorconfig)"
	@echo "make format         ktlint: fix what it can"
	@echo "make test           unit and IDE integration tests"
	@echo "make verify         JetBrains Plugin Verifier against the local IDE"
	@echo "make build          plugin zip: $(ZIP)"
	@echo "make run            sandbox IDE with the plugin"
	@echo "make check          lint + test + verify + build"
	@echo "make release-ready  quick: clean tree, new version, change-notes, docs images"
	@echo "make release-check  release-ready + check: run before tagging $(VERSION)"

lint:
	$(GRADLE) ktlintCheck

format:
	$(GRADLE) ktlintFormat

test:
	$(GRADLE) test

verify:
	$(GRADLE) verifyPlugin

build:
	$(GRADLE) buildPlugin
	@test -f $(ZIP) || { echo "missing $(ZIP)"; exit 1; }

run:
	$(GRADLE) runIde

check: lint test verify build

release-ready:
	@test -z "$$(git status --porcelain)" || { echo "working tree is not clean"; exit 1; }
	@! git rev-parse -q --verify "refs/tags/$(VERSION)" >/dev/null || { echo "tag $(VERSION) already exists: bump the version"; exit 1; }
	@grep -q "<h3>$(VERSION)</h3>" src/main/resources/META-INF/plugin.xml || { echo "no change-notes for $(VERSION) in plugin.xml"; exit 1; }
	@for f in $$(sed -n 's/.*\(docs\/[^")]*\).*/\1/p' README.md src/main/resources/META-INF/plugin.xml | sort -u); do \
		test -f "$$f" || { echo "README or plugin.xml refers to missing $$f"; exit 1; }; \
	done
	@echo "version $(VERSION): tree clean, not tagged yet, change-notes and images in place"

release-check: release-ready check
	@echo "release $(VERSION) is ready: $(ZIP)"

clean:
	$(GRADLE) clean

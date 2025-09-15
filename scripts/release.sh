#!/bin/bash

# Release helper script for QuranSync

set -e

REPO="quran/mobile-sync"

case "$1" in
  "patch"|"minor"|"major")
    BUMP_TYPE="$1"
    PRERELEASE="${2:-false}"
    
    echo "🚀 Triggering $BUMP_TYPE release..."
    
    if [ "$PRERELEASE" = "true" ]; then
      echo "⚠️  This will be marked as a prerelease"
    fi
    
    # Use GitHub CLI to trigger workflow
    gh workflow run release.yml \
      -f version_bump="$BUMP_TYPE" \
      -f prerelease="$PRERELEASE"
    
    echo "✅ Release workflow triggered!"
    echo "🔗 Monitor progress: https://github.com/$REPO/actions"
    ;;
    
  "status")
    echo "📊 Recent releases:"
    gh release list --limit 10
    echo ""
    echo "🏗️  Current workflow runs:"
    gh run list --workflow=release.yml --limit 5
    ;;
    
  "dev-builds")
    echo "🚧 Recent dev builds:"
    gh release list --limit 10 | grep "dev-" || echo "No dev builds found"
    ;;
    
  "cancel")
    echo "⏹️  Cancelling running release workflows..."
    RUNS=$(gh run list --workflow=release.yml --status=in_progress --json databaseId --jq '.[].databaseId')
    
    if [ -z "$RUNS" ]; then
      echo "No running release workflows found"
    else
      for RUN_ID in $RUNS; do
        echo "Cancelling run $RUN_ID..."
        gh run cancel $RUN_ID
      done
      echo "✅ Cancelled running workflows"
    fi
    ;;
    
  *)
    echo "Usage: $0 {patch|minor|major|status|dev-builds|cancel}"
    echo ""
    echo "Commands:"
    echo "  patch      - Create a patch release (1.0.0 → 1.0.1)"
    echo "  minor      - Create a minor release (1.0.0 → 1.1.0)"
    echo "  major      - Create a major release (1.0.0 → 2.0.0)"
    echo "  status     - Show recent releases and workflow status"
    echo "  dev-builds - Show recent development builds"
    echo "  cancel     - Cancel running release workflows"
    echo ""
    echo "Examples:"
    echo "  $0 patch         # Normal patch release"
    echo "  $0 minor true    # Minor prerelease"
    echo "  $0 major         # Major release"
    echo ""
    echo "Prerequisites:"
    echo "  - Install GitHub CLI: brew install gh"
    echo "  - Authenticate: gh auth login"
    exit 1
    ;;
esac
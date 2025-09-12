#!/bin/bash

# Development setup script for QuranSync

IOS_PROJECT_PATH="../YourIOSProject"  # Adjust path as needed
FRAMEWORK_NAME="QuranSync"

case "$1" in
  "local")
    echo "🔨 Building local XCFramework..."
    ./gradlew :umbrella:createXCFramework
    
    echo "📝 Updating Package.swift for local development..."
    sed -i '' 's/let useLocalBuild = false/let useLocalBuild = true/' Package.swift
    
    if [ -d "$IOS_PROJECT_PATH" ]; then
      echo "🔄 Updating iOS project dependencies..."
      cd "$IOS_PROJECT_PATH"
      xcodebuild -resolvePackageDependencies
      echo "✅ Local development setup complete!"
    fi
    ;;
    
  "remote")
    echo "📝 Updating Package.swift for remote dependencies..."
    sed -i '' 's/let useLocalBuild = true/let useLocalBuild = false/' Package.swift
    
    if [ -d "$IOS_PROJECT_PATH" ]; then
      echo "🔄 Updating iOS project dependencies..."
      cd "$IOS_PROJECT_PATH"
      xcodebuild -resolvePackageDependencies
      echo "✅ Remote development setup complete!"
    fi
    ;;
    
  "build")
    echo "🔨 Building XCFramework..."
    ./gradlew :umbrella:createXCFramework
    echo "✅ Build complete!"
    ;;
    
  "dev")
    if [ -z "$2" ]; then
      echo "❌ Please specify a dev build version"
      echo "Usage: $0 dev <version>"
      echo "Example: $0 dev dev-20240101-123456-abc1234"
      exit 1
    fi
    
    DEV_VERSION="$2"
    echo "🔄 Switching to dev build: $DEV_VERSION"
    
    # Fetch the Package.swift that was updated for this specific dev build
    echo "📝 Fetching Package.swift for $DEV_VERSION..."
    
    # Get the commit hash of the dev build tag
    COMMIT_HASH=$(git ls-remote origin refs/tags/$DEV_VERSION | cut -f1)
    
    if [ -n "$COMMIT_HASH" ]; then
      # Fetch the Package.swift from that specific commit
      curl -s "https://raw.githubusercontent.com/quran/mobile-sync/$COMMIT_HASH/Package.swift" -o Package.swift
      
      # Ensure we're not in local mode
      sed -i '' 's/let useLocalBuild = true/let useLocalBuild = false/' Package.swift
      
      echo "✅ Updated Package.swift to use $DEV_VERSION"
      echo "📝 Package.swift automatically configured with correct checksum"
    else
      echo "❌ Could not find dev build $DEV_VERSION"
      echo "    Make sure the version exists: https://github.com/quran/mobile-sync/releases/tag/$DEV_VERSION"
      exit 1
    fi
    ;;
    
  "list-dev")
    echo "📋 Available dev builds:"
    curl -s "https://api.github.com/repos/quran/mobile-sync/releases" | \
      grep -E '"tag_name"|"name"' | \
      grep -A1 '"dev-' | \
      sed 's/.*"tag_name": "\(.*\)".*/\1/' | \
      sed 's/.*"name": "\(.*\)".*/  \1/' | \
      head -20
    ;;
    
  *)
    echo "Usage: $0 {local|remote|build|dev|list-dev}"
    echo "  local     - Switch to local development mode"
    echo "  remote    - Switch to remote dependencies"  
    echo "  build     - Build XCFramework only"
    echo "  dev <ver> - Switch to specific dev build"
    echo "  list-dev  - List available dev builds"
    exit 1
    ;;
esac
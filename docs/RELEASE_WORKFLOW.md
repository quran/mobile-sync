# Release Workflow Guide

## How Releases Work

### 🔄 Development Builds (Automatic)
- **Trigger**: Every push to `main` branch
- **Tag Format**: `dev-YYYYMMDD-HHMMSS-SHORTSHA` (e.g., `dev-20240315-143022-abc1234`)
- **Package.swift**: Automatically updated with dev build info
- **Cancellation**: New builds cancel previous ones
- **Cleanup**: Only keeps 5 most recent dev builds
- **Usage**: For testing latest changes with standard SPM integration

### 🚀 Stable Releases (Manual)
- **Trigger**: Manual workflow dispatch
- **Tag Format**: `v1.2.3` (semantic versioning)
- **No cancellation**: Releases run to completion
- **Persistence**: Stable releases are never auto-deleted

## Triggering Releases

### Option 1: GitHub Web Interface
1. Go to **Actions** tab in GitHub
2. Click **Release** workflow
3. Click **Run workflow** button
4. Choose:
   - **Branch**: `main` (default)
   - **Version bump**: `patch`, `minor`, or `major`
   - **Prerelease**: `true` or `false`

### Option 2: GitHub CLI (Recommended)
```bash
# Install GitHub CLI if needed
brew install gh
gh auth login

# Trigger releases
gh workflow run release.yml -f version_bump=patch
gh workflow run release.yml -f version_bump=minor -f prerelease=true
gh workflow run release.yml -f version_bump=major
```

### Option 3: Helper Script (Easiest)
```bash
# Use our helper script
./scripts/release.sh patch     # 1.0.0 → 1.0.1
./scripts/release.sh minor     # 1.0.0 → 1.1.0  
./scripts/release.sh major     # 1.0.0 → 2.0.0

# Check status
./scripts/release.sh status    # View releases and workflow runs
./scripts/release.sh cancel    # Cancel running releases if needed
```

## What Happens During Builds/Releases

### Development Build Steps (Automatic on main push):
1. **Testing**: Runs full test suite (`./gradlew allTests`)
2. **Building**: Creates XCFramework (`./gradlew :umbrella:createXCFramework`)
3. **Package Update**: Updates `Package.swift` with dev build version and checksum
4. **Git Operations**:
   - Commits Package.swift changes
   - Pushes updated Package.swift to main
   - Creates dev build tag (e.g., `dev-20240315-143022-abc1234`)
5. **Release Creation**:
   - Creates GitHub prerelease with dev build
   - Uploads XCFramework zip file
   - Includes checksum in release notes

### Stable Release Steps (Manual trigger):
1. **Version Calculation**: Bumps version based on current git tags
2. **Testing**: Runs full test suite (`./gradlew allTests`)
3. **Building**: Creates XCFramework (`./gradlew :umbrella:createXCFramework`)
4. **Package Update**: Updates `Package.swift` with stable version and checksum
5. **Git Operations**: 
   - Commits Package.swift changes
   - Creates new git tag (e.g., `v1.2.3`)
   - Pushes to repository
6. **Release Creation**: 
   - Creates GitHub release with generated notes
   - Uploads XCFramework zip file
   - Marks as prerelease if specified

### The Bot Email Explained
```yaml
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
```
This is GitHub's official bot email address. When the workflow commits Package.swift changes, it shows up in git history as coming from `github-actions[bot]` instead of a real user account.

## Version Bumping Logic

The release workflow automatically determines the next version:

```bash
# Current version: v1.2.3

./scripts/release.sh patch  # → v1.2.4 (bug fixes)
./scripts/release.sh minor  # → v1.3.0 (new features, backward compatible)  
./scripts/release.sh major  # → v2.0.0 (breaking changes)
```

## Development Workflow Examples

### Daily Development
```bash
# 1. Work on features, merge PRs to main
git checkout -b feature/new-sync-algorithm
# ... make changes ...
git push origin feature/new-sync-algorithm
# ... create PR, merge to main ...

# 2. Dev build automatically created: dev-20240315-143022-abc1234

# 3. Test in iOS project using dev build
./scripts/dev-setup.sh list-dev  # See available builds
./scripts/dev-setup.sh dev dev-20240315-143022-abc1234

# Or use standard SPM (Package.swift is auto-updated):
# .package(url: "https://github.com/quran/mobile-sync", exact: "dev-20240315-143022-abc1234")

# 4. When ready for stable release
./scripts/release.sh patch  # Creates v1.0.1
```

### Release Cadence Suggestions
- **Patch releases**: Bug fixes, small improvements (weekly/bi-weekly)
- **Minor releases**: New features, API additions (monthly/quarterly)
- **Major releases**: Breaking changes, major rewrites (quarterly/yearly)

## Troubleshooting

### Release Failed?
```bash
./scripts/release.sh status  # Check what happened
gh run view [RUN_ID] --log   # View detailed logs
```

### Need to Cancel Release?
```bash
./scripts/release.sh cancel  # Stops running releases
```

### Wrong Version Released?
- You can create a new release immediately
- Old releases are never auto-deleted
- Consider using prerelease flag for testing

### Dev Build Missing?
- Check if build failed: Go to Actions tab
- Older dev builds are auto-deleted (only 5 kept)
- Use `./scripts/dev-setup.sh list-dev` to see available builds
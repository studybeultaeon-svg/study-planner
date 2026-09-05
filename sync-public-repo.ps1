# Syncs only the app source (phone-lock-android/, phone-lock-desktop/, shared/) plus
# .gitignore and .claude/launch.json from this working directory (whatever is
# currently on disk on "master" -- usually UNCOMMITTED, since this project's
# convention is to not commit until asked) into a separate worktree checked
# out on "clean-main" (public, source-only, tracks origin/main), then commits
# and pushes from that worktree. Internal docs (BUGS.md, CHANGELOG.md,
# DECISIONS.md, HANDOFF.md, IDEAS.md, VM_BUILD_HANDOFF.md, the report md) are
# intentionally never copied.
#
# IMPORTANT: this script must NEVER run "git checkout <branch>" in the main
# working directory (master) -- that would overwrite whatever is currently on
# disk with master's last COMMIT, silently discarding uncommitted work (this
# happened once: 2026-08-30, an update-check bug fix was wiped out this way).
# Using a separate worktree for clean-main avoids that entirely: the main
# directory's branch is never switched, only files are copied *into* the
# worktree directory with robocopy.
#
# Usage: powershell -File sync-public-repo.ps1 [commit message]
# No Korean text in this file (project rule) -- PowerShell 5.1 misreads
# non-ASCII in BOM-less .ps1 files, so keep this script all-ASCII.

param(
    [string]$CommitMessage = "Sync source from master"
)

$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot
$worktree = "C:\build\clean-main-worktree"

if (-not (Test-Path $worktree)) {
    Write-Host "Worktree not found at $worktree -- creating it..."
    Set-Location $repoRoot
    git worktree add $worktree clean-main
}

Write-Host "Copying phone-lock-android from master (working tree) into the clean-main worktree..."
robocopy "$repoRoot\phone-lock-android" "$worktree\phone-lock-android" /MIR /XD ".git" "build" | Out-Null

Write-Host "Copying phone-lock-desktop from master (working tree) into the clean-main worktree..."
robocopy "$repoRoot\phone-lock-desktop" "$worktree\phone-lock-desktop" /MIR /XD ".git" "build" | Out-Null

Write-Host "Copying shared from master (working tree) into the clean-main worktree..."
robocopy "$repoRoot\shared" "$worktree\shared" /MIR /XD ".git" "build" | Out-Null

Copy-Item "$repoRoot\.gitignore" "$worktree\.gitignore" -Force
Copy-Item "$repoRoot\.claude\launch.json" "$worktree\.claude\launch.json" -Force

Set-Location $worktree
git add phone-lock-android phone-lock-desktop shared .gitignore .claude/launch.json

$changed = git status --porcelain -- phone-lock-android phone-lock-desktop shared .gitignore .claude/launch.json
if (-not $changed) {
    Write-Host "Nothing changed -- skipping commit and push."
    Set-Location $repoRoot
    exit 0
}

git commit -m $CommitMessage
if ($LASTEXITCODE -ne 0) { Set-Location $repoRoot; throw "git commit failed" }

Write-Host "Pushing clean-main to origin/main..."
git push origin clean-main:main
if ($LASTEXITCODE -ne 0) { Set-Location $repoRoot; throw "git push failed" }

Set-Location $repoRoot
Write-Host "Done."

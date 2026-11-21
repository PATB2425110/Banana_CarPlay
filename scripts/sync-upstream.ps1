<#
.SYNOPSIS
    把官方 xcertplay 的更新同步到二开主线。

.DESCRIPTION
    执行流程（与 ARCHITECTURE.md §11.3 一致）：

        1. git fetch upstream
        2. master  --ff-only-->  upstream/master     (master 永远等于上游)
        3. dev     --merge---->  master              (把上游改进带进二开主线)
        4. 列出本次同步引入的上游改动，并检查已知冲突点
        5. 打印需要人工验证的命令（不自动跑构建，避免长时间占用）

    采用 merge 而不是 rebase 的理由见 ARCHITECTURE.md §11.2。

.PARAMETER MainBranch
    二开主线分支名，默认 dev。

.PARAMETER SkipFetch
    跳过 git fetch（用于离线复跑）。

.EXAMPLE
    .\scripts\sync-upstream.ps1
#>
[CmdletBinding()]
param(
    [string]$MainBranch = 'dev',
    [switch]$SkipFetch
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------------ preflight
# 本脚本靠 git 干活。若 git 不在 PATH 上，`& git ...` 会在
# $ErrorActionPreference='Stop' 下**立刻终止脚本、且不打印任何有用信息**——
# 表现为"跑完什么都没有、退出码还是 0"，极难排查。所以先把 git 找出来。
function Resolve-GitCommand {
    if ($env:GIT_EXE -and (Test-Path $env:GIT_EXE)) { return $env:GIT_EXE }

    $cmd = Get-Command git -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # ⚠️ 不要只信 $env:ProgramFiles —— 实测在精简/注入式环境里它可能是**空字符串**，
    # 于是 "$env:ProgramFiles\Git\cmd\git.exe" 塌成 "\Git\cmd\git.exe" 而永远找不到。
    # 既用 [Environment]（走注册表，不依赖环境变量），也留字面量兜底。
    $roots = @(
        [Environment]::GetFolderPath('ProgramFiles'),
        [Environment]::GetFolderPath('ProgramFilesX86'),
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)},
        'C:\Program Files',
        'C:\Program Files (x86)'
    ) | Where-Object { $_ } | Select-Object -Unique

    foreach ($root in $roots) {
        foreach ($rel in @('Git\cmd\git.exe', 'Git\bin\git.exe')) {
            $candidate = Join-Path $root $rel
            if (Test-Path $candidate) { return $candidate }
        }
    }

    # 用户级安装 / 包管理器
    foreach ($candidate in @(
        "$env:LOCALAPPDATA\Programs\Git\cmd\git.exe",
        "$env:USERPROFILE\scoop\apps\git\current\cmd\git.exe"
    )) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }

    return $null
}

$gitExe = Resolve-GitCommand
if (-not $gitExe) {
    Write-Host 'ERROR: 找不到 git，本脚本无法运行。' -ForegroundColor Red
    Write-Host '任选其一：' -ForegroundColor Yellow
    Write-Host '  1) 安装 Git for Windows  https://git-scm.com/download/win'
    Write-Host '  2) 显式指定:  $env:GIT_EXE = "C:\Program Files\Git\cmd\git.exe"'
    exit 2
}
# 把 git 所在目录挂到 PATH 最前面，后面所有 `& git ...` 就都能解析。
$env:PATH = (Split-Path -Parent $gitExe) + ';' + $env:PATH

function Get-RepoRoot {
    $root = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $root) {
        throw 'Not inside a git repository.'
    }
    return $root.Trim()
}

function Assert-CleanOrWarn {
    $dirty = & git status --porcelain
    if ($dirty) {
        Write-Warning 'Working tree is not clean. Commit or stash before syncing:'
        $dirty | ForEach-Object { Write-Host "    $_" }
        $answer = Read-Host 'Continue anyway? (y/N)'
        if ($answer -ne 'y') { throw 'Aborted by user.' }
    }
}

function Warn-UncommittedUpstreamPatches {
    # PATCH-0004 / PATCH-0005 改的是上游文件。它们如果只躺在工作区，
    # 一次 git checkout / git merge 就可能把它们冲掉。
    $upstreamPaths = @('shared', 'common', 'mobile', 'automotive', 'gradle', 'build.gradle.kts')
    $touched = & git status --porcelain -- $upstreamPaths
    if ($touched) {
        Write-Host ''
        Write-Warning 'Uncommitted changes under UPSTREAM paths:'
        $touched | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
        Write-Warning 'These are registered patches (PATCHES.md PATCH-0004 / PATCH-0005).'
        Write-Warning 'They exist ONLY in the working tree right now.'
        Write-Warning 'Commit them before syncing, or git checkout/merge may overwrite them.'
        Write-Host ''
    }
}

function Get-RemoteName {
    $remotes = & git remote
    if ($remotes -contains 'upstream') { return 'upstream' }
    Write-Warning "No 'upstream' remote found. Run: git remote add upstream https://github.com/shilapi/xcertplay.git"
    throw "Missing remote 'upstream'."
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot

Write-Host "==> Repository: $repoRoot" -ForegroundColor Cyan
$remote = Get-RemoteName
Write-Host "==> Upstream remote: $remote" -ForegroundColor Cyan

Assert-CleanOrWarn
Warn-UncommittedUpstreamPatches

$startBranch = (& git rev-parse --abbrev-ref HEAD).Trim()
Write-Host "==> Current branch: $startBranch" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1. fetch
if (-not $SkipFetch) {
    Write-Host "`n[1/5] Fetching $remote ..." -ForegroundColor Green
    & git fetch $remote --prune
    if ($LASTEXITCODE -ne 0) { throw 'git fetch failed.' }
} else {
    Write-Host "`n[1/5] Skipping fetch (-SkipFetch)." -ForegroundColor DarkGray
}

$upstreamBranch = "$remote/master"
& git rev-parse --verify --quiet $upstreamBranch | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve $upstreamBranch." }

$upstreamHead = (& git rev-parse $upstreamBranch).Trim()
Write-Host "    $upstreamBranch = $upstreamHead"

# ------------------------------------------------- 2. master fast-forward
Write-Host "`n[2/5] Fast-forwarding 'master' to $upstreamBranch ..." -ForegroundColor Green
& git rev-parse --verify --quiet master | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Warning "'master' does not exist locally; creating it from $upstreamBranch."
    & git branch master $upstreamBranch
} else {
    & git checkout master
    if ($LASTEXITCODE -ne 0) { throw 'git checkout master failed.' }
    & git merge --ff-only $upstreamBranch
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "master is not a fast-forward of $upstreamBranch."
        Write-Warning 'This usually means someone committed directly to master.'
        Write-Warning "Inspect with: git log --oneline --graph master..$upstreamBranch"
        throw 'master diverged from upstream. Resolve manually before syncing.'
    }
}

# ------------------------------------------------- 3. merge into main line
Write-Host "`n[3/5] Merging master into '$MainBranch' ..." -ForegroundColor Green
& git rev-parse --verify --quiet $MainBranch | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Warning "'$MainBranch' does not exist locally; creating it from master."
    & git branch $MainBranch master
}
& git checkout $MainBranch
if ($LASTEXITCODE -ne 0) { throw "git checkout $MainBranch failed." }

$before = (& git rev-parse HEAD).Trim()
& git merge --no-edit master
$mergeExit = $LASTEXITCODE
if ($mergeExit -ne 0) {
    Write-Host ''
    Write-Warning 'Merge produced conflicts.'
    Write-Host 'Conflicting files:' -ForegroundColor Yellow
    & git diff --name-only --diff-filter=U | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
    Write-Host ''
    Write-Host 'Known conflict points (PATCHES.md):' -ForegroundColor Yellow
    Write-Host '  settings.gradle.kts' -ForegroundColor Yellow
    Write-Host '      [PATCH-0001] keep BOTH sides: upstream content + our trailing include block.' -ForegroundColor Yellow
    Write-Host '  shared/.../network/LocalOnlyHotspotManager.kt' -ForegroundColor Yellow
    Write-Host '      [PATCH-0004] keep OUR selectHotspotInterface / hotspotAddress (the isUp() guard' -ForegroundColor Yellow
    Write-Host '      breaks on real devices). Then re-verify the reflection block still matches the' -ForegroundColor Yellow
    Write-Host '      upstream API names (setSsid / setWifiSsid, startLocalOnlyHotspot*).' -ForegroundColor Yellow
    Write-Host '  shared/.../orchestration/CarPlayController.kt' -ForegroundColor Yellow
    Write-Host '      [PATCH-0005] keep our LOCAL_ONLY_HOTSPOT branch (passes ssid/passphrase/security).' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'After resolving: re-check EACH patch is still needed (ARCHITECTURE.md §13 step 5).' -ForegroundColor Yellow
    Write-Host 'Then: git add <files>; git commit' -ForegroundColor Yellow
    Set-Location $repoRoot
    exit 1
}
$after = (& git rev-parse HEAD).Trim()

# ------------------------------------------------------- 4. report upstream
Write-Host "`n[4/5] Upstream changes brought in ..." -ForegroundColor Green
Write-Host ""
if ($before -eq $after) {
    Write-Host '    (nothing new - already up to date)' -ForegroundColor DarkGray
} else {
    & git log --oneline --no-decorate "$before..$after" | ForEach-Object { Write-Host "    $_" }
    Write-Host ''
    Write-Host '    Files changed by upstream:' -ForegroundColor Cyan
    & git diff --stat "$before" "$after" -- | ForEach-Object { Write-Host "    $_" }
}

# --------------------------------------------------------- 5. next actions
Write-Host "`n[5/5] Verification required." -ForegroundColor Green
Write-Host @'

  The sync is NOT done until the build and tests pass. Run:

    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
    .\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug
    .\gradlew.bat :car:api:assembleDebug :car:core-bridge:assembleDebug :car:platform-android:assembleDebug :car:platform-flyme:assembleDebug :car:steering:assembleDebug :car:ui:assembleDebug

  If ':car:core-bridge:compileDebugKotlin' fails on an exhaustive 'when'
  in CarPlayStatusMapper.kt, that is BY DESIGN: upstream added a new
  CarPlayStatus. Map it to the right CarSessionPhase. Do not add 'else'.

  Then update UPSTREAM.md ("Current sync status") with the new commit.

'@ -ForegroundColor Cyan

Set-Location $repoRoot
exit 0

<#
.SYNOPSIS
    审计二开改动足迹：我们的代码是否真的没有污染上游路径。

.DESCRIPTION
    回答三个问题：

        1. 相对上游，我们到底改了哪些**已存在**的上游文件？
           （这些是合并时的真实风险点，必须与 PATCHES.md 一一对应）
        2. 我们**新增**了哪些文件/目录？（这些必须全部落在上游没有的路径下）
        3. 上游路径下有没有意外的、未登记的改动？

    退出码：0 = 足迹正常；1 = 发现未登记的上游改动。

.PARAMETER Base
    对比基线，默认 upstream/master。

.PARAMETER Strict
    严格模式：只要有上游文件被改动且未登记在 PATCHES.md 就返回失败。
    （默认即开启；加 -Lenient 可关闭）

.EXAMPLE
    .\scripts\footprint.ps1
#>
[CmdletBinding()]
param(
    [string]$Base = 'upstream/master',
    [switch]$Lenient
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

# 这些是"允许被改动"的上游文件，必须与 PATCHES.md 中的 APPLIED 条目严格一致。
$allowedUpstreamEdits = @(
    'settings.gradle.kts'                                       # PATCH-0001
    'shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt'      # PATCH-0004
    'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt'      # PATCH-0005
)

# 我们的扩展根目录：所有新增内容都必须落在这些前缀下。
# 注意是 `scripts/` 不是 `tools/`：上游根 .gitignore 有 `tools/`（匹配任意层级），
# 用那个名字会让脚本不被跟踪。详见 PATCHES.md「已排除的改动」。
$extensionRoots = @(
    'car/',
    'configs/',
    'scripts/',
    'ARCHITECTURE.md',
    'UPSTREAM.md',
    'PATCHES.md'
)

# 本地文件，不属于仓库内容。
$ignoredPaths = @(
    'local.properties',
    'baseline-build.log',
    'baseline-build.err'
)

$repoRoot = & git rev-parse --show-toplevel 2>$null
# ⚠️ 必须先判空再 Set-Location：空字符串会让 Set-Location 抛错，
# 在 $ErrorActionPreference='Stop' 下**直接终止脚本且不打印任何有用信息**。
# 实测（受限环境里 git 无法执行时）就是这个表现：跑完什么都没有、退出码还是 0。
if (-not $repoRoot) {
    Write-Host 'ERROR: 取不到仓库根目录。' -ForegroundColor Red
    Write-Host "  当前目录: $((Get-Location).Path)" -ForegroundColor Yellow
    Write-Host '  可能原因：当前目录不在 git 仓库内；或 git 无法执行。' -ForegroundColor Yellow
    Write-Host "  已验证的 git 路径: $gitExe" -ForegroundColor Yellow
    Write-Host '  自检: git rev-parse --show-toplevel' -ForegroundColor Yellow
    exit 2
}
$repoRoot = $repoRoot.Trim()
Set-Location $repoRoot

& git rev-parse --verify --quiet $Base | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Cannot resolve base '$Base'. Add the upstream remote first:"
    Write-Host '    git remote add upstream https://github.com/shilapi/xcertplay.git'
    Write-Host '    git fetch upstream'
    exit 2
}

Write-Host "==> Baseline: $Base ($((& git rev-parse $Base).Trim()))" -ForegroundColor Cyan
Write-Host "==> HEAD    : $((& git rev-parse HEAD).Trim())" -ForegroundColor Cyan
Write-Host ''

# ------------------------------------------------------------------ collect
$modifiedUpstream = New-Object System.Collections.Generic.List[string]
$addedFiles = New-Object System.Collections.Generic.List[string]
$deletedFiles = New-Object System.Collections.Generic.List[string]
$unexpected = New-Object System.Collections.Generic.List[string]

function Test-IsExtensionPath([string]$path) {
    foreach ($root in $extensionRoots) {
        if ($path -eq $root -or $path.StartsWith($root)) { return $true }
    }
    return $false
}

# 1) 已跟踪文件相对基线的差异
$nameStatus = @(& git diff --name-status $Base -- 2>$null)
foreach ($line in $nameStatus) {
    if (-not $line) { continue }
    $parts = $line -split "`t"
    if ($parts.Count -lt 2) { continue }
    $status = $parts[0]
    $path = ($parts[-1] -replace '\\', '/').Trim()

    if ($ignoredPaths -contains $path) { continue }
    $isExtensionPath = Test-IsExtensionPath $path

    switch -Regex ($status) {
        '^A' { $addedFiles.Add($path) | Out-Null }
        '^D' {
            $deletedFiles.Add($path) | Out-Null
            if (-not $isExtensionPath) { $unexpected.Add("DELETED upstream file: $path") | Out-Null }
        }
        '^M' {
            # 扩展层内部文件反复修改是正常的，只有上游路径下的改动需要登记。
            if (-not $isExtensionPath) { $modifiedUpstream.Add($path) | Out-Null }
        }
        '^R' { $unexpected.Add("RENAMED: $line") | Out-Null }
        default { if (-not $isExtensionPath) { $unexpected.Add("$status $path") | Out-Null } }
    }
}

# 2) 未跟踪的新文件（git diff 看不到它们，但正是扩展层的主要形态）
$untracked = @(& git ls-files --others --exclude-standard 2>$null)
foreach ($raw in $untracked) {
    if (-not $raw) { continue }
    # git 对含非 ASCII 的路径会输出带引号的转义形式，这里只做前缀判断，不需要还原。
    $path = ($raw -replace '\\', '/').Trim('"')
    if ($ignoredPaths -contains $path) { continue }
    if (Test-IsExtensionPath $path) { $addedFiles.Add($path) | Out-Null }
    else { $unexpected.Add("New file outside extension paths: $path") | Out-Null }
}

# ------------------------------------------------------------------- report
$bar = '-' * 60
Write-Host $bar -ForegroundColor DarkGray
Write-Host "[1] Modified UPSTREAM files: $($modifiedUpstream.Count)" -ForegroundColor Yellow
Write-Host '    (each must map to an APPLIED entry in PATCHES.md)' -ForegroundColor DarkGray
if ($modifiedUpstream.Count -eq 0) {
    Write-Host '    (none)' -ForegroundColor Green
} else {
    foreach ($path in $modifiedUpstream) {
        $registered = $allowedUpstreamEdits -contains $path
        $mark = if ($registered) { '[registered]  ' } else { '[UNREGISTERED]' }
        $color = if ($registered) { 'Green' } else { 'Red' }
        Write-Host "    $mark $path" -ForegroundColor $color
        if (-not $registered) { $unexpected.Add("Unregistered upstream edit: $path") | Out-Null }
    }
}

Write-Host ''
Write-Host "[2] Added extension files: $($addedFiles.Count)" -ForegroundColor Green
Write-Host '    (all must live under: car/ configs/ scripts/ ARCHITECTURE.md UPSTREAM.md PATCHES.md)' -ForegroundColor DarkGray
if ($addedFiles.Count -eq 0) {
    Write-Host '    (none)' -ForegroundColor DarkGray
} else {
    # 按顶层目录聚合，避免一次刷出上百行。
    $addedFiles |
        ForEach-Object { ($_ -split '/')[0..1] -join '/' } |
        Group-Object |
        Sort-Object Name |
        ForEach-Object { Write-Host ("    {0,-40} {1,4} file(s)" -f $_.Name, $_.Count) }
}

Write-Host ''
Write-Host "[3] Deleted upstream files: $($deletedFiles.Count)" -ForegroundColor Yellow
if ($deletedFiles.Count -eq 0) {
    Write-Host '    (none)' -ForegroundColor Green
} else {
    foreach ($path in $deletedFiles) { Write-Host "    - $path" -ForegroundColor Red }
}

Write-Host $bar -ForegroundColor DarkGray
if ($unexpected.Count -eq 0) {
    Write-Host 'Footprint OK: upstream untouched except registered patches.' -ForegroundColor Green
    exit 0
}

Write-Host 'Footprint WARNING:' -ForegroundColor Red
foreach ($item in $unexpected) { Write-Host "  - $item" -ForegroundColor Red }
Write-Host ''
Write-Host 'Fix: either move the change into car/ configs/ scripts/, or register it in PATCHES.md.' -ForegroundColor Yellow

if ($Lenient) { exit 0 }
exit 1

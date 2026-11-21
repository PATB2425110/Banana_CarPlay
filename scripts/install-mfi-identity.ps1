<#
.SYNOPSIS
    把一份 MFi 配件身份安装进二开工程的 assets，供"本机软件认证"随包内置使用。

.DESCRIPTION
    身份由两个文件组成，必须来自**同一份** Apple 签发的配件：

        identity.pk8        私钥（PKCS#8 DER）
        certificate.p7b     配件证书（PKCS#7 容器；裸 X.509 的 .cer/.crt 也可以）

    装好之后，APK 里就有了 assets/offline-mfi/，设置页把"认证方式"选成
    "本机软件认证"即可，不需要任何 MFi 硬件。

    来源有三种，按优先级：

        1. -FromDirectory <目录>    从目录里取这两个文件
        2. -FromApk <apk 路径>      从 APK 里解出 assets/offline-mfi/*
        3. 都不给                   在当前目录下自动找 offline-mfi\ 或唯一的 *.apk

    脚本会做这些检查：
        - 两个文件都存在、非空、不超过 16 KB（与运行时上限一致）
        - 用 Windows 自带的 CNG 做一次真实的 sign/verify，确认私钥与证书配对、
          且证书是 P-256。不匹配就直接拒绝安装，不留半套身份。

    注意：脚本只做**静态**检查。真正的把关在运行时
    （CarMfiAccessoryKey 会验证私钥可解析、证书是 P-256、私钥与证书配对），
    以及 car/core-bridge 的单测里。

.PARAMETER FromDirectory
    存放 identity.pk8 与 certificate.p7b 的目录。

.PARAMETER FromApk
    APK 路径。会按 assets/offline-mfi/ 的路径去里面找那两个文件。

.PARAMETER Force
    目标目录已存在时覆盖它。不加这个开关会直接报错退出。

.EXAMPLE
    .\scripts\install-mfi-identity.ps1 -FromApk .\DiPlay-0.2.0.apk

.EXAMPLE
    .\scripts\install-mfi-identity.ps1 -FromDirectory D:\mfi-identity -Force

.NOTES
    assets/offline-mfi/ 与 *.pk8 已在 .gitignore 里排除。
    配件身份属于它的合法持有者，不要提交进仓库，也不要随公开发行版分发。
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$FromDirectory,
    [string]$FromApk,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$IdentityName = 'identity.pk8'
$CertificateName = 'certificate.p7b'
$AssetDirectoryName = 'offline-mfi'

# 与 CarMfiAccessoryKey.MAX_FILE_BYTES 保持一致。
$MaxFileBytes = 16 * 1024

$RepoRoot = Split-Path -Parent $PSScriptRoot
$DestinationDirectory = Join-Path $RepoRoot "car\app\src\main\assets\$AssetDirectoryName"

function Write-Step {
    param([string]$Message)
    Write-Host "  $Message"
}

# ---------------------------------------------------------------------- 读来源

function Read-ApkEntry {
    <#
        从 APK 里取出指定后缀的条目。找不到返回 $null。
        用 ZipFile 而不是 Expand-Archive：后者要求文件后缀是 .zip。
    #>
    param(
        [string]$ApkPath,
        [string]$Name
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $wanted = "assets/$AssetDirectoryName/$Name"
        $entry = $archive.Entries |
            Where-Object { $_.FullName -eq $wanted } |
            Select-Object -First 1
        if (-not $entry) {
            # 有些构建工具会把 assets 前缀改掉，退一步按后缀找。
            $entry = $archive.Entries |
                Where-Object { $_.FullName -like "*/$AssetDirectoryName/$Name" } |
                Select-Object -First 1
        }
        if (-not $entry) { return $null }

        $stream = $entry.Open()
        try {
            $memory = New-Object System.IO.MemoryStream
            $stream.CopyTo($memory)
            return $memory.ToArray()
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Resolve-FromApk {
    param([string]$ApkPath)

    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "找不到 APK：$ApkPath"
    }
    Write-Step "来源：APK $ApkPath"

    $key = Read-ApkEntry -ApkPath $ApkPath -Name $IdentityName
    $certificate = Read-ApkEntry -ApkPath $ApkPath -Name $CertificateName
    if (-not $key -or -not $certificate) {
        throw "这个 APK 里没有 assets/$AssetDirectoryName/$IdentityName 与 $CertificateName"
    }
    return @{ Key = $key; Certificate = $certificate }
}

function Resolve-FromDirectory {
    param([string]$Directory)

    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw "找不到目录：$Directory"
    }
    $keyPath = Join-Path $Directory $IdentityName
    $certificatePath = Join-Path $Directory $CertificateName
    foreach ($path in @($keyPath, $certificatePath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "目录里缺少 $(Split-Path -Leaf $path)：$Directory"
        }
    }
    Write-Step "来源：目录 $Directory"

    return @{
        Key         = [System.IO.File]::ReadAllBytes($keyPath)
        Certificate = [System.IO.File]::ReadAllBytes($certificatePath)
    }
}

function Resolve-Automatically {
    $candidates = @(
        (Join-Path (Get-Location).Path $AssetDirectoryName),
        (Join-Path $RepoRoot $AssetDirectoryName),
        (Join-Path (Split-Path -Parent $RepoRoot) $AssetDirectoryName)
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            return Resolve-FromDirectory -Directory $candidate
        }
    }

    $apks = @()
    foreach ($directory in @((Get-Location).Path, $RepoRoot, (Split-Path -Parent $RepoRoot))) {
        if (Test-Path -LiteralPath $directory -PathType Container) {
            $apks += @(Get-ChildItem -LiteralPath $directory -Filter '*.apk' -File -ErrorAction SilentlyContinue)
        }
    }
    if ($apks.Count -eq 1) {
        return Resolve-FromApk -ApkPath $apks[0].FullName
    }

    throw @"
没有指定来源，也自动找不到。

请显式给一个：
    .\scripts\install-mfi-identity.ps1 -FromDirectory <目录>
    .\scripts\install-mfi-identity.ps1 -FromApk <apk 路径>
"@
}

# ---------------------------------------------------------------------- 校验

function Test-IdentityPairing {
    <#
        校验私钥与证书是否配对，顺便确认证书是 P-256。

        用 Windows 自带的 CNG（CngKey），不用 openssl，理由：
          - 不依赖外部可执行文件，装机就能跑（openssl 在很多 Windows 上并不在 PATH 里）；
          - `CngKey.Import` 直接吃 PKCS#8，不用先把私钥转成 PEM；
          - 这是一次真正的 sign/verify，不是"能解析就算过"。

        返回 @{ Checked = <bool>; Ok = <bool>; Reason = <string> }。
        CNG 不可用（例如非 Windows 上跑 PowerShell 7）时 Checked = $false，不阻断安装。
    #>
    param(
        [byte[]]$KeyBytes,
        [byte[]]$CertificateBytes
    )

    if (-not ("System.Security.Cryptography.CngKey" -as [type])) {
        return @{ Checked = $false; Ok = $true; Reason = '当前 PowerShell 没有 CNG，跳过配对自检' }
    }

    # 证书：PKCS#7 容器与裸 X.509 都能被 X509Certificate2Collection 吃下。
    $collection = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
    try { $collection.Import($CertificateBytes) }
    catch { return @{ Checked = $true; Ok = $false; Reason = '证书既不是 PKCS#7 容器也不是 X.509 证书' } }
    if ($collection.Count -lt 1) {
        return @{ Checked = $true; Ok = $false; Reason = '证书里没有可用的 X.509 证书' }
    }
    $certificate = $collection[0]

    # 私钥：CNG 直接导入 PKCS#8。
    $privateCng = $null
    $privateKey = $null
    try {
        $privateCng = [System.Security.Cryptography.CngKey]::Import(
            $KeyBytes,
            [System.Security.Cryptography.CngKeyBlobFormat]::Pkcs8PrivateBlob
        )
        $privateKey = New-Object System.Security.Cryptography.ECDsaCng($privateCng)
    }
    catch {
        return @{ Checked = $true; Ok = $false; Reason = 'identity.pk8 不是有效的 PKCS#8 EC 私钥' }
    }

    $publicCng = $null
    $publicKey = $null
    try {
        # .NET Framework 上没有 X509Certificate2.GetECDsaPublicKey()，
        # 所以从证书里取裸 EC 点（0x04 || X || Y），手工拼成 CNG 的 ECCPUBLIC_BLOB：
        #     dwMagic(4) | cbKey(4) | X(32) | Y(32)
        $point = $certificate.PublicKey.EncodedKeyValue.RawData
        if ($point.Length -ne 65 -or $point[0] -ne 0x04) {
            return @{ Checked = $true; Ok = $false; Reason = '证书公钥不是未压缩的 EC 点' }
        }
        $blob = New-Object byte[] 72
        [BitConverter]::GetBytes([uint32]0x31534345).CopyTo($blob, 0) # BCRYPT_ECDSA_PUBLIC_P256_MAGIC
        [BitConverter]::GetBytes([uint32]32).CopyTo($blob, 4)
        [Array]::Copy($point, 1, $blob, 8, 32)
        [Array]::Copy($point, 33, $blob, 40, 32)
        $publicCng = [System.Security.Cryptography.CngKey]::Import(
            $blob,
            [System.Security.Cryptography.CngKeyBlobFormat]::EccPublicBlob
        )
        $publicKey = New-Object System.Security.Cryptography.ECDsaCng($publicCng)
    }
    catch {
        return @{ Checked = $true; Ok = $false; Reason = '证书公钥不是 P-256 EC 公钥' }
    }

    try {
        if ($publicKey.KeySize -ne 256) {
            return @{ Checked = $true; Ok = $false; Reason = "证书不是 P-256（$($publicKey.KeySize) 位）" }
        }
        $challenge = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($challenge)
        $signature = $privateKey.SignHash($challenge)
        if (-not $publicKey.VerifyHash($challenge, $signature)) {
            return @{ Checked = $true; Ok = $false; Reason = '私钥与证书不匹配（签名自检失败）' }
        }
        return @{ Checked = $true; Ok = $true; Reason = '私钥与证书配对正确，证书是 P-256' }
    }
    finally {
        if ($publicKey) { $publicKey.Dispose() }
        if ($privateKey) { $privateKey.Dispose() }
        if ($publicCng) { $publicCng.Dispose() }
        if ($privateCng) { $privateCng.Dispose() }
    }
}

function Assert-ByteArraySize {
    param(
        [byte[]]$Bytes,
        [string]$Label
    )

    if ($Bytes.Length -eq 0) { throw "$Label 是空文件" }
    if ($Bytes.Length -gt $MaxFileBytes) {
        throw "$Label 有 $($Bytes.Length) 字节，超过 $MaxFileBytes 字节上限——大概率选错了文件"
    }
}

# ------------------------------------------------------------------------ 主流程

Write-Host ''
Write-Host '安装本地 MFi 配件身份'
Write-Host '--------------------------------------------------------------'

$source =
    if ($FromApk) { Resolve-FromApk -ApkPath $FromApk }
    elseif ($FromDirectory) { Resolve-FromDirectory -Directory $FromDirectory }
    else { Resolve-Automatically }

Assert-ByteArraySize -Bytes $source.Key -Label $IdentityName
Assert-ByteArraySize -Bytes $source.Certificate -Label $CertificateName
Write-Step "$IdentityName：$($source.Key.Length) 字节"
Write-Step "$CertificateName：$($source.Certificate.Length) 字节"

$pairing = Test-IdentityPairing -KeyBytes $source.Key -CertificateBytes $source.Certificate
if ($pairing.Checked) {
    if (-not $pairing.Ok) { throw "身份校验失败：$($pairing.Reason)" }
    Write-Step "配对自检：$($pairing.Reason)"
}
else {
    Write-Warning $pairing.Reason
    Write-Warning '没有自检不等于没问题：配对错误会在 iPhone 那边表现为没有任何提示的认证失败。'
}

if (Test-Path -LiteralPath $DestinationDirectory) {
    if (-not $Force) {
        throw @"
目标目录已存在：
    $DestinationDirectory

确认要替换就加 -Force。
"@
    }
    Write-Step "目标目录已存在，-Force 生效，覆盖它"
}

if (-not $PSCmdlet.ShouldProcess($DestinationDirectory, '写入 MFi 配件身份')) {
    Write-Host '已按 -WhatIf 跳过写入。'
    return
}

New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $DestinationDirectory $IdentityName), $source.Key)
[System.IO.File]::WriteAllBytes((Join-Path $DestinationDirectory $CertificateName), $source.Certificate)
Write-Step "已写入 $DestinationDirectory"

Write-Host ''
Write-Host '接下来'
Write-Host '--------------------------------------------------------------'
Write-Host '  1. 构建：  .\gradlew :car:app:assembleDebug'
Write-Host '  2. 设置页把"认证方式"选为"本机软件认证"（默认已经是它）'
Write-Host '  3. 也可以在设置页直接导入别的身份，优先级高于随包内置的这一份'
Write-Host ''
Write-Host '提醒'
Write-Host '--------------------------------------------------------------'
Write-Host '  assets/offline-mfi/ 与 *.pk8 已在 .gitignore 中排除。'
Write-Host '  这份身份属于它的合法持有者，不要提交进仓库，也不要随公开发行版分发。'
Write-Host ''

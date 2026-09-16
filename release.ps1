<#
    release.ps1 —— 发版唯一入口

    用法：powershell -ExecutionPolicy Bypass -File release.ps1 0.2.0
          不传版本号 = 按 gradle.properties 里已有的号原样打包（首次发布走这条）
    自测装机（不发布）：加 -DebugSign

    为什么要有这个脚本：版本号原先靠"记得传 -PversionName"来保证。漏一次就会发出一个
    自称旧版本的包 —— Gitee 的 tag 已经指到新版本，于是所有用户被提示"有更新"、装完
    号还是没变、每次冷启动重新弹一遍。而更新是唯一还能修好其他 bug 的通道。
    这里把「改号 → 跑门禁 → R8 打包 → 收产物 → 打印发布步骤」钉成一条不可拆的序列，
    tag 与包内版本号就不可能对不上。

    改号与打包之间是**独立的 Gradle 调用**：stampVersion 写进 gradle.properties 的值，
    同一趟配置阶段读不到，必须新起一次构建。
#>
param(
    [Parameter(Position = 0)][string]$Version = '',
    [switch]$DebugSign
)

$ErrorActionPreference = 'Stop'
# PS 5.1 往管道里写东西时按控制台码页（本机 OEM 936）编码，中文提示到重定向文件里就是乱码。
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$bump = [bool]$Version
if ($bump) {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        Write-Host "版本号必须是三段数字（例如 0.2.0），收到的是：$Version" -ForegroundColor Red
        exit 2
    }
} else {
    # 不传号 = 首发场景：gradle.properties 里已经写好了要发的号，没有"上一个版本"可比，
    # 也就没有任何东西需要递增 —— 强行要求传号只会逼人先手动改回去。
    $Version = (Get-Content -Path 'gradle.properties' -Encoding UTF8 |
        Where-Object { $_.StartsWith('VERSION_NAME=') }) -replace '^VERSION_NAME=', ''
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        Write-Host "gradle.properties 里的 VERSION_NAME 不成样（$Version），要么修它要么传个新号" -ForegroundColor Red
        exit 2
    }
    Write-Host "不改号，按当前 VERSION_NAME=$Version 原样打包" -ForegroundColor DarkGray
}

# Java properties 里冒号是转义过的（sdk.dir=D\:/AndroidSDK），读出来要还原
function Read-LocalProperty([string]$key) {
    $file = Join-Path $root 'local.properties'
    if (-not (Test-Path $file)) { return $null }
    foreach ($line in Get-Content -Path $file -Encoding UTF8) {
        if ($line.StartsWith("$key=")) {
            return ($line.Substring($key.Length + 1).Trim() -replace '\\:', ':') -replace '\\\\', '\'
        }
    }
    return $null
}

# 工具链：build.cmd / release.cmd 那种写着绝对路径的包装脚本不入库（换机器即失效），
# 所以这里自己找 JDK —— local.properties 的 buaa.jdk.home > 环境变量 > SDK 下的 jdk-*。
$sdk = Read-LocalProperty 'sdk.dir'
$jdk = Read-LocalProperty 'buaa.jdk.home'
if (-not $jdk -and $env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { $jdk = $env:JAVA_HOME }
if (-not $jdk -and $sdk -and (Test-Path $sdk)) {
    $candidate = Get-ChildItem -Path $sdk -Directory -Filter 'jdk*' | Sort-Object {
        [int](($_.Name -replace '\D', '') | Select-Object -First 1)
    } -Descending | Select-Object -First 1
    if ($candidate) { $jdk = $candidate.FullName }
}
if (-not $jdk) {
    Write-Host "找不到 JDK：设 JAVA_HOME，或在 local.properties 里加 buaa.jdk.home=<jdk 目录>" -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "JDK: $env:JAVA_HOME" -ForegroundColor DarkGray

$gradlew = Join-Path $root 'gradlew.bat'

function Invoke-Build {
    param([string[]]$Tasks, [string]$Label)
    Write-Host ''
    Write-Host "== $Label ==" -ForegroundColor Cyan
    & $gradlew @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "$Label 失败（退出码 $LASTEXITCODE）。修好再发，不要带着红继续往下走。" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

$total = if ($bump) { 3 } else { 2 }
if ($bump) {
    Invoke-Build @(':app:stampVersion', "-PreleaseVersion=$Version") "1/$total 写入版本号（VERSION_CODE 自动 +1）"
    Invoke-Build @(':app:testDebugUnitTest', ':app:lintDebug') "2/$total 单测 + lint"
} else {
    Invoke-Build @(':app:testDebugUnitTest', ':app:lintDebug') "1/$total 单测 + lint"
}

$package = @(':app:releasePackage')
if ($DebugSign) { $package += '-PdebugSign' }
Invoke-Build $package "$total/$total R8 打包 + 收产物"

# 两道"意图核对"。`releasePackage` 已经核过"包内版本 == gradle.properties"，但它核不了
# "gradle.properties == 你这次要发的那个号"——改号那一步静默失效时（本项目就踩过：
# 分支条件写成 if ($Bump)，而脚本里没有这个参数），它照样全绿。而后果是全机弹窗循环。
$stampNow = (Get-Content -Path (Join-Path $root 'gradle.properties') -Encoding UTF8 |
    Where-Object { $_.StartsWith('VERSION_NAME=') }) -replace '^VERSION_NAME=', ''
if ($stampNow -ne $Version) {
    Write-Host "gradle.properties 里现在是 $stampNow，这次要发的却是 $Version：改号那步没生效。" -ForegroundColor Red
    Write-Host "别去打 tag —— 先看上面 1/$total 那一步的输出。" -ForegroundColor Red
    exit 1
}
$artifact = Join-Path $root "dist\buaa-schedule-$Version.apk"
if (-not (Test-Path $artifact)) {
    Write-Host "产物不在 $artifact —— 手里没有包就别去发布。" -ForegroundColor Red
    exit 1
}
Write-Host "意图核对通过：$Version / $(Split-Path -Leaf $artifact)（$([math]::Round((Get-Item $artifact).Length / 1MB, 2)) MB）" -ForegroundColor DarkGray

Write-Host ''
if ($DebugSign) {
    Write-Host "调试密钥自签的包已生成：只可装机自测，**不要**发到 Gitee（换回正式密钥时用户必须先卸载，课表会一起没）。" -ForegroundColor Yellow
} else {
    Write-Host "dist\buaa-schedule-$Version.apk 已就绪，按上面打印的步骤发到 Gitee。" -ForegroundColor Green
}
Write-Host "别忘了：gradle.properties 的两行版本号改动要与 tag v$Version 一起提交。" -ForegroundColor Green

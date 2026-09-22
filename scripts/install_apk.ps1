# 把编译好的 APK 装到 USB 连接的真机上。
# 用法：powershell -ExecutionPolicy Bypass -File scripts\install_apk.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$adb  = "C:\Users\Jehia\android-build\sdk\platform-tools\adb.exe"
$apk  = Join-Path $root "output\我的课表-v1.0-debug.apk"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "找不到 adb：$adb（便携工具链可能被删掉了，需要重新准备）"
}
if (-not (Test-Path -LiteralPath $apk)) {
    throw "找不到 APK：$apk（先跑一次 gradle assembleDebug）"
}

& $adb start-server | Out-Null

$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host "没有检测到已授权的设备。请检查：" -ForegroundColor Yellow
    Write-Host "  1. 手机用数据线连上电脑（选「传输文件」，不要只充电）"
    Write-Host "  2. 设置 → 关于手机 → 连点「版本号」7 次，打开开发者选项"
    Write-Host "  3. 开发者选项 → 打开「USB 调试」，手机上弹出的授权框点「允许」"
    exit 1
}

Write-Host "已连接设备：" -ForegroundColor Green
$devices | ForEach-Object { Write-Host "  $_" }

& $adb install -r -d $apk
if ($LASTEXITCODE -ne 0) {
    throw "安装失败，退出码 $LASTEXITCODE"
}

Write-Host ""
Write-Host "安装完成。接下来在手机上：" -ForegroundColor Green
Write-Host "  1. 打开「我的课表」，点「设置开学日期」，选开学那一周的任意一天"
Write-Host "  2. 回到桌面，长按空白处 → 小组件 → 找到「我的课表」"
Write-Host "  3. 把「今日课程」或「本周课表」拖到桌面上"

& $adb shell am start -n com.jehia.schedulewidget/.MainActivity | Out-Null

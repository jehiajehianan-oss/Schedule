# 我的课表 · 荣耀 MagicOS 桌面小组件

从爬到的课表网页生成两个桌面小组件，纯个人自用，不联网、不要权限。

- **今日课程**（默认 4x2）：一张卡回答「现在上什么课」——大字号课名 + 时间 + 教室 + 教师，
  正在上课时另给剩余时间和进度条，右侧一行预告下一节；没课就整块降成灰阶
- **本周课表**（默认 5x5）：周一到周日整张网格，今天那一列高亮，同一门课同一个颜色

## 数据从哪来

```
data/course.html
  └─ python coursetable/export_excel.py           → output/我的课程表.xlsx
       └─ python scripts/export_widget_data.py
            ├─ widget/app/src/main/assets/schedule.json   小组件读的数据（随 APK 打包）

```

`output\我的课程表.xlsx` 里 `1-9`、`1-3、8-15、06`、`13-15单、07、09` 这类周次文本，
会被展开成 `weekList`（如 `[7, 9, 13, 15]`）写进 JSON，小组件直接按周过滤，单双周也算好了。



## 编译成 APK

需要 JDK 17 + Android SDK。二选一：

**A. Android Studio（省事，推荐）**

1. 装 Android Studio（自带 JDK 和 SDK）
2. `File → Open` 选这个 `widget` 目录，等 Gradle 同步完
3. 连着手机点 Run，或者 `Build → Build APK(s)`

产物在 `widget/app/build/outputs/apk/debug/app-debug.apk`。

**B. 命令行**

```powershell
# 装 JDK 17 和 Android 命令行工具（winget 会写系统注册表，介意就改用便携版 zip）
winget install Microsoft.OpenJDK.17

$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager" `
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd widget
gradle wrapper --gradle-version 8.7   # 仓库里没有 wrapper 的 jar，先生成一次
.\gradlew assembleDebug
```

仓库里没有 `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` 是因为 wrapper 的 jar 是二进制文件，
没法直接写进仓库；用 Android Studio 打开时它会自动补上，命令行则先跑一次 `gradle wrapper`。

## 装到荣耀手机

- 数据线：`adb install -r app-debug.apk`（手机先开「开发者选项 → USB 调试」）
- 或者把 APK 传到手机，用文件管理器点开安装（需要允许安装未知来源应用）

## 添加小组件

长按桌面空白处 → 小组件（部分 MagicOS 版本在「桌面和壁纸 → 小组件」）→ 找到「我的课表」→
把「今日课程」或「本周课表」拖到桌面。可以自由拉大拉小。

## 首次使用

1. 打开「我的课表」，点「设置开学日期」，选**开学那一周的任意一天**（脚本会自动对齐到那周的周一）
2. 小组件马上变成当前第几周；以后点小组件都能回到这个界面

## 换学期 / 换课表

- 电脑上重跑：`python coursetable\export_excel.py` → `python scripts\export_widget_data.py`
- 手机上**不用重装 APK**：把新生成的 `schedule.json` 传到手机 → App 里点「导入课表数据」选中它
- 想退回 APK 内置那份，点「恢复内置数据」

## 想改的东西在哪

| 想改什么                 | 改哪里                                                                            |
| ------------------------ | --------------------------------------------------------------------------------- |
| 每节课几点上（作息时间） | `scripts/export_widget_data.py` 里的 `SECTION_TIMES`，改完重跑导出            |
| 面板透明度 / 圆角        | `res/drawable/widget_bg.xml`；预览页里对应 `--panel`                          |
| 文字色与强调色           | `res/values/colors.xml`；预览页里对应 `:root` 变量                            |
| 课程方块颜色             | `WidgetCommon.blockFill` / `blockText`；预览页里对应 JS 的 `fill` / `ink` |
| 是否跟随系统深色模式     | `WidgetCommon.FOLLOW_SYSTEM_DARK`，默认 false（壁纸固定，组件也固定）           |
| 「下一节」什么时候显示   | `TodayWidgetProvider.MIN_HEIGHT_FOR_NEXT`（默认 168dp）                         |
| 预览页垫的壁纸           | `scripts/export_widget_data.py` 里的 `WALLPAPER_RELATIVE`                     |
| 小组件默认尺寸           | `res/xml/widget_today_info.xml`、`res/xml/widget_week_info.xml`               |
| 周视图最多显示到第几节   | `WeekWidgetProvider.MAX_SECTION`                                                |
| 小组件里想显示老师       | `TodayWidgetProvider.metaOf`（教室 · 教师 · 节次都在这一行）                  |

## 已知限制

- 同一格里出现两门课时只显示一门。课表里的这类重叠其实都是「周次互补」的安排
  （例如周二 6-7 节：「模拟电子技术」是 7、9、13-15 单周，「形势与政策(3)」只有第 10 周），
  按当前周过滤后不会真的同时出现。
- 周视图格子太小的话课名会被裁掉，把小组件往大拉一点。
- 小组件靠系统按 `updatePeriodMillis` 刷新，最小值 30 分钟，所以跨天/跨周最多晚半小时；也可以在 App 里点「刷新小组件」。
- 课表数据跟着 APK 走，除非在 App 里用「导入课表数据」。

## 数据校验

课表数据有两条校验链路，改完 `data/course.html` 后建议都跑一遍：

- `python coursetable\export_excel.py`：导出时检查重复排课与时间冲突，有问题会打印出来并写进 xlsx 的「检查」表
- `python scripts\export_widget_data.py`：导出小组件数据前跑同一套检查，发现重复排课/时间冲突就中止，
  不动任何文件；确认无误可以加 `--force` 强制导出
- `python scripts\verify_schedule.py`：用另一套正则解析独立复核 `data/course.html` 与 xlsx 是否一致

当前结果：19 条课程、0 处重复排课、0 处时间冲突、6 处「周次互补」（时段相交但周次错开，属正常安排）。

## 验证情况

这份代码是在**没有 JDK / Android SDK** 的机器上写的，做过这些静态检查：

- 8 个 Java 文件全部能被 Java 语法解析器解析通过
- Java 里引用的每个 `R.layout/id/drawable/color/string` 都在 `res/` 里存在（0 问题）
- 17 个 XML 文件全部良构，Manifest 里的资源引用也都能解析
- `schedule.json` 数据结构、周次展开（含单双周）、预览页数据内嵌都已核对

但**没有真机编译过**。



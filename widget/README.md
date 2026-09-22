# 我的课表 · 荣耀 MagicOS 桌面小组件

从爬到的课表网页生成两个桌面小组件，纯个人自用，不联网、不要权限（权限列表是空的）。

- **今日课程**（默认 4x2）：一张卡回答「现在上什么课」——大字号课名 + 时间 + 教室 + 教师，
  正在上课时给剩余时间和 3dp 进度条，下面一行预告下一节；没课就整块降成灰阶
- **本周课表**（默认 5x5）：周一到周日整张网格，今天那一列整列铺淡强调色 + 表头胶囊，
  同一门课同一个颜色，跨多节的课连成一整块

这一版相比第一版改掉的：

| 改动 | 说明 |
| --- | --- |
| 面板近实色 | 浅色 `#F2FFFFFF` 左右、深色约 92% 墨色，22dp 圆角；68% 白压在绿壁纸上会发灰发绿 |
| 手工色卡 | 出厂四套各 10 对 `fill/ink`（默认 / 莫兰迪 / 马卡龙 / 明快），对比度都 ≥4.5:1，**真源只在 `scripts/export_widget_data.py` 的 `PALETTES`**；删掉了按 `hashCode()` 随机取色相；App 里可换色卡、编自定义色卡、逐课改色 |
| 课块连成整块 | 首格 / 中格 / 尾格 / 单格四种圆角资源，缝只留在整块外侧；课名只写首格、最多 2 行 |
| 时间列 | 左侧显示「节次序号 + 该节开始时间」两行 9sp，节次行之间 1px 细分隔线，表头行加高 |
| 今日组件排版 | 内容区 `weight=1` 垂直居中吃掉 4×3 的空白，NEXT 贴底；竖条 4dp 圆角用课程自己的颜色；进度条 3dp |
| 浅色 / 深色 | 跟随系统可选，默认浅色（壁纸是亮调） |
| App 增删改 | 课程 / 考试 / 作息时间 / 学期周数 / 期末周 / 主题都能在 App 里改 |
| 期末周 | 期末周里「今日课程」切成考试优先，「本周课表」整块换成考试表 |
| 刷新时机 | `AlarmManager` 在「下一节开始 / 当前课结束 / 上课中每分钟」精确翻页，**不加任何权限** |

## 数据从哪来

```
data/course.html
  └─ python assets/coursetable/export_excel.py    → output/我的课程表.xlsx
       └─ python scripts/export_widget_data.py
            ├─ widget/app/src/main/assets/schedule.json   小组件读的出厂数据（随 APK 打包）
            ├─ widget/app/src/main/res/drawable/block_*.xml  30 个课块圆角资源
            └─ output/course_card.html                    离线预览页
```

`output\我的课程表.xlsx` 里 `1-9`、`1-3、8-15、06`、`13-15单、07、09` 这类周次文本，
会被展开成 `weekList`（如 `[7, 9, 13, 15]`）写进 JSON，小组件按周过滤，单双周也算好了。
App 里改周次时反过来做：由勾选的周次生成同样语法的 `weeks` 文本（见 `Weeks.java`），
和 `coursetable/schedule.py` 的 `expand_weeks` 语义一致，来回转换不丢信息。

`schedule.json` 现在的字段：`version`、`semesterStart`、`generatedAt`、`sectionTimes`、
`palettes`（四套出厂色卡）、`paletteId`（当前选中的一套）、`courseColors`（当前色卡展开，兼容老数据）、
`semesterWeeks`、`finalsStartWeek`、`exams`、`courses`。
`courses` 每条含 `name / weekday / start / end / teacher / room / weeks / weekList / id / color / colorHex`
（`colorHex` 是逐课自定义颜色，空字符串 = 跟色卡走）。
## 两个小组件长什么样

**今日课程**按当前时间分状态，右上角的状态文字是实心强调色胶囊（白字 10sp）：

| 状态 | 卡片内容 |
| --- | --- |
| 在课 | 课名大字 + 教室/教师/节次 + 「上课中 · N 分钟后下课」+ 3dp 进度条；NEXT 行是下一节 |
| 课间 | 主角换成下一节，胶囊写「下一节 · N 分钟后上课」 |
| 今天没课 | 整块降成灰阶，隐藏色条和胶囊，只留一句说明 |
| 已上完 | 当天的课上完了 |
| 期末周 | 考试优先：当天有考试就显示考试名 / 时间 / 地点和进行中进度；没有就「下一场考试 · X月X日 · 还有 N 天」，NEXT 行显示下一场考试 |

**本周课表**是周一到周日整张网格：左侧时间列两行（节次序号 + 开始时间），节次行之间有 1px 细分隔线，
表头行加高；今天那一列整列铺一层可见的淡强调色，表头做成强调色胶囊；课块按在格子里的位置
（单格 / 首格 / 中格 / 尾格）切换圆角资源，跨多节的课连成一整块。期末周里整块换成按日期分组的考试表。

## 先看效果（不用编译）

`output/course_card.html` 是预览页，直接双击用浏览器打开就能看，也可以
`python scripts\preview_on_phone.py` 用手机看（它服务的是整个项目目录，
因为预览页里的壁纸是相对路径引用的）。

页面上有三个页签：**今日课程 / 本周课表 / 视觉规范**；可以直接选星期、周次、时间、主题，
切 4×2 / 2×2 / 4×3 尺寸，看「在课 / 课间 / 已上完 / 今天没课 / 期末周」各种状态长什么样。
配色、圆角、字号跟小组件一一对应：预览页读的是 `export_widget_data.py` 灌进去的同一份
`palettes` / `paletteId` 和同一套版式参数，**预览页看到什么，手机上就是什么**。

改完设计跑 `python scripts\shot_widget.py`，出图到 `output\预览图\`（含
`今日组件-全矩阵.png`：三种尺寸 × 五种状态）。

设计依据（壁纸分析、三种方案、信息层级、对比度实测）见 `docs/桌面设计方案.md`。
## 编译成 APK

需要 JDK 17 + Android SDK。二选一：

**A. Android Studio（省事，推荐）**

1. 装 Android Studio（自带 JDK 和 SDK）
2. `File → Open` 选这个 `widget` 目录，等 Gradle 同步完
3. 连着手机点 Run，或者 `Build → Build APK(s)`

产物在 `widget/app/build/outputs/apk/debug/app-debug.apk`。

**B. 命令行**

本机已经准备好一套便携工具链（`C:\Users\Jehia\android-build\`），离线就能编，不用联网：

```powershell
$env:JAVA_HOME = 'C:\Users\Jehia\android-build\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\Users\Jehia\android-build\sdk'
cd 'C:\Users\Jehia\Desktop\课程爬取\widget'
& 'C:\Users\Jehia\android-build\gradle\gradle-8.7\bin\gradle.bat' --offline assembleDebug assembleRelease
```

换台电脑就用仓库自带的 `gradlew`（已带 `gradle-wrapper.jar`），补一个 `widget/local.properties`
指向本机 SDK 即可：

```properties
sdk.dir=C:/你的/SDK/路径
```

`local.properties` 是机器专属文件，已经被 `.gitignore` 排除，不会跟着仓库走。

另外两处配置是**为了在这台机器上能编过、不影响功能**：

- `settings.gradle` 里把阿里云镜像放在 `google()` 前面 —— `dl.google.com` 在不少国内网络下不通，
  直接用官方源会先卡到超时
- `gradle.properties` 里的 `android.overridePathCheck=true` —— AGP 默认拒绝在含中文的路径下构建，
  而这个项目就在 `课程爬取\` 下

## 装到荣耀手机

- 一行装（推荐）：`powershell -ExecutionPolicy Bypass -File scripts\install_apk.ps1`
  （脚本用 `C:\Users\Jehia\android-build\sdk\platform-tools\adb.exe` 装 `output\我的课表-v1.0-debug.apk`）
- 手动：`adb install -r app-debug.apk`（手机先开「开发者选项 → USB 调试」）
- 或者把 APK 传到手机，用文件管理器点开安装（需要允许安装未知来源应用）

## 添加小组件

长按桌面空白处 → 小组件（部分 MagicOS 版本在「桌面和壁纸 → 小组件」）→ 找到「我的课表」→
把「今日课程」或「本周课表」拖到桌面。可以自由拉大拉小。

## 首次使用

1. 打开「我的课表」，点「开学日期」，选**开学那一周的任意一天**（会自动对齐到那周的周一）
2. 小组件马上变成当前第几周；以后点小组件都能回到这个界面

**首次添加小组件前要先设好开学日期**，否则组件只会显示提示。
## App 里能改什么

打开 App 就是课表管理界面。顶上一行显示当前第几周、开学日期，以及**当前用：自定义数据 / 内置数据**。

- **课程列表**：按星期分组，点一条进编辑器、长按弹确认框删掉。「＋ 添加课程」同理。
  编辑器字段：课名、星期、起始节次、结束节次、周次、地点、老师、颜色。
- **周次编辑器**：1..学期总周数 的 chips 多选，下面是快捷按钮「全选 / 清空 / 单周 / 双周 / 连续段」。
  保存时由 `Weeks.format()` 反推出 `weeks` 文本（连续 3 周以上写成 `1-9`，零散的单双周归并成 `13-15单` 这类），
  语法与 `coursetable/schedule.py` 的 `expand_weeks` 一致。
- **颜色**：一排色板色块，点一个就用那个颜色；默认「自动」＝按课名 hash 稳定取色板下标，
  写进 JSON 的 `color` 字段（-1 表示自动）。
- **设置**：开学日期（DatePicker）、学期总周数（默认 20）、期末周起始周（0 = 关闭）、
  主题（浅色 / 深色 / 跟随系统，默认浅色，切换后 Activity 立刻 `recreate()`）。
- **作息时间**：12 节的开始 / 结束时间各一个 TimePicker，改完存进 `schedule.json` 的 `sectionTimes`，
  不用再手改 Python 里的 `SECTION_TIMES`。
- **考试**：期末考试列表，DatePicker + TimePicker 增删改，存 `schedule.json` 的 `exams`。
- **导入课表数据**：选一个 JSON 导进来，**向后兼容旧格式**（缺 `id` / `color` / `exams` / `courseColors`
  时自动补默认值，旧版只有 `courses` 的 JSON 也能读）。
- **恢复内置数据**：先弹确认框说明「会清空自定义课程与考试，回到 APK 内置那份」，
  确认后才删掉 `filesDir/schedule.json`。**这个按钮会删一个文件**，弹窗里写明了。

### 数据是两层的

- `assets/schedule.json` —— 出厂数据，随 APK 打包，只读
- `filesDir/schedule.json` —— 用户数据，存在时优先读

第一次编辑时把出厂数据整份复制到 `filesDir` 再改；之后所有增删改都写这一份。
顶部那行「当前用：…」就是告诉你现在读的是哪一份。

## 期末周 = 考试表

- 配置：学期总周数 + 期末周起始周（0 = 未启用）→ 期末周区间 = `[起始周, 总周数]`
- **今日课程**：期末周内考试优先。当天有考试就显示考试名 / 时间 / 地点，进行中给进度条和剩余时间；
  当天没有考试就显示「下一场考试 · X月X日 · 还有 N 天」，NEXT 行换成「下一场」
- **本周课表**：期末周内整块切成该周的考试表，按日期分组，不再显示常规课程

## 刷新时机

`updatePeriodMillis = 1800000`（30 分钟，系统下限）仍然保留当兜底，另外用 `AlarmManager`
在关键时刻各排一次一次性闹钟精确翻页：

- **下一节课开始** —— 到点翻到「在课」
- **当前课结束** —— 到点翻到「下一节」
- **上课期间** —— `setAndAllowWhileIdle` 每 1 分钟滚一次，下课即停
- **当天没课了** —— 跨天 00:00:10 翻页（顺带刷新日期 / 周次）

要点：

- **不用** `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`，权限列表保持为空。代价是低电耗模式下
  系统可能把触发时间浮动几分钟，这是刻意的取舍
- 排闹钟是幂等的：每次先 `cancel` 同一个 `PendingIntent`（固定 requestCode）再 `set`，重复触发不叠加
- 桌面上没有小组件时不排闹钟；`onDeleted` / `onDisabled` 里取消
## 想改的东西在哪

| 想改什么 | 改哪里 |
| --- | --- |
| 课程配色（四套出厂色卡，各 10 对 `fill/ink`） | `scripts/export_widget_data.py` 的 `PALETTES`（**唯一真源**）。重跑脚本会写进 `schedule.json` 的 `palettes` / `paletteId`，**并重新生成 30 个 `block_*.xml` 与 4 个 `block_shape_*.xml`**；Java 兜底在 `Palette.BUILTIN_FILL/BUILTIN_INK`，两边漂移脚本会直接报错 |
| 用哪一套出厂色卡 | App 顶部「色卡」按钮（写 `paletteId`）；四套定义在 `export_widget_data.py` 的 `PALETTES` |
| 自定义色卡的十个颜色 | App「色卡 → 编辑自定义…」逐个格子改色，存 `palettes` 里 `id = "custom"` 的一套 |
| 某一门课用哪个颜色 | App 里编辑该课程点色块：`0` = 自动（按课名 hash 取色卡下标）、`1..10` = 当前色卡的下标（写 `color`）、`11` = 自定义（写 `colorHex`，压过色卡） |
| 课块颜色怎么上到 View 上 | `Palette.tintSupported()` 运行时判定：支持就走 `ImageView` + 纯白圆角 + `setColorFilter`（任意颜色精确生效），不支持回落 `block_<下标>_*.xml` 取最接近的出厂色 |
| 每节课几点上（作息时间） | App 里「作息时间」（TimePicker），存 `schedule.json` 的 `sectionTimes`；出厂默认在 `scripts/export_widget_data.py` 的 `SECTION_TIMES` |
| 面板透明度 / 圆角 | `res/drawable/widget_bg.xml`（浅色）/ `widget_bg_dark.xml`（深色）；预览页里对应 `--panel` |
| 文字色与强调色 | `res/values/colors.xml`；预览页里对应 `:root` 变量 |
| 浅色 / 深色 / 跟随系统 | 默认值在 `Prefs.getTheme()`（默认浅色），App 里「主题」按钮切换；组件按 `WidgetCommon.isNight()` 分主题取色 |
| 今天那一列的浓度 / 表头胶囊 | `WeekWidgetProvider` 里 today band 的透明度、`res/drawable/header_pill.xml`；预览页里有同一份数值 |
| 课块圆角半径 | `scripts/export_widget_data.py` 的 `write_block_drawables()`，重跑脚本覆盖 `block_*.xml` |
| 「下一节」什么时候显示 | `TodayWidgetProvider.MIN_HEIGHT_FOR_NEXT`（默认 168dp） |
| 周视图最多显示到第几节 | `WeekWidgetProvider.MAX_SECTION`（12） |
| 期末周区间 | App 里「期末周」设置（写 `finalsStartWeek`）+「学期周数」；0 = 关闭 |
| 刷新闹钟的时刻 | `WidgetAlarm.nextFireAt` |
| 预览页垫的壁纸 | `scripts/export_widget_data.py` 里的 `WALLPAPER_RELATIVE` |
| 小组件默认尺寸 | `res/xml/widget_today_info.xml`、`res/xml/widget_week_info.xml` |
| 小组件里想显示老师 | `TodayWidgetProvider.metaOf`（教室 · 教师 · 节次都在这一行） |
| App 里那些弹窗长什么样 | `res/layout/activity_main.xml`、`dialog_course.xml`、`dialog_exam.xml`、`dialog_section_times.xml` |
## 色卡与自定义颜色

配色分三层，优先级从高到低：

1. **逐课自定义颜色** —— 课程对象的 `colorHex`（`#RRGGBB`）。编辑课程时点最后一个色块「自定义」，
   用 RGB 滑杆 / hex 输入挑色，实时预览；指定了就压过色卡，文字色由 `Palette.autoInk()`
   按对比度在纯白 / 纯黑里自动挑。
2. **自定义色卡** —— App 顶部「色卡」按钮 →「编辑自定义…」→ 逐个格子改色，存进 `schedule.json`
   的 `palettes` 里 `id = "custom"` 那一套。
3. **出厂四套色卡** —— App 里一键切换（写 `paletteId`）：

   | id | 名字 | 风格 | 文字 |
   | --- | --- | --- | --- |
   | `default` | 默认 | 饱和深色块 | 8 色白字 + 2 色深字（金、蓝灰） |
   | `morandi` | 莫兰迪 | 低饱和灰调 | 白字 |
   | `macaron` | 马卡龙 | 浅粉彩底 | 深字 `#2E241C` |
   | `bright` | 明快 | 高饱和亮底 | 深字 `#0B1220` |

四套色卡定义在 `scripts/export_widget_data.py` 的 `PALETTES`（唯一真源）。预览页有可点的色板切换条，
`output\预览图\色卡对比.png` 是同一张课表只换配色的四联图。

**颜色怎么上到 View 上**：RemoteViews 没法「运行时颜色 + 圆角」同时上，所以分成两条路，
App 启动时用反射判定（`Palette.tintSupported()` 查 `ImageView.setColorFilter(int)` 有没有
`@RemotableViewMethod`）：

- **tint 路径（正常 ROM）**：`ImageView` 铺一张**纯白的圆角 shape**，颜色用 `setColorFilter()`
  在运行时下发 —— 任意颜色都能用，圆角仍是静态资源；
- **baked 路径（个别裁剪过白名单的 ROM）**：回落到预生成的 `block_<下标>_*.xml`，
  颜色按加权 RGB 距离取**主色卡里最接近的出厂色**，中格直接 `setBackgroundColor()`；
  底色既然被吸附了，**文字色也跟着吸附后的颜色走**（正好命中出厂色就沿用设计好的那一对 ink，
  否则按对比度现配白 / 黑），不会出现浅色卡被压成深字压深块。

两条路都不需要新权限；真机上到底走哪条，见下面清单第 10 条。

## 已知限制

- 同一格里出现两门课时只显示一门。课表里的这类重叠其实都是「周次互补」的安排
  （例如周二 6-7 节：「模拟电子技术」是 7、9、13-15 单周，「形势与政策(3)」只有第 10 周），
  按当前周过滤后不会真的同时出现。
- 周视图格子太小的话课名还是会被裁掉（最多 2 行省略号），把小组件往大拉一点。
- 翻页靠 `setAndAllowWhileIdle`，低电耗模式下系统可能把触发时间浮动几分钟——这是**不加精确闹钟权限**的代价；
  加上 30 分钟的 `updatePeriodMillis` 兜底，实在没刷新也能在 App 里点「刷新小组件」。
- 个别厂商 ROM 会拦 `setAndAllowWhileIdle`（代码里吞掉了 `SecurityException`），此时只剩 30 分钟兜底。
- 逐课自定义颜色的文字色只在白字 / 黑字里二选一，挑到中灰（`#767676` 上下）两边对比度都可能不到 4.5:1
  —— 换深一点或浅一点即可。
- 不支持运行时色滤镜的 ROM 上，颜色会被吸附到**主色卡那 10 个出厂色里最接近的一个**，
  所以「切色卡」和「逐课自定义色」在那类机器上都只能取近似值；文字色会跟着吸附后的颜色自动配，读得出来。
- RemoteViews 做不了实时模糊和阴影，卡片的浮起感是**外圈 padding + 描边**伪造出来的，不会有真阴影。
- 课表数据跟着 APK 走，除非在 App 里编辑过（写 `filesDir/schedule.json`）或导入过 JSON。

## 数据校验

课表数据有三条校验链路，改完 `data/course.html` 后建议都跑一遍：

- `python assets\coursetable\export_excel.py`：导出时检查重复排课与时间冲突，有问题会打印出来并写进 xlsx 的「检查」表
- `python scripts\export_widget_data.py`：导出前跑同一套检查 + **色板对比度与 Java/Python 一致性自检**，
  发现重复排课 / 时间冲突就中止，不动任何文件；确认无误可以加 `--force` 强制导出
- `python scripts\verify_schedule.py`：用另一套正则解析独立复核 `data/course.html` 与 xlsx 是否一致

当前结果：19 条课程、0 处重复排课、0 处时间冲突、6 处「周次互补」（时段相交但周次错开，属正常安排）。
## 验证情况

**已经真实构建过**（便携工具链：JDK 17 + Gradle 8.7 + AGP 8.5.2 + compileSdk 34 + build-tools 34.0.0，`--offline`）：

- `gradle --offline assembleDebug assembleRelease` → BUILD SUCCESSFUL
- `aapt2 dump permissions` → 权限列表为空（只输出 `package: com.jehia.schedulewidget`）
- `apksigner verify --print-certs` → 通过，CN=Android Debug（release 包也用调试证书签名，个人自用够）
- 无第三方依赖、无原生库；Release 包里的类没有被裁剪

数据与配色：

- 色卡对比度自检：四套出厂色卡各 10 对 `fill/ink` 全部 ≥4.5:1
  （默认最低 4.84:1、莫兰迪 4.68:1、马卡龙 9.72:1、明快 6.28:1）
- 色卡一致性：`export_widget_data.py` 每次导出都会读 `Palette.BUILTIN_FILL/BUILTIN_INK` **逐套比对并复算对比度**
  （`check_palette()`），还会核对 4 张 `block_shape_*.xml` 的圆角半径（`check_shapes()`），不一致直接报错
- 周次往返：对全部 19 门课 + 8 个边界用例验证 `expand_weeks(Weeks.format(x)) == sorted(x)`，0 处不一致
- `python scripts\verify_schedule.py` → 退出码 0，「核对通过」
- 预览页与真机同算法：`output\course_card.html` 与 `shot_widget.py` 出的图共用同一份配色与版式参数

**没有在真机上装过**，厂商系统相关的项见下面的清单。

## 真机上要自己确认

装好后建议按这个顺序看一遍（都是没法在电脑上验证的）：

1. **翻页准不准**：上一节课时看着组件，到下课时分应该自己翻页。若长时间不动，去
   「设置 → 电池 → 应用启动管理」把「我的课表」设成允许后台活动（MagicOS 默认可能收紧）。
   本应用不申请精确闹钟权限，浮动几分钟属预期
2. **小组件四角**：浅色 / 深色两套下有没有被系统裁掉或和桌面圆角打架
3. **深色主题**：壁纸是亮调，深色卡可能显得突兀——不满意就在 App 里切回浅色
4. **2 行课名**：周视图课块拉小到极限时课名是否被裁（会显示省略号，属正常）
5. **编辑弹窗**：窄屏下 chips / 色块 / 时间选择排版是否挤
6. **长按删除** 和 **恢复内置数据** 的确认框文案是否说得清楚
7. **导入旧格式 JSON**：找一份没有 `id` / `color` / `exams` 的旧 `schedule.json` 导一次
8. **期末周**：把「期末周」设成当前周、加一场今天的考试，看今日组件是否切成考试进度、
   周视图是否变成考试表
9. **删掉小组件**后闹钟应该停（不再有无谓唤醒）
10. **色块颜色对不对**：切一套色卡（比如「明快」）看课块有没有跟着变色、字色是不是跟着变深。
    变了 = 走 tint 路径，四套色卡和逐课「自定义」颜色都精确生效；没变（还是深色块）= 这台 ROM
    不支持运行时色滤镜，回落成「主色卡那 10 个色的最接近值」，色卡和自定义色只算近似，属预期
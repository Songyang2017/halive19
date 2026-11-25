# 河南直播·全频道（halive19）

一个极简直播播放器：直接播运营商 CDN 的公开频道地址，绕开原厂 App 被锁在
「默认频道 / 试看」状态机、只能看 CCTV1 的问题。纯 Java、单 Activity、无 AndroidX、无第三方库。

## 分支说明

| 分支 | 适配 | 说明 |
| :--- | :--- | :--- |
| **`main`**（当前） | **Android 10+**（minSdk 21 / targetSdk 34） | 分区存储、免存储权限；清单放 App 私有目录或内置 assets；画面按标准 DAR 自适应 |
| **`android-4x`** | Android 4.4（SDK 19） | 老盒子专用。清单放 `/sdcard/tvhome19/`，并包含这家 Amlogic 播放器的画幅补偿（97.5%×75% 那条经验值） |

两个分支的菜单、按键、播放逻辑相同，差异只在**存储访问**和**画面尺寸计算**。
`android-4x` 的补偿系数是量特定固件得来的，换设备会放大失真，所以只保留在那个分支。

## 为什么需要它

原厂 App 在没有有效机顶盒账号时会被锁进「默认频道 / 试看」状态机：只播
`config.json` 里 `defaultOpenChannelUrl` 指向的那一条 CCTV1 单播流，频道列表不渲染、
上下键与数字键都不生效。我们把 APK 反编译后确认了几个关键点：

| 发现 | 说明 |
| :--- | :--- |
| `BConfig.BOXTYPE` 在 `CommonApplication.init()` 里**写死** `"multicast"` | 改 `user.json` 的 `boxType` 会在启动时被写回 |
| `Channel.getPlayUrl()` 在组播模式下返回 `rtp://232.x.x.x` | 普通 Wi-Fi 收不到组播，换台必失败 |
| `AppConfig.isShowDefaultChannel()` 决定首播 | 为真时强制播默认频道，不进入频道链表 |
| 服务器 `https://yd-live.hnnrtv.com:33766/live/channelListVer` | 实测可正常返回 **206 个频道**（含分类），CDN 各频道 m3u8 均返回 302 可播 |
| `isShowInList` 字段 | 服务端根本没下发该字段，本地的 `false` 只是 Gson 默认值，**不是**鉴权开关 |

也就是说：频道和流都是好的，坏的是原厂 App 的状态机。继续伪造 3A 身份或改它的 UI 判断，
成本高且不可靠；因此改为**直接用同一批运营商 CDN 地址做一个干净的播放器**，
绕开原厂状态机。

## 功能

- **108 个频道**（服务端下发去重后的 ALL 分类清单），全部 `NoCharge`，含央视 1～17、
  CCTV5+/4K、各省卫视、河南卫视高清、河南移动电视、CGTN 系列、4K 频道等。
- 频道清单是一个 JSON（`no` / `name` / `url`），放在 App 私有目录；APK 内置示例清单兜底。
- 遥控器：
  - **上 / 下 / 左 / 右**：播放页里上一台 / 下一台
  - **OK**：打开频道菜单（版式见下一节）
  - **数字键**：直接输入频道号（如 `31` → 河南卫视高清），2 秒内连按有效
  - **Back**：菜单里收起菜单，播放页退出
- 换台时左上角显示 3 秒频道号与名称；播放失败时中央提示，按上下键换台重试。
- 记住上次看的频道与上次停留的栏目；启动时短暂屏蔽按键，避免桌面 OK 键余波误开菜单。
- 纯 Java、单 Activity、`VideoView` + `org.json`，无 AndroidX / 无第三方库；本分支 minSdk 21。

## 频道菜单（照着成熟电视直播 App 做）

`ChannelMenuView.java` 是一个自绘 `View`，替换了最初右侧那条 108 行的 `ListView`：

- **通高窄侧栏**：频道表是屏幕中偏左的一条侧栏，**上下顶到屏幕边缘**、宽度按当前栏目的
  频道名自动算（约 300～460 dp，刚好把名字显示全，放不下才用 `…`），右侧留出大块画面；
- **左栏分类**：全部频道 108 / 央视频道 24 / 河南本地 14 / 卫视频道 38 / 专题频道 20 / 4K 超清 12，
  每项右侧带数量；焦点进入左栏时**整条变蓝**，一眼能看出方向键现在作用在左栏还是列表；
- **右侧单列频道表**：行数按屏幕高度均分（720p 下 11 行，正好铺满上下），
  上下键逐台滚动（带一行位置的补间），右侧有细滚动条，
  头部是「栏目名 · 共 N 个 · 当前位置 13/108」；
  - 选中行是**白色胶囊 + 深色文字 + 投影**；
  - **正在播的频道**带橙色竖条，未选中时行尾再加一个橙点；
  - 频道号右对齐到固定列宽、名称过长 `…` 省略、行间有细分隔线，整列看起来很整齐；
- **顶部**：右上角时间与「11月25日 周三」，带阴影，压在亮画面上也清楚；
- **左下角**：侧栏空出来的位置放「正在播放 13 中央新闻」和两行按键提示，不再压在频道表上；
- **按键**：
  - `↑ ↓` 逐台滚动选择（与播放页的换台方向一致）；
  - `←` 进左栏分类，`↑ ↓` 切栏目（切换时自动定位到正在播的频道），`→` / `OK` 回到列表；
  - `OK` 播放并收起；`BACK` 收起（焦点在左栏时先退回列表）；
  - 菜单记住上次停留的栏目（`SharedPreferences` 的 `cat`）；
  - 菜单打开时按数字键，顶部弹白色「频道号 xx」卡片，2 秒后自动跳转播放；
- 滚动时 `animShift` 按 0.62 衰减做短补间，换栏目是直接重排（不滑动）。

> 踩坑记录：自定义常量**不要**取名 `VISIBLE`，它会把 `View.VISIBLE`（0）遮蔽掉，
> `setVisibility(VISIBLE)` 实际传进去是 8（`GONE`），菜单会「打开了却什么都不显示」。
> 这里已改名为 `ROWS`。

## 画面比例（4:3 老频道）

有的频道是标清 4:3（例如 `308 中学生`，编码 720x576），原生 `VideoView` 会按视频宽高比
把自己缩成左上角一小块，四周全黑。`TvVideoView` 自己算尺寸解决：

- **自适应（默认）**：保持原始比例，**上下铺满屏幕、左右居中**（4:3 频道左右留黑边），
  16:9 频道正好满屏；
- 这台机器的播放器对非 16:9 画面有个固定行为：把画面硬塞进「97.5% 宽 × 75% 高」的矩形里，
  所以直接铺满会被横向拉宽（看着像是被放大、变形）。`TvVideoView` 按这个规律反推视图尺寸
  （`h = 屏高/0.75`、`w = 屏高 × 显示比例 / 0.975`；标清 720x576 按 DAR 4:3 计算），塞完之后的画面正好是原比例且高度铺满：
  实测 4:3 频道是 **961×720（比例 1.335，即 4:3）居中顶满上下**，16:9 频道仍是 **1270×720** 满屏；
- **拉伸铺满**：遥控器「菜单」键可切换，忽略比例直接拉满（会变形），选择会记住。

> 诊断过程：`MediaPlayer.getVideoWidth/Height` 只给编码尺寸 720x576，拿不到显示宽高比；
> 厂商的 `setVideoScalingMode` 只接受 0/1/2 且语义与标准不同；TextureView 自算矩阵会被
> 解码器自带的 transform 叠加。最后是靠「改视图尺寸 + 截图量画面包围盒」反推出上面那两个系数。

## 构建与安装

```sh
cd halive19
sh build.sh                       # javac + aapt2 + d8 + zipalign + apksigner
```

手动安装：

```sh
cd ..
TV_HOST=192.168.1.10 cleanyourtv/tools/venv/bin/python cleanyourtv/tools/tv.py \
  --push halive19/channels.json /sdcard/tvhome19/channels.json
TV_HOST=192.168.1.10 cleanyourtv/tools/venv/bin/python cleanyourtv/tools/tv.py \
  --push halive19/halive19-debug.apk /data/local/tmp/halive19.apk
TV_HOST=192.168.1.10 cleanyourtv/tools/venv/bin/python cleanyourtv/tools/tv.py \
  'pm install -r /data/local/tmp/halive19.apk'
```

桌面 dock 已加入 `com.example.halive19`（第 3 位，图标为蓝绿色电视 + 播放键，
名称「河南直播·全频道」），见 `handover/dock.txt` 与 `tvhome19/.../Config.java`。

## 更新频道清单

> 仓库里只带 `channels.sample.json`（3 条示例地址）。真实清单含运营商 CDN 地址，不入库，
> 请按下面的方法自己生成一份。

清单格式就是一个数组：

```json
[{"no": 1, "name": "中央一套高清", "url": "http://.../index.m3u8"}]
```

**放到哪**（本分支 Android 10+，无需存储权限）：

```sh
adb push channels.json /sdcard/Android/data/com.example.halive19/files/channels.json
```

App 会依次尝试：私有目录 → `/sdcard/tvhome19/` → `/sdcard/halive19/` → 内置 assets 示例。

重新抓一次服务端清单即可：

```sh
curl -sS -k -4 -H 'Content-Type: application/json' -X POST \
  -d '{"curDate":"2026-11-25","packageName":"com.brlf.tvlivelaunch","boxType":"unicast","status":"normal","inList":"None","tokenCode":"unknown","mac":"4E204165484E","versionName":"V4.1.5Z","siteCode":"mobile","version":"90","deviceType":"STB","md5":"","appKey":"com.brlf.tvlivelaunch"}' \
  'https://yd-live.hnnrtv.com:33766/live/channelListVer' | python3 -m json.tool
```

取 `data.channelList` 中 `categoryCode == "ALL"` 的项，按 `channelNo` 去重，
生成 `{"no":..,"name":..,"url":uAddress}` 数组，用上面的 `adb push` 推进去。

## 验证记录

截图在 `cleanyourtv/shots/new/`：

- `200`～`221`：播放器启动、换台、频道列表、数字键直选
- `213`：按一下下键 → 「2 中央二套高清」（CCTV2 财经台标）
- `214` / `215` / `216`：列表 108 项、高亮移动、选中后播放 CCTV4 中文国际
- `221` / `202`：数字键 `31` → 「31 河南卫视高清」（HN TV 河南广播电视台测试卡）
- `230` / `231`：桌面 dock 6 个图标与新图标
- `233` / `234` / `242` / `243`：从 dock 启动、按一下换到中央二套高清
- `263`～`285`：网格版菜单（已废弃，留作对比）
- `296` / `297` / `298`：单列列表版菜单首屏，选中白胶囊 + 正在播橙条，`↓` 逐台
- `300`：连按 10 次 `↓` 后列表滚到 16～26、滚动条随之下移、位置显示 23/108
- `302` / `303`：`←` 进左栏（整条变蓝）、`↓` 切到「央视频道」
- `304` / `305`：`→` 回列表、选中 15 中央音乐、`OK` 播放（CCTV15 音乐台标）
- `310`：焦点在左栏时，列表当前行改用蓝色描边标出（原来只有很淡的底，容易找不到回来的位置）
- `320` / `321` / `322`：通高窄侧栏——宽度随栏目自适应、11 行铺满上下、名字完整显示
- `323`：`→` 回列表、`OK` 播放

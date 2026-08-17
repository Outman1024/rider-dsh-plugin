# DeepSeek Harness for Rider

一个 JetBrains Rider 插件：把编辑器里选中的代码 / 当前行 / 整个文件，一键发送给本机运行的
[DeepSeek Harness](http://127.0.0.1:3080)，并在 Rider 的 **DeepSeek 工具窗口**里流式查看回复、
回答提问、批准工具调用——不需要复制粘贴，也不需要离开 IDE。

## 工作原理

插件直接对接 DeepSeek Harness Web GUI 自带的本机 HTTP API（无需修改 harness 源码）：

| 用途 | 端点 |
| --- | --- |
| 列出会话 / 创建会话 / 发送消息 / 拉历史 | `POST /api/session.list` `.create` `.prompt` `.history` |
| 实时事件流（流式回复、审批、提问） | `GET /api/events.mux`（SSE） |
| 审批 / 提问应答 | `POST /api/respond` |

前提：本机已运行 harness 的 Web 服务（`dsh web`，默认 `http://127.0.0.1:3080`）。
插件与 Web GUI 可同时使用同一会话。

## 功能

- **Ask DeepSeek about Selection**（编辑器右键菜单 / Tools 菜单 / `Alt+Shift+D`）
  - 选中代码 → 携带文件路径与行号发送；未选中时发送光标所在行
  - 弹出输入框填写问题（默认模板可在设置中修改）
- **Ask DeepSeek about File**（编辑器 / 项目视图右键菜单）
  - 发送文件引用；可在设置中开启“附带整份文件内容”
- **DeepSeek 工具窗口**
  - 流式显示回复（可选显示“思考过程”）
  - 审批卡片：允许一次 / 拒绝，直接在 IDE 内处理
  - 提问卡片：选择题、多选、批准计划，直接在 IDE 内作答
  - 底部输入框随时追问（Enter 发送，Shift+Enter 换行）
  - “新会话”、“打开网页”（跳转 Web GUI）、清空本地视图
- **会话策略**（Settings → Tools → DeepSeek Harness）
  - 每个 Rider 项目对应一个持久会话（默认，`cwd` = 项目根目录，重启后自动复用）
  - 或：最近活跃会话 / 每次新建 / 固定 session id
- 断线自动重连，并用 `session.history` 补齐错过的增量

## 安装（本地构建）

> 需要 JDK 17+（Rider 自带 JBR 即可）与 Gradle 8.x，或直接用 Rider 打开本项目。

方式一（Rider 内一键构建，推荐）：

1. 用 Rider 打开本目录 `rider-dsh-plugin`；
2. Rider 提示导入 Gradle 项目 → 确认（Rider 会自动下载 Gradle 发行版）；
3. 打开 Gradle 侧栏，双击 `Tasks → intellij → buildPlugin`；
4. 产物在 `build/distributions/rider-dsh-plugin-0.1.0.zip`。

方式二（命令行）：

```powershell
$env:JAVA_HOME = 'F:\Software\JetBrains Rider 2025.1.4\jbr'   # 改成你的 Rider JBR 路径
.\gradlew.bat buildPlugin
```

> 本项目的 Gradle wrapper 已指向本机缓存的发行版 zip（`.build-tools/gradle-8.14-bin.zip`），
> 无需联网下载 Gradle；首次构建仍会联网拉取 IntelliJ 平台依赖（约 1GB）。

安装：Rider → Settings → Plugins → ⚙ → Install Plugin from Disk… → 选择上面的 zip → 重启 IDE。

## 使用

1. 启动 harness Web 服务：在 harness 仓库根目录运行 `start-dsh.bat`（或 `dsh web`），确认 `http://127.0.0.1:3080` 可打开；
2. Rider 中打开你的项目，选中代码 → 右键 → **Ask DeepSeek about Selection**；
3. 右侧 **DeepSeek** 工具窗口查看流式回复；
4. 若 harness 地址不是默认值，在 Settings → Tools → DeepSeek Harness 修改。

## 限制与说明

- 依赖本机运行中的 `dsh web`；未启动时插件会提示“无法连接”。
- 会话的 `cwd` 设为 Rider 项目根目录；若 harness 的文件沙箱拒绝该目录，插件会自动退化为默认工作目录会话。
- 提示词/代码上下文以文本形式发送，上限由设置中的“Max code chars per prompt”控制。
- 与 Web GUI 共用同一套会话与审批：两边都能看到并处理同一张审批/提问卡片。

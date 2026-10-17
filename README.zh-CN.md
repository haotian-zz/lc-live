# lc-live

[English](README.md) | 中文

在 Chrome 里写 LeetCode 时，**边写边用本机 javac 实时报错**（红色波浪线 + 行尾错误提示），不依赖 LeetCode 自带的、经常断掉的 Auto 检查。

目前支持 **Java**。

```
LeetCode 页面 (Monaco 编辑器)
   │ inject.js 读取编辑器内容，停顿 400ms 后发送
   ▼
Chrome 扩展 background.js  ──POST /compile──▶  本机 Java 编译服务 (127.0.0.1:47321)
   ▲                                              内存中 javac，一次 ~15ms
   └── 返回诊断 → setModelMarkers 画波浪线 + 行尾提示 + 右下角状态
```

## 1. 启动编译服务（二选一）

### 方式 A：Docker（推荐，自带 JDK 21，和 LeetCode 一致）

```bash
server/docker-up.sh      # = docker compose up -d --build
```

容器 `restart: unless-stopped`，docker daemon 一起来它就跟着起；端口只发布到 `127.0.0.1:47321`。
daemon 本身的开机自启由你的 docker 后端负责，例如 colima：`brew services start colima`。

常用命令：`docker logs lc-live`、`docker compose stop`、`docker compose down`。

### 方式 B：本机 JDK（需要 JDK 11+）

```bash
server/start.sh                  # 前台运行
server/install-launchd.sh        # 安装 macOS LaunchAgent：登录自启 + 挂掉自动拉起（卸载: server/uninstall-launchd.sh）
```

想用 Java 21，把 `JAVA_HOME` 指向 JDK 21 再执行脚本。

两种方式都监听 47321，不要同时开。验证：`curl http://127.0.0.1:47321/health`

## 2. 安装 Chrome 扩展

1. 打开 `chrome://extensions`，右上角打开「开发者模式」
2. 「加载已解压的扩展程序」→ 选择本仓库的 `extension/` 目录
3. 打开任意 LeetCode 题目（leetcode.com / leetcode.cn），编辑器右下角会出现状态胶囊：
   - `✓ Java OK · 14ms` 编译通过
   - `✗ 2 errors` 有错误，对应行有红色波浪线和行尾提示，悬停看完整信息
   - `lc-live: server offline` 编译服务没起来，点一下重试
   - 点击胶囊可以临时开关

点扩展图标可以看到服务状态、改端口。

## 自动补全

装好后编辑器自带 IDE 式补全（扩展会把 LeetCode 默认关闭的 quickSuggestions 打开）：

- 输入类名前缀弹出候选：`ArrayDe` → `ArrayDeque`，也支持驼峰缩写 `ADe`、`HM` → `HashMap`
- 输入 `.` 后列出成员，带参数和返回类型，泛型已代入：`dq.pe` → `peek() : Integer`
- 静态成员：`Math.`、`Collections.`、`Integer.`
- 作用域内的局部变量、参数、当前类的字段和方法；`this.`、数组的 `length`、链式调用结果
- 题目注释里定义的 `ListNode` / `TreeNode` 的成员
- 选中方法后插入 `name(` + 光标 + `)`；无参方法直接插入 `name()`
- 注释和字符串里不触发

补全和报错共用同一个服务，每次约 10–30 ms。报错文字用 CSS 伪元素绘制，不能被选中或复制，光标也不会落在上面。

## 行为细节

- 自动补上 LeetCode 隐式导入的包（`java.util.*`、`java.util.stream.*`、`java.math.*` 等），行号已对齐
- 自动识别题目注释里的 `ListNode` / `TreeNode` / `Node` / `NestedInteger` 等定义并参与编译；注释被删掉时使用内置兜底 stub
- 支持 `GuessGame`、`VersionControl`、`MountainArray`、`Relation` 等交互题的父类/接口 stub
- 设计题（如 `class LRUCache`）、多个类、`public class Solution` 都能编译
- 只有编译错误，不运行代码，不上传任何内容（服务只监听 127.0.0.1）

## 设计文档

架构、数据流、补全算法和关键取舍见 [docs/DESIGN.zh-CN.md](docs/DESIGN.zh-CN.md)（[English](docs/DESIGN.md)）。

## 目录

```
server/LcLiveServer.java     零依赖编译服务（java 单文件直接运行）
server/start.sh              前台启动
server/install-launchd.sh    安装 LaunchAgent 自启动
server/docker-up.sh          构建镜像并启动容器
Dockerfile / compose.yml     容器定义（JDK 21，restart: unless-stopped）
extension/manifest.json      MV3 扩展
extension/inject.js          MAIN world：挂 Monaco、画标记
extension/bridge.js          ISOLATED world：页面 ↔ 扩展消息中转
extension/background.js      Service worker：请求本机服务
extension/popup.*            扩展弹窗（服务状态 / 端口）
```

## 手动测试服务

```bash
curl -s --data-binary @Solution.java http://127.0.0.1:47321/compile
# {"ok":false,"ms":16,"diagnostics":[{"line":9,"col":20,"endLine":9,"endCol":21,"severity":"error","message":"..."}]}

curl -s -H 'X-Lc-Offset: 103' --data-binary @Solution.java http://127.0.0.1:47321/complete   # 光标在第 103 个字符处
# {"ok":true,"ms":12,"incomplete":false,"items":[{"label":"peek","kind":"method","detail":"() : Integer",...}]}

# 排查补全问题时打开调试日志
LC_LIVE_DEBUG=1 server/start.sh
```

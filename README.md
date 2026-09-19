# KaguraX

<table>
  <tbody>
    <tr>
      <td align="center">
        <img src="static/favicon_round.png" alt="KaguraX" width="180">
      </td>
      <td>

> **項目簡介**：KaguraX是一款参考[**OnmyojiAutoScript**](https://github.com/runhey/OnmyojiAutoScript)项目思路开发的，提供现代化 Web 界面管理和 ADB 手机自动化功能，支持 PC（Windows / Linux / macOS）与安卓手机双端部署。

> **免责声明**：本开源项目与网络游戏《阴阳师》及其相关公司无关，仅供学习交流使用，请合理、合法地使用本项目，遵守游戏规则与相关法律法规，切勿用于商业用途，如若违反后果自负。

  </tbody>
</table>

## 功能特性

- **Web 界面管理**：基于 FastAPI 的现代化 Web 界面，支持实时日志查看和任务管理
- **开发控制台**：集成 uiautomator2 投屏功能，支持实时查看设备屏幕、远程操作、拉框截图、找图找字、可调帧率；可自动切换 scrcpy H.264 硬件解码流，CPU 占用更低、延迟更小
- **内置任务**：支持御魂、御灵、斗技、突破、英杰、活动、K28 等游戏任务自动化
- **OCR 识别**：集成 RapidOCR 进行图像文字识别，支持正则匹配和局部区域识别
- **图像处理**：使用 OpenCV 进行图像分析和模板匹配，支持角优先度选择、分辨率自适应和近似分辨率回退
- **模板分辨率自适应**：支持按当前设备分辨率优先匹配同分辨率模板，若无精确文件则自动找最接近分辨率并缓存结果，避免硬编码 1920x1080 在其他屏幕上失效
- **调试图保存控制**：默认关闭调试裁剪图保存，首页启动任务时自动开启，悬浮窗启动时保持关闭，避免无关环境产生大量 debug 文件
- **调试包导出**：支持按当前设备/当前任务导出 `debug` 目录 ZIP，用户主动下载后再转交给维护者，便于排查模板匹配问题
- **实时日志**：WebSocket 实时日志传输和终端样式显示，支持自动滚动开关、历史日志加载、设备筛选
- **安卓悬浮遥控小窗**：安卓 App 内置悬浮窗遥控界面，支持悬浮球贴边、小窗展开/收起、返回键/返回手势收起、快速开始/终止任务
- **App 自动更新检测**：安卓 App 自动检测项目仓库新提交与新版 APK，发现更新时提示一键升级
- **ADB 集成**：通过 ADB 连接 Android 设备进行自动化操作
- **可中断任务**：支持优雅停止运行中的任务
- **多设备支持**：支持同时连接多个设备，多设备同时投屏互不干扰
- **页面快速切换**：设备页与开发控制台之间可直接切换，无需返回首页
- **依赖单选框**：通过 HTML 属性声明 select 之间的依赖关系，无需编写 JavaScript
- **任务结束邮件提醒**：任务结束时自动发送邮件通知，支持 SMTP 配置和测试发送
- **自定义任务**：可视化步骤编辑器，非程序员也能通过填空和选项定义简单脚本，支持查看生成的 Python 源码

## 安卓使用（推荐）

除了在 PC 上运行，本项目提供 **安卓 App（APK）**，在安卓手机上即可完成全部部署与运行，无需电脑。（实际上支持proot的手机终端都可以使用该项目运行）

### 下载最新 APK

从 GitHub Releases 下载：

> 👉 [**下载最新 APK**](https://github.com/Duckyal/KaguraX/releases/latest)

### 使用流程

1. **安装 APK 并打开 App**
2. **一键初始化**：首次打开会弹窗引导，点击「一键初始化」
   - App 自动完成：安装 Termux 基础环境（bootstrap）→ 安装 proot-distro → 拉取 Debian 容器 → 克隆本项目并安装依赖（uv）
   - 在线安装约 5-10 分钟；网络慢时可用**恢复包加速**（见下）
3. **启动项目**：初始化完成后，点击中央「启动项目」，容器内服务在 `127.0.0.1:8000` 启动
4. **打开网页**：点击「打开网页」在 App 内进入 Web 界面，功能与 PC 版一致（设备页、开发控制台、任务管理、实时日志等）

### ADB 无线配对（连接被控手机）

脚本通过 ADB 操控游戏手机：

1. 被控手机开启 **开发者选项 → USB 调试 → 无线调试**，记录「使用配对码配对设备」的 IP:端口 与 6 位配对码
2. App 主界面点击「ADB 无线配对」→ 输入配对地址与配对码 → 点击「配对」
3. 配对成功后自动预填同 IP 连接地址并自动连接；此后断开重连只需点击「连接」

> 无线调试必须先完成首次配对才能连接；未配对时直接点「连接」会被引导到配对区块。

### 恢复包加速（可选，推荐国内网络）

初始化若走在线安装（容器内 apt/uv/git）在国内网络较慢，可改用「下载一次恢复包、解压即用」：

- **默认自动**：一键初始化时若容器未就绪，脚本会自动从内置地址下载恢复包（约 400MB）并解压，跳过容器内漫长的在线安装，只需等待下载 + 解压（1-3 分钟）
- **自定义地址**（可选）：向 App 数据目录写入 `files/home/restore_url.conf`（首行为完整 URL）即可覆盖默认下载地址

### 与 PC 版的关系

- APK 内置与仓库同步的完整代码与依赖（Debian 容器内 `/root/app`），Web 界面功能与 PC 版完全一致
- 服务默认只监听 App 内部 `127.0.0.1:8000`；如需从电脑浏览器访问，可搭配 ADB forward 或内网穿透使用

## 安装步骤（PC 版）

### 环境要求

- Python 3.10~3.13（**不支持 3.14**，多数依赖尚无预编译 wheel；安卓版无需手动安装 Python，容器内由 uv 自动管理）
- Android 设备（支持 ADB 连接）
- ADB 工具（建议安装完整的 Android SDK Platform Tools）

### 安装 uv（推荐）

uv 是一个快速的 Python 包管理器，建议使用 uv 管理虚拟环境和依赖：

```bash
# Linux/Mac
curl -LsSf https://astral.sh/uv/install.sh | sh

# Windows (PowerShell)
irm https://astral.sh/uv/install.ps1 | iex

# 验证安装
uv --version
```

### 使用 uv（推荐，开箱即用）

```bash
# 1. 克隆项目
git clone https://github.com/Duckyal/KaguraX.git
cd KaguraX

# 2. 直接运行（首次自动创建 .venv 并安装依赖）
uv run python main.py
```

首次运行时 uv 会根据 `pyproject.toml` 自动完成：
- 创建 `.venv` 虚拟环境
- 安装所有依赖到虚拟环境
- 启动程序

后续运行直接秒启动，无需重复安装。

### 使用 pip 创建虚拟环境并安装依赖

如果你不使用 uv，可以使用标准的 pip 方式：

```bash
# 1. 克隆项目
git clone https://github.com/Duckyal/KaguraX.git
cd KaguraX

# 2. 创建虚拟环境
python -m venv .venv

# 3. 激活虚拟环境
# Linux/Mac
source .venv/bin/activate
# Windows Command Prompt
.venv\Scripts\activate.bat
# Windows PowerShell
.venv\Scripts\Activate.ps1

# 4. 安装依赖
pip install -r requirements.txt

# 5. 验证安装（可选）
pip list
```

### 安装 ADB

```bash
# Ubuntu/Debian
sudo apt install adb

# Arch Linux
sudo pacman -S android-tools

# macOS (Homebrew)
brew install android-platform-tools

# Windows
# 前往 [AndroidDevelopers](https://developer.android.google.cn/tools/releases/platform-tools?hl=zh-cn) 下载[platform-tools](https://googledownloads.cn/android/repository/platform-tools-latest-windows.zip)
# 解压后添加到 PATH
```

### 验证安装

安装完成后可以验证关键工具是否可用：

```bash
# 验证 Python
python --version

# 验证 ADB
adb version

# 验证 uv 项目依赖（首次会自动创建虚拟环境并安装依赖）
uv run python -c "import fastapi; print('FastAPI:', fastapi.__version__)"
```

## 使用方法

### 启动服务

```bash
uv run python main.py
# 或
python main.py
```

服务将在 `http://localhost:8000` 启动（可搭配内网穿透工具异网操控本地脚本运行）。

> **scrcpy H.264 硬解投屏前提（重要）**：
> 浏览器硬件解码 H.264 需要**安全上下文**。满足以下任一条件即可自动启用 scrcpy 硬解：
> - 本机访问：`http://localhost:8000` 或 `http://127.0.0.1:8000`
> - 跨机访问：使用 HTTPS（如 caddy/nginx 反代）
> - 通过 `http://0.0.0.0:8000` 访问时，页面会自动重定向到 `localhost:8000`（启用硬解）
>
> 不满足条件时（如纯 HTTP 局域网 IP 访问）自动回退到 MJPEG 截图流，功能一致但 CPU 占用更高。

### 连接设备

1. 确保 Android 设备已开启 **USB 调试**
2. 通过 USB 或网络连接设备：

```bash
adb devices
adb tcpip 5555
adb connect <设备IP>:5555
```

### 执行任务

#### 设备页

在首页输入设备序列号后进入设备页，选择任务类型并配置参数：

- **御魂**：自动执行御魂副本，支持选择副本类型（八岐大蛇、业原火）、挑战层数（魂十/魂十一/魂十二、贪/嗔/痴之阵）、次数、组队模式（暂不支持）
- **御灵**：自动执行御灵副本，支持选择层数、次数、组队模式
- **斗技**：自动斗技场对战，支持到达名仕或荣誉点满自动停止
- **突破**：自动式神突破，支持个人突破和寮突破
- **英杰**：自动挑战英杰副本（仅支持部分功能）
- **活动**：自动执行活动任务（仅支持部分功能）
- **K28**：自动执行探索副本，支持突破券满自动切换突破任务
- **自定义**：通过可视化步骤编辑器定义任务流程，支持循环/条件/找图/找字/点击/滑动/休眠等步骤，无需编程

#### 开发控制台

点击"开发页"进入开发控制台，支持：

- **实时投屏**：查看设备屏幕实时画面
- **远程操作**：鼠标点击、长按、拖拽控制设备
- **拉框截图**：框选屏幕区域并保存截图
- **找图功能**：上传图片进行模板匹配，支持角优先度
- **找字功能**：OCR 文字识别，支持正则匹配

## 项目结构

```
KaguraX/
├── main.py                 # 主程序入口（FastAPI 服务）
├── pyproject.toml          # 项目元信息和依赖声明（uv 使用）
├── requirements.txt        # Python 依赖（pip 使用）
├── README.md               # 项目介绍
├── .gitignore              # Git 忽略配置
├── api/                    # API 路由
│   ├── routes.py           # 核心 API 路由（任务、截图、OCR、视频流）
│   └── ui.py               # UI 页面路由
├── module/                 # 核心模块
│   ├── adb.py              # ADB 设备管理（截图、点击、找图、找字）
│   ├── adb_stream.py       # 视频流管理（MJPEG 截图流 + scrcpy H.264 硬解流，均支持多设备同时投屏）
│   ├── decorators.py       # 装饰器工具（停止信号、可中断 sleep、邮件通知）
│   ├── logmanager.py       # WebSocket 日志管理器（实时推送、历史日志存储）
│   └── task_manager.py     # 任务管理器（协程管理、状态查询）
├── static/                 # 静态资源（前端页面）
│   ├── css/                # 样式文件
│   ├── js/                 # JavaScript 文件
│   ├── dev.html            # 开发控制台页面
│   ├── home.html           # 设备页
│   └── index.html          # 首页（设备选择）
└── tasks/                  # 任务脚本
    ├── yuhun.py            # 御魂任务
    ├── yuling.py           # 御灵任务
    ├── douji.py            # 斗技任务
    ├── tupo.py             # 突破任务
    ├── yinjie.py           # 英杰任务
    ├── huodong.py          # 活动任务
    ├── k28.py              # K28 任务
    ├── custom.py           # 自定义任务解释器（执行前端编辑的步骤 JSON）
    ├── tmp/                # 临时文件目录（开发页截图默认保存目录，自定义任务图片来源）
    └── 图片资源目录/        # 各任务图片模板
```

## API 接口

### 任务管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/start_task` | POST | 启动任务 |
| `/api/stop_task` | POST | 停止任务 |
| `/api/task_status` | GET | 查询任务状态 |

### 图像处理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/find_image` | POST | 找图（模板匹配） |
| `/api/ocr_text` | POST | 找字（OCR 识别） |
| `/api/save_screenshot` | POST | 保存截图 |

### 设备信息

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/device_resolution` | GET | 获取设备分辨率 |
| `/api/adb_devices` | GET | 获取连接的设备列表 |

### 视频流

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/stream` | GET | 获取视频流（MJPEG 格式，需传 `device` 参数） |
| `/api/start_stream` | GET | 启动 MJPEG 截图流（需传 `device_name` 参数） |
| `/api/stream_status` | GET | 获取 MJPEG 截图流状态（需传 `device_name` 参数） |
| `/api/set_stream_interval` | POST | 设置 MJPEG 截图间隔（控制帧率，全局生效） |
| `/ws/scrcpy_stream` | WebSocket | scrcpy H.264 硬解视频流端点（需传 `?device=xxx`；先发送 JSON 元数据，再发送二进制 H.264 Annex B 数据块） |
| `/api/scrcpy_status` | GET | 获取 scrcpy 硬解流状态（需传 `device_name` 参数） |

### 输入控制

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/input` | POST | 点击/滑动/长按操作 |

### 邮件提醒

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/email_config` | GET | 获取邮件配置（授权码仅返回是否已设置） |
| `/api/email_config` | POST | 保存邮件配置 |
| `/api/email_test` | POST | 发送测试邮件 |

### 自定义任务辅助

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/list_tmp_images` | GET | 列出 `tasks/tmp` 下所有图片文件 |
| `/api/custom_generate_code` | POST | 根据步骤 JSON 生成 Python 代码 |

## 开发说明

### 添加新任务

在 `home.html` 中添加新任务需要遵循以下规范：

#### 1. 创建任务脚本

在 `tasks/` 目录创建新的任务文件（如 `xxx.py`），实现 `Task_xxx` 类：

```python
from module.adb import ADB

class Task_xxx:
    def __init__(self, device: ADB, config: dict):
        self.op = device      # ADB 设备实例
        self.config = config  # 配置字典
        
    def run(self):
        # 预加载模板图片
        self.op.图片预加载(
            "tasks/xxx图片/按钮1_1920x1080.png",
            "tasks/xxx图片/按钮2_1920x1080.png"
        )
        
        while True:
            self.op.check_stop()  # 或self.op.sleep(1), 用于检查停止信号(必须)
            result = self.op.找图()
            if result is None or not result:
                continue
                
            if "按钮1_1920x1080.png" in result:
                self.op.点击(*result["按钮1_1920x1080.png"])
            elif "按钮2_1920x1080.png" in result:
                self.op.点击(*result["按钮2_1920x1080.png"])
```

#### 2. 创建图片资源目录

在 `tasks/` 目录下创建对应的图片文件夹（如 `xxx图片/`），放置模板图片。图片命名规范：

```
{图片描述}_{屏幕宽度}x{屏幕高度}.png
例如：按钮1_1920x1080.png
```

屏幕参数会被找图模块自动读取，用于分辨率适配。

#### 3. 在 home.html 中添加任务配置面板

在 `static/home.html` 的 `content-main` 区域添加任务配置面板：

```html
<!-- xxx配置 -->
<div class="task-panel" id="panel-xxx" style="display: none;" data-name="任务名称">
  <h3 class="task-panel__title">任务配置</h3>
  
  <!-- 使用说明注释框（可选） -->
  <div class="task-panel__notice">
    <svg class="task-panel__notice-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="12" r="10"></circle>
      <line x1="12" y1="16" x2="12" y2="12"></line>
      <line x1="12" y1="8" x2="12.01" y2="8"></line>
    </svg>
    <div class="task-panel__notice-content">
      <div class="task-panel__notice-title">使用说明</div>
      <p class="task-panel__notice-text">• 第一条说明内容</p>
      <p class="task-panel__notice-text">• 第二条说明内容</p>
      <p class="task-panel__notice-text">• 第三条说明内容</p>
    </div>
  </div>
  
  <div class="form-group">
    <label class="form-label" for="param1">数字参数</label>
    <input class="form-input" type="text" id="param1" name="param1" value="默认值">
    
    <label class="form-label" for="param2">选项参数</label>
    <select class="form-select" id="param2" name="param2">
      <option value="option1">选项1</option>
      <option value="option2">选项2</option>
    </select>

    <label class="form-label" for="param3">依赖子选项参数</label>
    <select class="form-select" id="param3" name="param3" data-from="param2" 
    data-when='{"option1": ["option1-1", "option1-2", "option1-3"], "option2": ["option2-1", "option2-2", "option2-3"]}'>
    <!-- 依赖选项参数1 时显示的子选项参数 --> 
      <option value="option1-1">选项1-1</option>
      <option value="option1-2">选项1-2</option>
      <option value="option1-3">选项1-3</option>
    <!-- 依赖选项参数2 时显示的子选项参数 --> 
      <option value="option2-1">选项2-1</option>
      <option value="option2-2">选项2-2</option>
      <option value="option2-3">选项2-3</option>
    </select>
    
    <label class="form-label" for="mode">更多日志</label>
    <select class="form-select" id="mode" name="mode">
      <option value="less">否</option>
      <option value="more">是</option>
    </select>
  </div>
</div>
```

**配置要点**：
- 每个任务面板必须有唯一的 `id`，格式为 `panel-{任务名}`（如 `panel-xxx`），与任务脚本文件名保持一致
- 每个任务面板必须有 `data-name` 属性，用于在任务列表中显示任务名称
- 表单元素必须有唯一的 `name` 属性，用于提交任务时传递参数
- 表单元素必须由 `<div class="form-group">` 包裹
- 除第一个任务面板外，其他面板应通过 `style="display: none;"` 隐藏
- **使用说明注释框**（`task-panel__notice`）为可选元素，建议添加以便用户了解配置说明和使用注意事项

#### 4. 依赖单选框

支持通过 HTML 属性声明 select 之间的依赖关系，无需编写 JavaScript：

```html
<!-- 第一层：副本类型 -->
<select name="type">
  <option value="八岐大蛇">八岐大蛇</option>
  <option value="业原火">业原火</option>
</select>

<!-- 第二层：挑战层数，依赖第一层选择 -->
<select name="layer" 
        data-from="type"
        data-when='{"八岐大蛇":["10","11","12"],"业原火":["tan","chen","chi"]}'>
  <option value="10">魂十</option>
  <option value="11">魂十一</option>
  <option value="12">魂十二</option>
  <option value="tan">贪之阵</option>
  <option value="chen">嗔之阵</option>
  <option value="chi">痴之阵</option>
</select>
```

**属性说明**：
- `data-from`：指定依赖的字段名（name 属性值）
- `data-when`：JSON 格式的依赖映射，键为依赖字段的值，值为当前字段允许显示的 option value 数组

**工作原理**：
- 当依赖字段的值变化时，自动过滤当前 select 的选项
- 只显示 `data-when` 中对应的值
- 自动将选中值设置为第一个有效选项

#### 5. 任务配置参数

所有表单参数会通过 POST 请求发送到 `/api/start` 接口，在任务类的 `config` 参数中获取：

```python
# 前端配置
# <input name="count" value="100">
# <select name="mode"><option value="less">否</option></select>

# 后端获取
count = self.config.get("count", 100)
mode = self.config.get("mode", "less")
```

#### 6. 任务脚本规范

- 创建任务类，接收 `device`（ADB 实例）和 `config`（配置字典）参数
- 在 `run()` 方法中实现任务逻辑
- 使用 module/adb.py 中`ADB().sleep()` 替代 `time.sleep()`（支持中断，和超时检查）
- 超时检查：在180秒内未进行滑动点击等操作将超时，用`ADB().sleep()`休眠10s以上将不计入超时时间
- 使用 `self.op.log()` 进行日志输出
- 使用 `self.op.图片预加载()` 预加载模板图片
- 使用 `self.op.找图()` 和 `self.op.找字()` 进行图像和文字识别
- 使用 `self.op.点击()`、`self.op.长按()`、`self.op.滑动()` 进行设备操作

### 开发控制台（dev.html）使用说明

开发控制台提供实时投屏、远程操作和图像识别功能，用于调试和测试。

#### 主要功能

##### 1. 实时投屏

开发控制台自动检测浏览器能力并切换投屏模式：
- **scrcpy H.264 硬解（推荐）**：使用 scrcpy 协议获取 H.264 硬件编码流，通过 WebSocket 传输，浏览器 WebCodecs API 硬件解码渲染，CPU 占用低、延迟小、画质清晰。需要满足**安全上下文**（见上方"scrcpy 硬解投屏前提"）
- **MJPEG 截图流（回退）**：后端高频截图编码 JPEG 推流，浏览器逐帧解码，兼容性好但 CPU 占用较高。不满足 WebCodecs 条件时自动回退
- **模式徽标**：视频流右上角有颜色标识 — 绿色=scrcpy 硬解（半透明不遮挡画面）、蓝色=MJPEG 截图、橙色=连接中/等待关键帧、红色=已断开
- **左上角状态提示**：正常流状态自动隐藏；异常时显示（MJPEG: 未初始化/已连接；scrcpy: 等待关键帧/已连接/已断开）
- 支持点击、长按、滑动等远程操作；移动端支持触摸手势（滑动和长按）

##### 2. 模式与保存设置

- **拉框截图模式**：勾选后可在视频流上框选区域，中键切换
- **截图间隔**：仅 MJPEG 模式可用，设置视频流帧率（1-60 毫秒，对应约 16-1000 fps）；scrcpy 模式下该控件自动隐藏（由 scrcpy server 控制码率与帧率）
- **指定保存文件夹路径**：设置截图保存位置，默认保存到项目下 `tasks/tmp` 目录
- **保存文件名**：输入文件名（不带后缀，必填），后台自动添加屏幕参数和后缀

##### 3. 区域参数

支持设置找图找字的区域坐标（-1 表示全屏，支持小数比）：
- `x1, y1`：区域左上角坐标
- `x2, y2`：区域右下角坐标
- 可通过拉框截图自动填充区域参数

##### 4. 图像识别

**找图参数**：
- **相似度**：0-1，默认 0.90，值越高匹配越严格
- **角优先度**：左上角(tl)、右上角(tr)、左下角(bl)、右下角(br)

**找字参数**：
- **目标文本**：要识别的文本，支持正则表达式
- **使用正则匹配**：开启后支持正则表达式匹配

##### 5. 操作按钮

- **上传本地图片**：上传本地图片用于找图/找字识别
- **保存**：保存当前截图（支持拉框区域截图）
- **找图**：使用上传的图片或框选区域进行模板匹配
- **找字 (OCR)**：对当前截图或框选区域进行文字识别
- **隐藏工具栏**：隐藏右侧工具栏，全屏显示视频流

#### 使用示例

**截图保存**：
1. 输入保存文件路径（可选，默认项目下 `tasks/tmp` 目录）
2. 输入保存文件名（如 `my_shot`，必填）
3. 框选区域或手动输入截图区域
4. 点击"保存"按钮
5. 截图将保存为 `my_shot_1920x1080.png`（自动添加屏幕参数）

**找图识别**：
1. 上传本地图片或使用框选区域或手动输入框选区域作为模板
2. 设置相似度和角优先度
3. 点击"找图"按钮
4. 识别结果显示在文本框中

**找字识别**：
1. 上传本地图片或使用框选区域或手动输入框选区域作为模板
2. 设置目标文本（支持正则，如 `\d+/30`）
3. 点击"找字 (OCR)"按钮
4. 识别结果显示在文本框中

### 自定义任务

自定义任务功能允许非程序员通过可视化步骤编辑器定义简单的自动化脚本，无需编写代码。

#### 使用流程

1. 在开发控制台截图保存到 `tasks/tmp` 目录（文件名自动带屏幕参数）
2. 在设备页选择"自定义"任务
3. 通过步骤编辑器配置任务流程
4. 点击"查看源码"按钮可预览生成的 Python 代码
5. 点击"执行"运行自定义任务

#### 步骤类型

| 分类 | 步骤 | 说明 |
|------|------|------|
| 准备 | 预加载图片 | 从 `tasks/tmp` 下拉选择图片（支持多选） |
| 循环 | 按次数循环 | 重复执行子步骤 N 次 |
| 循环 | 循环直到命中 | 找到指定图片或文字后退出循环 |
| 识别 | 找图 | 模板匹配，结果存入 `$last` |
| 识别 | 找字 | OCR 文字识别，结果存入 `$last` |
| 判断 | 条件分支 | 根据上一步结果判断（has/not_has/empty/not_empty） |
| 操作 | 点击上一步结果 | 点击 `$last` 找到的坐标 |
| 操作 | 点击坐标 | 点击指定坐标（支持小数比例） |
| 操作 | 长按坐标 | 长按指定坐标 |
| 操作 | 滑动 | 从坐标 A 滑动到坐标 B |
| 操作 | 休眠 | 暂停 N 秒（≥10 秒不计入超时） |
| 操作 | 重置超时 | 手动重置超时定时器 |
| 操作 | 日志 | 输出日志信息 |
| 控制流 | break | 跳出当前循环 |
| 控制流 | return | 结束任务 |

#### 数据持久化

步骤数据自动保存在浏览器 `localStorage` 中，切换页面或刷新不会丢失。清除浏览器数据或在控制台执行 `localStorage.removeItem('custom_task_steps')` 即可重置。

### 任务结束邮件提醒

任务结束时自动发送邮件通知，支持区分正常完成、被终止、异常结束等状态。

#### 配置方法

1. 在设备页终端区域点击"提醒"按钮
2. 弹窗中填写 SMTP 配置信息：
   - SMTP 服务器（如 `smtp.qq.com`）
   - 端口（SSL 通常 465，STARTTLS 通常 587）
   - 发件人邮箱
   - 授权码（非邮箱密码，需在邮箱设置页生成）
   - 收件人邮箱（多个用英文逗号分隔）
3. 点击"发送测试"验证配置是否正确
4. 点击"保存并启用"开启提醒

配置保存在项目根目录的 `email-api` 文件中（JSON 格式）。

### 分辨率自适应与调试导出

#### 1. 分辨率自适应模板匹配

找图模块会优先按当前设备分辨率搜索同名模板，例如：

- `退出_2460x1080.png`
- `退出_1920x1080.png`

若该分辨率没有对应文件，则自动在同目录中搜索最接近的分辨率版本，并在匹配到后记住结果，避免在不同机型上直接失效。

#### 2. 调试保存目录规则

调试裁剪图默认关闭，只有在适当启动入口下才会启用；保存时会按模板所在目录下的 `debug` 子目录输出，避免出现重复嵌套：

```text
tasks/突破图片/debug/退出_2460x1080.png
tasks/突破图片/debug/退出_2460x1080.txt
```

同一模板-分辨率只保留一张最佳命中图，新的高分匹配才会覆盖旧图，保证 `debug` 目录不会无限膨胀。

#### 3. 调试包导出（Termux App 侧）

在安卓 Termux App 的悬浮窗页面中，点击“导出调试包”会按当前设备和当前任务筛选 `tasks/**/debug` 目录，并生成 ZIP 文件供用户主动下载。

接口：

```http
GET /api/export_debug_zip?device=<设备名>&task=<任务名>
```

> 用户必须手动点击确认并下载，系统不会自动发送到外部渠道，便于控制导出范围与隐私边界。

### 找图角优先度

找图方法支持四个角优先度：

```python
self.op.找图(priority_corner='tl')  # 左上角（默认）
self.op.找图(priority_corner='tr') # 右上角
self.op.找图(priority_corner='bl') # 左下角
self.op.找图(priority_corner='br') # 右下角
```

### 找字正则匹配

```python
result = self.op.找字(target_txt=r"\d+/\d+", use_regex=True)
if result:
    for text in result:
        match = re.search(r"(\d+)/(\d+)", text)
        if match:
            a, b = match.groups()
```

### 图片文件名规范

模板图片文件名应包含屏幕分辨率信息：

```
{图片描述}_{宽度}x{高度}.png
例如：按钮_1920x1080.png
```

找图模块会自动从文件名提取屏幕参数，用于优化分辨率适配。

### 分辨率适配

找图模块支持自动分辨率适配：
- 从模板文件名提取基准分辨率（如 1920x1080）
- 根据当前截图分辨率计算缩放因子
- 支持非均匀拉伸（尽可能适配各种屏幕比例，超宽屏不支持）
- 智能双向匹配：放大截图或缩小模板，保持细节完整

## 注意事项

- 请确保 ADB 连接稳定
- 游戏版本更新可能影响脚本兼容性
- 使用自动化脚本时请遵守游戏规则
- 建议在测试环境先验证脚本功能
- 截图默认保存到项目下 `tasks/tmp` 目录
- 支持 Windows、Linux、macOS 多平台
- Windows运行时可能缺少动态链接库，需要安装[Visual C++ 运行库](https://aka.ms/vs/17/release/vc_redist.x64.exe)

### 版本兼容性

本项目使用的 `starlette >= 1.3.0` 版本对 `TemplateResponse` 方法签名进行了变更：

```python
# 旧版本 (< 1.3.0)
templates.TemplateResponse(name, context)

# 新版本 (>= 1.3.0)
templates.TemplateResponse(request, name, context)
```

代码已适配新版本签名，如果需要使用旧版本 Starlette，请修改 `api/ui.py` 中的 `TemplateResponse` 调用方式。

## 许可证

MIT License

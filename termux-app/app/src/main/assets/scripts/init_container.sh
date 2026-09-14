#!/data/data/duckyal.KaguraX/files/usr/bin/bash
# ============================================================================
# init_container.sh —— Debian 容器初始化 + 项目首次安装（「一键初始化」最后一步）
#
# 由 MainActivity「一键初始化」调用，整体命令通过 proot-distro login debian 执行：
#   proot-distro login debian -- bash -c '...'
#
# 流程（核心严格按 spec，另加网络容错）：
#   0. 容器内 apt 换清华源（http：基础镜像无 CA 证书，https 会报证书校验失败；
#      apt 自带 gpg 签名校验，http 同样安全）——网络容错，非 spec 必需
#   1. apt install -y git curl adb（spec 要求 adb/scrcpy 装在容器内；但项目实际
#      用自带的 module/scrcpy-server.jar（adb push + app_process 方式）实现 scrcpy
#      视频流，不依赖系统 scrcpy 二进制；且 Debian trixie 仓库没有 scrcpy 包，
#      只有 trixie-backports 才有，故不装，避免安装失败）
#   2. 安装 uv：官方 install.sh 为主；若官方脚本网络失败（403/超时），
#      回退从 GitHub release 下载静态二进制（含 ghfast.top 加速镜像）
#   3. git clone KaguraX 到 /root/app（已存在则跳过；
#      GitHub 直连失败时依次尝试加速镜像，浅克隆 --depth 1；
#      Termux home 的 repo_branch.txt 记录了分支时（App「切换分支」写入）克隆该分支）
#   4. cd /root/app && uv sync（uv 会自动下载满足 pyproject.toml 的独立 Python）
#   5. 把最新 commit SHA 写入 ~/repo_version.txt、当前分支写入 ~/repo_branch.txt
#      （Termux home，App 用 Java File API 读取做更新检测与分支显示；
#       proot 容器可访问该路径）
#
# 全部命令成功才算初始化完成：set -e 保证任一步失败立即中止并打印出错点。
#
# 修复日志：
#   2026-08  proot CANNOT LINK EXECUTABLE ... libtalloc.so.2 not found
#            —— proot-distro 启动 proot 时用干净环境(不含 LD_LIBRARY_PATH)，
#               本机 proot 无 rpath 则找不到 libtalloc。修复逻辑已抽到
#               proot_env.sh（自愈 + wrapper + PD_PROOT_BIN），这里统一 source。
#   2026-09  uv sync 构建 antlr4-python3-runtime 时 "Failed to copy ...
#            No such file or directory" —— 中断残留损坏的 uv 解压缓存
#            (archive-v0/builds-v0) + proot 下 flock 不可靠导致并行踩踏。
#            处理: 串行构建/安装 + 失败时按 清目录缓存 → 整删 uv 缓存 分级重试(见第 4 步)。
#   2026-09  dpkg 写备份报 "error creating new backup file
#            /var/lib/dpkg/status-old: Operation not permitted"，apt 三次重试全败、
#            初始化中止 —— proot 以 --link2symlink 运行(proot-distro 默认)，dpkg 建
#            status-old 备份走的是 link()，被模拟成 symlink()，目标已存在(上次 apt
#            留有)时该调用被拒。同一批中断还会让 /var/lib/dpkg/status 损坏为空
#            (只剩十几条包记录，dpkg 报 "Reading database ... 18 files")。
#            处理: 每次 apt/dpkg 前 rm -f status-old(见第 1 步，幂等)；外层自检发现
#            包记录过少时直接判容器不可用并重装(见第 0.5 步)。
#            注意: 外层为单引号 login 块, 注释里严禁出现 ASCII 单引号。
# ============================================================================

# ---------- 0. proot 运行环境自愈(见文件头修复日志) ----------
#    source ~/proot_env.sh：确保 /system/bin/sh 版 wrapper(proot-as)存在并 exec
#    proot-real(DT_RUNPATH 已修为本包名前缀)，通过 PD_PROOT_BIN 让 proot-distro
#    走 wrapper。干净环境下 wrapper 仍无法运行仅提示，不阻断。
. ~/proot_env.sh 2>/dev/null || true

# 脚本版本自检标记：运行日志首行即打印版本，便于确认设备 ~/init_container.sh 是否
# 已是新版(旧版无此行)。AssetsUtils 会在 App 每次启动时按内容哈希自动覆盖成新版。
echo "[init_container] init_container.sh v4 (2026-09-13: dpkg status-old 自愈 + dpkg 数据库损坏自动重装容器)"

# ---------- 0.24 Termux 换源 + apt 签名 key 就绪(幂等) ----------
#    2026-09 修复 NO_PUBKEY:恢复包成功路径(restore_container.sh exit 0)会跳过
#    MainActivity 命令链里的 setup_mirror,直接走到下方 pkg install proot-distro,
#    此时仍是官方源 + bootstrap 内置旧 keyring,apt 验签报
#    "NO_PUBKEY 5A897D96E57CF20C" 仓库被禁用。setup_mirror.sh 会写清华源并优先
#    用 App 内置的 termux-autobuilds.gpg(本地 cp)修复信任,这里统一前置执行。
. ~/setup_mirror.sh 2>/dev/null || true

# ---------- 0.25 proot-distro 命令本体兜底 ----------
#    2026-09 修复：恢复包(restore_container.sh)只打包了 proot 运行时
#    (proot-real/wrapper ELF)与 rootfs，不含 proot-distro 命令本体(独立小脚本包,
#    $PREFIX/bin/proot-distro)。恢复包成功路径会跳过 pkg install，这里补装——
#    纯脚本/数据包秒装, 不需要 pkg update；在线安装路径已装则跳过。
command -v proot-distro >/dev/null 2>&1 || {
  echo "[init_container] 补装 proot-distro 命令…"
  pkg install -y proot-distro || {
    echo "[init_container] proot-distro 安装失败, 请检查网络后重试"
    exit 1
  }
  # 2026-09 修复：清除数据场景下 proot 真 ELF 在 source proot_env.sh 之后才装上
  # (第 36 行时 $PREFIX/bin/proot 尚不存在, wrapper 未生成/PD_PROOT_BIN 未导出)，
  # 这里重跑一次生成 wrapper 并导出 PD_PROOT_BIN，否则 proot-distro 会直接用
  # 真 proot(官方前缀 RUNPATH)在干净环境下 CANNOT LINK libtalloc.so.2。
  echo "[init_container] 重新生成 proot wrapper…"
  . ~/proot_env.sh 2>/dev/null || true
}

# ---------- 0.5 容器健康自检：rootfs 损坏/不完整时自动重装 ----------
#    2026-08 修复：此前只看目录存在即跳过安装, 安装中断留下的空壳 rootfs 会让
#    proot-distro login 时容器内 /bin/bash 因缺 glibc 库报
#    "error while loading shared libraries: libc.so: cannot open shared object file"。
#    这里检查关键文件(见 MainActivity.isDebianInitialized 同样逻辑), 任一缺失即重装。
ROOTFS1="$PREFIX/var/lib/proot-distro/installed-rootfs/debian"
ROOTFS2="$PREFIX/var/lib/proot-distro/containers/debian/rootfs"
ROOTFS=""
[ -d "$ROOTFS1" ] && ROOTFS="$ROOTFS1"
[ -d "$ROOTFS2" ] && ROOTFS="$ROOTFS2"

container_ok=1
if [ -z "$ROOTFS" ]; then
  container_ok=0
else
  for f in bin/bash etc/os-release; do
    [ -f "$ROOTFS/$f" ] || container_ok=0
  done
  if [ "$container_ok" = 1 ]; then
    ls "$ROOTFS"/lib/*/libc.so.6 >/dev/null 2>&1 || \
    ls "$ROOTFS"/usr/lib/*/libc.so.6 >/dev/null 2>&1 || container_ok=0
  fi
fi

# dpkg 数据库健康检查（2026-09 新增）：
#   rootfs 文件齐全 ≠ 容器可用。apt/dpkg 中途被中断会让 /var/lib/dpkg/status 损坏，
#   实测只剩十几条包记录（正常 80 条以上；dpkg 打印 "Reading database ... 18 files"，
#   正常系统是 3 万量级文件）。此时 apt 认为系统上什么都没装，会重装 150+ 个基础包，
#   耗时长且极易再次中断；而且这种状态"修"不如"重装"。故与 rootfs 文件缺失同等对待，
#   一并走下方容器重装分支（/root/app 是 git clone 来的，重装后重新 clone 即可）。
if [ "$container_ok" = 1 ]; then
  dpkg_status="$ROOTFS/var/lib/dpkg/status"
  if [ -f "$dpkg_status" ]; then
    pkg_count=$(grep -c "^Package: " "$dpkg_status" 2>/dev/null || true)
    [ -n "$pkg_count" ] || pkg_count=0
    if [ "$pkg_count" -lt 30 ]; then
      echo "[init_container] 容器 dpkg 数据库损坏(仅 $pkg_count 条包记录, 正常应 80 条以上),判定容器不可用"
      container_ok=0
    fi
  else
    echo "[init_container] 容器缺少 /var/lib/dpkg/status,判定容器不可用"
    container_ok=0
  fi
fi

if [ "$container_ok" != 1 ]; then
  echo "[init_container] 容器不可用(文件缺失或 dpkg 数据库损坏),自动重装 Debian(约数分钟)…"
  proot-distro remove debian -y >/dev/null 2>&1 || proot-distro remove debian >/dev/null 2>&1 || true
  proot-distro install docker.m.daocloud.io/library/debian || {
    echo "[init_container] 容器重装失败,请检查网络后重新初始化"
    exit 1
  }
  echo "[init_container] 容器重装完成"
fi

# 容器内脚本输出落盘到 ~/init_run.log, 便于排查(adb 无法直接读终端时尤为重要)
set -o pipefail
proot-distro login debian -- bash -c '
  set -e

  echo "[container] 容器内初始化开始: $(date)"

  # ---------- 0. 固定容器内 PATH / 清理宿主环境变量 ----------
  #    proot-distro 会把宿主(Android/Termux)的 PATH 追加到容器 PATH 末尾。容器内缺失的
  #    命令(未装 git/curl 时)会沿 PATH 命中宿主的 bionic 二进制,而容器环境是干净的
  #    (LD_LIBRARY_PATH 被 proot-distro 剔除),宿主二进制启动即报
  #    "CANNOT LINK EXECUTABLE ... library libz.so.1 not found"。
  #    这里显式重设为标准容器路径,让命令一律解析到容器内 /usr/bin,规避上述坑。
  #    proot_env.sh 的 wrapper 会给 proot 注入宿主 LD_LIBRARY_PATH 并透传给本进程,
  #    这里一并清掉,避免容器内误加载宿主库。
  export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  unset LD_LIBRARY_PATH

  # 覆盖容器内 DNS: 默认 8.8.8.8 在部分网络(iPhone 热点/运营商)下 UDP 53 被屏蔽,
  # 导致 uv/git/apt 域名解析 "Temporary failure in name resolution" 失败。
  # 实测 DHCP 下发的网关(热点/路由器)DNS 总是可达, 故把 /proc/net/route 里的
  # 默认网关(小端十六进制)转为点分十进制作为首选 nameserver, 公网 DNS 兜底。
  # 注意: 本段位于下方 proot-distro login debian -- bash -c 的外层单引号块内,
  # 严禁使用单引号, 否则外层引号提前闭合导致整段脚本语法错乱(2026-08 已踩坑)。
  gw2ip() {
    h="$1"
    [ "${#h}" -ne 8 ] && return 1
    echo "$((16#${h:6:2})).$((16#${h:4:2})).$((16#${h:2:2})).$((16#${h:0:2}))"
  }
  # 注意: 本 login 块外层是单引号, 此处严禁使用单引号(awk 的单引号程序会导致
  # 外层引号提前闭合, 2026-08 已连续踩坑两次), 一律用 grep+cut 双引号实现。
  # 优先尝试从 /proc/net/route 提取默认网关作 DNS(热点/路由器网关 DNS 通常可达,
  # 解决公网 UDP 53 被运营商屏蔽时的解析), 再兜底阿里/114/Google 公网 DNS。
  # 注: 部分 ROM 的容器侧 /proc/net 为空(拿不到网关), 此时直接走公网 DNS。
  GW_HEX=$(grep -m1 "00000000" /proc/net/route 2>/dev/null | cut -f3)
  GW_IP=""
  [ -n "$GW_HEX" ] && GW_IP=$(gw2ip "$GW_HEX")
  {
    [ -n "$GW_IP" ] && echo "nameserver $GW_IP"
    echo "nameserver 223.5.5.5"
    echo "nameserver 114.114.114.114"
    echo "nameserver 8.8.8.8"
  } > /etc/resolv.conf
  echo "[container] resolv.conf:"
  cat /etc/resolv.conf

  # 网络自检(仅定位用): 直连 IP 不走 DNS, 判断容器内 TCP 是否可达。
  # 若 TCP 可达而 DNS 失败 → 纯 DNS 问题; 若 TCP 也不可达 → 容器网络整体问题。
  echo "[container] netns inode: $(ls -l /proc/self/ns/net 2>&1)"
  echo "[container] 网络自检 TCP:"
  curl -sS -m 6 -o /dev/null -w "tcp223(ali-443)=%{http_code}\n" -k https://223.5.5.5/ 2>&1 || echo "tcp223=FAIL"
  curl -sS -m 6 -o /dev/null -w "tcp172(gw-80)=%{http_code}\n" http://172.20.10.1/ 2>&1 || echo "tcp172=FAIL"

  # ---------- 1. 容器内 apt 换清华源(http)：自动探测 Debian 版本代号 ----------
  CODENAME=$(grep -E "^VERSION_CODENAME=" /etc/os-release | cut -d= -f2 | tr -d "\"")
  [ -z "$CODENAME" ] && CODENAME=$(grep -E "^VERSION=" /etc/os-release | sed -E "s/.*\(([^)]+)\).*/\1/")
  [ -z "$CODENAME" ] && CODENAME="trixie"  # 兜底

  if [ -f /etc/apt/sources.list ] && [ ! -f /etc/apt/sources.list.bak ]; then
    cp /etc/apt/sources.list /etc/apt/sources.list.bak
  fi
  cat > /etc/apt/sources.list <<MIRROR_EOF
deb http://mirrors.tuna.tsinghua.edu.cn/debian/ $CODENAME main contrib non-free non-free-firmware
deb http://mirrors.tuna.tsinghua.edu.cn/debian/ $CODENAME-updates main contrib non-free non-free-firmware
deb http://mirrors.tuna.tsinghua.edu.cn/debian-security $CODENAME-security main contrib non-free non-free-firmware
MIRROR_EOF
  rm -f /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true
  echo "[init_container] Debian 源已切换为清华镜像 ($CODENAME, http)"

  # ---------- 1. 安装基础工具（spec：git curl adb；scrcpy 见文件头说明不装） ----------
  #    ca-certificates 最先装：装完才有 CA 证书，后续 git clone / curl 走 https 才正常
  #    libgl1/libglib2.0-0: OpenCV(cv2) 运行时需要 libGL.so.1 等, 不装则 uv run 报
  #    "ImportError: libGL.so.1: cannot open shared object file"
  #    网络容错：apt 偶发超时失败（第一遍失败、第二遍成功常见原因），失败自动重试最多 3 次。
  #    用 apt-get 而非 apt：apt 的 CLI 不保证脚本兼容，stdout 非 tty 时(本项目
  #    结尾做 2>&1 | tee 落盘)会打印 "apt does not have a stable CLI interface"
  #    警告，与成败无关却极易被当成报错。
  #    实测 2026-09：install 中途断网/被杀会留下 dpkg 半配置状态，之后 apt install
  #    一律报 "dpkg was interrupted" 并提示手动 configure -a；不修 dpkg 则三次重试
  #    必然全部失败、初始化直接中止。故每次尝试前先 configure -a（幂等，无需联网）。
  #    实测 2026-09 之二(更底层)：dpkg 写 /var/lib/dpkg/status 前会先建 status-old
  #    备份，走的是 link() 硬链接；而 proot 以 --link2symlink 运行(proot-distro
  #    默认), link() 被模拟成 symlink()，status-old 已存在(上次 apt 建有)时该调用
  #    直接 EPERM: "dpkg: error: error creating new backup file
  #    /var/lib/dpkg/status-old: Operation not permitted"，dpkg 退出，此错三次重试
  #    必然原样重现、与网络无关。status-old 只是备份文件，删掉即可(幂等)，故每次
  #    尝试前先 rm，让 dpkg 重建。
  #    注意: 上述 EPERM 发生在写备份那一步，说明 /var/lib/dpkg 目录本身可写，
  #    所以这不是权限问题、重装 dpkg 也没用，只与 status-old 残留有关。
  #    update 只做一次：它若绑在重试循环里，会把最慢的一步重复三遍。
  echo "[init_container] 容器内 apt update（清华源，首次约 1-2 分钟，期间无输出属正常）…"
  apt-get update </dev/null || echo "[init_container] apt update 失败,继续用现有索引尝试安装"

  APT_OK=0
  for attempt in 1 2 3; do
    echo "[init_container] 容器内 apt 安装基础工具(第 $attempt 次)…"
    rm -f /var/lib/dpkg/status-old /var/lib/dpkg/status-new 2>/dev/null || true
    dpkg --configure -a </dev/null >/dev/null 2>&1 || true
    if DEBIAN_FRONTEND=noninteractive apt-get install -y </dev/null \
        ca-certificates git curl adb libgl1 libglib2.0-0; then
      APT_OK=1
      break
    fi
    echo "[init_container] 第 $attempt 次失败,2 秒后重试…"
    sleep 2
  done
  [ "$APT_OK" = "1" ] || { echo "[init_container] apt 安装连续 3 次失败,初始化中止"; exit 1; }

  # ---------- 2. 安装 uv（spec：官方 install.sh；失败回退 GitHub release） ----------
  #    官方脚本：curl -LsSf https://astral.sh/uv/install.sh | sh
  #    回退原因：官方脚本在国内网络可能 403/超时，此时直接从 GitHub release 拉取
  #    静态二进制（uv 是静态链接，无需系统 Python），同样满足「不 apt 装 python3」。
  if ! command -v uv >/dev/null 2>&1 && [ ! -x /root/.local/bin/uv ]; then
    echo "[init_container] 安装 uv（官方脚本）..."
    curl -LsSf https://astral.sh/uv/install.sh | sh || {
      echo "[init_container] 官方脚本失败，回退 GitHub release 下载..."
      mkdir -p /root/.local/bin
      UV_DL_OK=0
      for base in \
        "https://github.com/astral-sh/uv/releases/latest/download" \
        "https://ghfast.top/https://github.com/astral-sh/uv/releases/latest/download"; do
        if curl -LsSf "$base/uv-aarch64-unknown-linux-gnu.tar.gz" -o /tmp/uv.tar.gz \
            && tar -xzf /tmp/uv.tar.gz -C /tmp \
            && cp /tmp/uv-aarch64-unknown-linux-gnu/uv /root/.local/bin/uv; then
          UV_DL_OK=1
          break
        fi
        echo "[init_container] 该源下载 uv 失败，换下一个..."
      done
      [ "$UV_DL_OK" != "1" ] && { echo "[init_container] uv 安装失败，初始化中止"; exit 1; }
    }
  fi
  export PATH="/root/.local/bin:$PATH"

  # ---------- 3. 克隆项目仓库到 /root/app（已存在则跳过） ----------
  #    GitHub 直连国内不稳定，失败时依次尝试 ghfast.top / gh-proxy.com 加速镜像；
  #    浅克隆（--depth 1）减少传输量。
  #    分支：App 的「切换分支」会把分支名写入 Termux home 的 repo_branch.txt，这里按它
  #    clone / 同步，便于在手机上直接初始化出 dev 等分支；文件缺失或名字含非法字符时用默认分支。
  #    case 的字符类已排除空格与 shell 元字符，避免脏数据被拼进 git 命令。
  KX_BRANCH_FILE=/data/data/duckyal.KaguraX/files/home/repo_branch.txt
  KX_BRANCH=""
  if [ -f "$KX_BRANCH_FILE" ]; then
    KX_BRANCH=$(head -n 1 "$KX_BRANCH_FILE" 2>/dev/null | tr -d " \r\n")
    case "$KX_BRANCH" in
      ""|*[!A-Za-z0-9._/-]*|[-/]*|*..*|*//*|*.) KX_BRANCH="" ;;
    esac
    [ -n "$KX_BRANCH" ] && echo "[init_container] 检测到分支记录, 将使用分支: $KX_BRANCH"
  fi
  # 可选参数单独放变量：有合法分支才带上 -b 分支名（空则克隆仓库默认分支）
  if [ -n "$KX_BRANCH" ]; then
    CLONE_BRANCH_ARGS="-b $KX_BRANCH"
  else
    CLONE_BRANCH_ARGS=""
  fi

  if [ ! -d /root/app ]; then
    CLONE_OK=0
    for url in \
      "https://github.com/Duckyal/KaguraX" \
      "https://ghfast.top/https://github.com/Duckyal/KaguraX" \
      "https://gh-proxy.com/https://github.com/Duckyal/KaguraX"; do
      echo "[init_container] git clone 尝试: $url"
      # shellcheck disable=SC2086
      if git clone --depth 1 $CLONE_BRANCH_ARGS "$url" /root/app; then
        CLONE_OK=1
        break
      fi
      echo "[init_container] 该源克隆失败，换下一个镜像..."
    done
    [ "$CLONE_OK" != "1" ] && { echo "[init_container] 所有源均克隆失败，初始化中止"; exit 1; }
  elif [ -n "$KX_BRANCH" ]; then
    # 仓库已存在且记录过分支: 切到该分支并同步最新(失败不中止, 保留现有代码继续 uv sync)
    echo "[init_container] /root/app 已存在，同步分支 $KX_BRANCH ..."
    if (cd /root/app && git fetch --depth 1 origin "$KX_BRANCH" \
        && git checkout -f -B "$KX_BRANCH" FETCH_HEAD); then
      echo "[init_container] 代码已同步到分支 $KX_BRANCH"
    else
      echo "[init_container] 分支同步失败(网络?)，保留现有代码继续..."
    fi
  else
    # 仓库已存在(重跑初始化): 同步最新代码, 避免旧 pyproject.toml/uv.lock 残留导致
    # 依赖解析到早已下架的包(如 opencv-python==5.0.0.93)。fetch/reset 失败不中止,
    # 保留现有代码继续 uv sync(依赖可能已可解析)。
    echo "[init_container] /root/app 已存在，同步最新代码(git fetch + reset)..."
    if (cd /root/app && git fetch --depth=1 origin 2>/dev/null) \
        && (cd /root/app && git reset --hard origin/HEAD 2>/dev/null); then
      echo "[init_container] 代码已同步到最新"
    else
      echo "[init_container] 同步失败(网络?)，保留现有代码继续..."
    fi
  fi

  # ---------- 4. 安装项目依赖：uv sync 依据 pyproject.toml 创建 .venv ----------
  #    国内访问 PyPI 官方源(files.pythonhosted.org)常遇 DNS/TLS 超时或解析失败,
  #    切换清华 PyPI 镜像(UV_DEFAULT_INDEX)同时解决解析与下载速度问题。
  export UV_DEFAULT_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
  # 2026-09 修复: proot 容器内 uv sync 反复失败(典型报错见下):
  #   Failed to copy file from .../archive-v0/.../_distutils_hack/override.py
  #   to .../builds-v0/...: No such file or directory
  # 根因: 中断的 sync 会在 ~/.cache/uv 留损坏条目(archive-v0/builds-v0), uv 误信
  # 缓存完整却找不到其中文件; uv cache clean 按包名偶发清不掉, 直接删目录最可靠。
  # 另 proot 下 flock 不可靠, uv 多线程并行解压/构建互相踩踏也产生随机 No such file,
  # 故串行构建/安装并用 copy 代替硬链接。
  export UV_LINK_MODE=copy
  export UV_CONCURRENT_BUILDS=1
  export UV_CONCURRENT_INSTALLS=1
  cd /root/app
  # 预清理易损缓存(每次 sync 前执行, 保证确定性): archive-v0=wheel 解压目录,
  # 装包/构建隔离都从它复制文件, 损坏高发; builds-v0=上次构建隔离环境残留。
  # wheels-v0(下载的原始 whl)与 python/(解释器)保留: 删 archive 后 uv 从本地
  # wheels-v0 重新解压即可, 基本无需再联网下载。兼容新旧 uv 目录命名。
  rm -rf /root/.cache/uv/archive-v0 /root/.cache/uv/builds-v0 \
         /root/.cache/uv/archive /root/.cache/uv/builds 2>/dev/null || true
  if ! uv sync; then
    echo "[init_container] uv sync 失败, 清理全部包缓存(保留 Python 解释器)后重试…"
    rm -rf /root/.cache/uv/wheels-v0 /root/.cache/uv/wheels 2>/dev/null \
      || rm -rf /root/.cache/uv
    if ! uv sync; then
      echo "[init_container] uv sync 重试仍失败, 整目录删除 uv 缓存做最后一次尝试…"
      rm -rf /root/.cache/uv
      if ! uv sync; then
        echo "[init_container] uv sync 连续失败, 初始化中止(可再点一次一键初始化重试)"
        exit 1
      fi
    fi
  fi
  # 中断残留会在 site-packages 留下空包目录(如 fastapi/), uv 按 dist-info 标记
  # 认为已装好不会重装, 运行时 import 报 cannot import name FastAPI from fastapi
  # (unknown location)。同步后再做一次真实导入校验, 失败则重建 .venv(2026-09)。
  if ! .venv/bin/python -c "import fastapi, uvicorn, uiautomator2" 2>/dev/null; then
    echo "[init_container] .venv 依赖不完整(疑似中断残留), 重建虚拟环境…"
    rm -rf /root/app/.venv
    if ! uv sync; then
      echo "[init_container] uv sync 重建 .venv 失败, 初始化中止"
      exit 1
    fi
  fi

  # ---------- 5. 记录版本：commit SHA + 当前分支写入 Termux home ----------
  #    App 读 repo_version.txt 做「是否有新提交」对比，读 repo_branch.txt 显示当前分支。
  git rev-parse HEAD > /data/data/duckyal.KaguraX/files/home/repo_version.txt
  git rev-parse --abbrev-ref HEAD > /data/data/duckyal.KaguraX/files/home/repo_branch.txt

  echo "[init_container] 初始化完成!项目已就绪,可以点「启动项目」运行。"
' 2>&1 | tee "$HOME/init_run.log" || {
  echo "[init_container] 容器登录或初始化失败,完整日志见 ~/init_run.log"
  exit 1
}

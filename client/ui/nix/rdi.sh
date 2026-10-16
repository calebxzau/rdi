#!/bin/sh
# RDI启动程序（Linux/macOS）：更新UI库，然后用JDK25启动RDI。
# 用法：sh rdi.sh [--no-update] [--debug] [--app-logs] [--select-java] [--solid-window] [--jvmArg=<参数>]...
set -eu

OFFICIAL_URL="https://rdi.calebxzhou.cn:65331"
# RDI_DEBUG_URL points --debug at another test server.
DEBUG_URL="${RDI_DEBUG_URL:-http://127.0.0.1:65231}"
MAIN_CLASS="calebxzau.rdi.client.MainKt"
LOCK_DIR=".rdi-update.lock"
JAVA_HOME_FILE=".rdi-java"

info() { printf '%s\n' "$*"; }
warn() { printf '警告：%s\n' "$*" >&2; }
die() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

# Single-quotes one word so a list of words can be rebuilt later with eval "set -- ...".
quote() {
    printf "'%s' " "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

ORIGINAL_ARGS=""
for argument in "$@"; do
    ORIGINAL_ARGS="${ORIGINAL_ARGS}$(quote "$argument")"
done

NO_UPDATE=false
DEBUG=false
APP_LOGS=false
SELECT_JAVA=false
SOLID_WINDOW=false
USER_JVM_ARGS=""
for argument in "$@"; do
    case "$argument" in
        --no-update) NO_UPDATE=true ;;
        --debug) DEBUG=true ;;
        --app-logs) APP_LOGS=true ;;
        --select-java) SELECT_JAVA=true ;;
        --solid-window) SOLID_WINDOW=true ;;
        --jvmArg=*) USER_JVM_ARGS="${USER_JVM_ARGS}$(quote "${argument#--jvmArg=}")" ;;
        -h | --help)
            sed -n '2,3p' "$0"
            exit 0
            ;;
        *) die "未知参数：${argument}（可用参数见sh rdi.sh --help）" ;;
    esac
done

# Run from the script's own directory, so user.dir matches the Windows launcher.
if [ -n "${RDI_DEBUG_URL:-}" ] && [ "$DEBUG" = false ]; then
    warn "设置了RDI_DEBUG_URL，但没有使用--debug，将连接正式服务器"
fi

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
SCRIPT="${ROOT}/$(basename -- "$0")"
cd "$ROOT"

# ---------- 依赖与平台 ----------

for tool in uname sed awk grep mkdir mv rm cp sort; do
    command -v "$tool" >/dev/null 2>&1 || die "缺少系统命令${tool}，请先安装"
done
if command -v curl >/dev/null 2>&1; then
    HTTP_TOOL=curl
elif command -v wget >/dev/null 2>&1; then
    HTTP_TOOL=wget
else
    die "缺少curl或wget，请先安装其中一个（例如sudo apt install curl）"
fi
if command -v sha1sum >/dev/null 2>&1; then
    SHA1_TOOL=sha1sum
elif command -v shasum >/dev/null 2>&1; then
    SHA1_TOOL=shasum
else
    die "缺少sha1sum或shasum，请先安装coreutils"
fi

# Exit status 0 when version $1 >= $2 (dotted numbers).
version_at_least() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        n = split(a, x, "."); m = split(b, y, ".")
        for (i = 1; i <= (n > m ? n : m); i++) {
            if ((x[i] + 0) > (y[i] + 0)) exit 0
            if ((x[i] + 0) < (y[i] + 0)) exit 1
        }
        exit 0
    }'
}

detect_platform() {
    os=$(uname -s)
    arch=$(uname -m)
    case "$os/$arch" in
        Linux/x86_64 | Linux/amd64)
            PLATFORM=linux-x64
            JAVA_ARCH=amd64
            ;;
        Darwin/arm64)
            PLATFORM=macos-arm64
            JAVA_ARCH=aarch64
            ;;
        Darwin/x86_64)
            # A terminal running under Rosetta still reports x86_64 on Apple Silicon.
            if [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = 1 ]; then
                PLATFORM=macos-arm64
                JAVA_ARCH=aarch64
            else
                die "不支持Intel芯片的Mac，RDI只支持Apple Silicon（M系列芯片）的Mac"
            fi
            ;;
        *) die "不支持的系统：${os} ${arch}。RDI支持Windows x64、Linux x64和Apple Silicon的Mac" ;;
    esac

    # FFmpeg natives need these system versions.
    if [ "$PLATFORM" = macos-arm64 ]; then
        mac_version=$(sw_vers -productVersion 2>/dev/null || echo 0)
        version_at_least "$mac_version" 15 || die "需要macOS 15或更高版本（当前${mac_version}），请先升级系统"
    else
        glibc=$(getconf GNU_LIBC_VERSION 2>/dev/null | awk '{print $2}')
        [ -n "$glibc" ] || die "不支持非glibc的Linux发行版（如Alpine），请使用Ubuntu、Debian、Fedora、Arch等"
        version_at_least "$glibc" 2.35 ||
            die "系统版本过旧（glibc ${glibc}），需要Ubuntu 22.04、Debian 12、Fedora 36或更新的发行版"
    fi
}

detect_platform

if [ "$PLATFORM" = macos-arm64 ]; then
    # Browsers mark downloaded files as quarantined; the script itself must not stay blocked.
    xattr -d com.apple.quarantine "$SCRIPT" 2>/dev/null || true
fi

# ---------- HTTP ----------

if [ "$HTTP_TOOL" = wget ] && wget --help 2>&1 | grep -q -- '--show-progress'; then
    WGET_PROGRESS="--show-progress"
else
    WGET_PROGRESS=""
fi

# Prints the body of URL $1; fails on HTTP errors. $2 is the timeout in seconds.
http_text() {
    if [ "$HTTP_TOOL" = curl ]; then
        curl -fsSL --connect-timeout 10 --max-time "${2:-30}" "$1"
    else
        wget -q -T "${2:-30}" -O - "$1"
    fi
}

# Downloads URL $1 to file $2 with a progress bar.
http_download() {
    if [ "$HTTP_TOOL" = curl ]; then
        curl -fL --connect-timeout 10 --progress-bar -o "$2" "$1"
    elif [ -n "$WGET_PROGRESS" ]; then
        wget -q "$WGET_PROGRESS" -T 30 -O "$2" "$1"
    else
        wget -q -T 30 -O "$2" "$1"
    fi
}

sha1_of() {
    if [ "$SHA1_TOOL" = sha1sum ]; then
        sha1sum -- "$1" | awk '{print $1}'
    else
        shasum -a 1 -- "$1" | awk '{print $1}'
    fi
}

# ---------- 更新锁 ----------

LOCK_HELD=false
release_lock() {
    if [ "$LOCK_HELD" = true ]; then
        rm -rf "$LOCK_DIR"
        LOCK_HELD=false
    fi
}

acquire_lock() {
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        owner=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "")
        if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then
            die "另一个启动程序正在更新，请等它完成后再启动"
        fi
        # The owner died without cleaning up.
        rm -rf "$LOCK_DIR"
        mkdir "$LOCK_DIR" 2>/dev/null || die "无法创建更新锁${LOCK_DIR}"
    fi
    LOCK_HELD=true
    echo "$$" >"$LOCK_DIR/pid"
}

trap 'release_lock' EXIT
trap 'release_lock; exit 130' INT
trap 'release_lock; exit 143' TERM

# ---------- 选择API ----------

select_apis() {
    if [ "$DEBUG" = true ]; then
        APIS="$DEBUG_URL"
        return
    fi
    APIS="$OFFICIAL_URL"
    my_ip=$(http_text "https://ip.3322.net/" 5 2>/dev/null | tr -d '[:space:]' || true)
    if ! printf '%s' "$my_ip" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
        my_ip=""
    fi
    entry_url="${OFFICIAL_URL}/server-entry?format=text"
    if [ -n "$my_ip" ]; then entry_url="${entry_url}&myIp=${my_ip}"; fi
    backup=$(http_text "$entry_url" 10 2>/dev/null | tr -d '[:space:]' || true)
    # An error answer is JSON, never a URL.
    if printf '%s' "$backup" | grep -Eq '^https?://[^[:space:]]+$'; then
        backup=${backup%/}
        if [ "$backup" != "$OFFICIAL_URL" ]; then
            APIS="$backup
$OFFICIAL_URL"
        fi
    fi
}

# ---------- 脚本自更新 ----------

update_script() {
    if [ "${RDI_SCRIPT_UPDATED:-}" = 1 ]; then return 0; fi
    local_hash=$(sha1_of "$SCRIPT")
    for api in $APIS; do
        remote_hash=$(http_text "${api}/update/script/${PLATFORM}/hash" 10 2>/dev/null | tr -d '[:space:]' || true)
        printf '%s' "$remote_hash" | grep -Eq '^[0-9a-f]{40}$' || continue
        if [ "$remote_hash" = "$local_hash" ]; then return 0; fi
        info "正在更新启动脚本"
        if http_text "${api}/update/script/${PLATFORM}" 30 >"${SCRIPT}.new" 2>/dev/null &&
            [ "$(sha1_of "${SCRIPT}.new")" = "$remote_hash" ]; then
            chmod 755 "${SCRIPT}.new"
            # The running shell keeps reading the old inode, so replacing the file is safe.
            mv "${SCRIPT}.new" "$SCRIPT"
            release_lock
            RDI_SCRIPT_UPDATED=1
            export RDI_SCRIPT_UPDATED
            eval "exec sh \"\$SCRIPT\" $ORIGINAL_ARGS"
        fi
        rm -f "${SCRIPT}.new"
        warn "启动脚本更新失败，继续使用当前版本"
        return 0
    done
}

# ---------- UI库更新 ----------

# Restores lib/ after an update that was interrupted between its two renames.
recover_libraries() {
    for leftover in lib.updating-*; do
        if [ -e "$leftover" ]; then rm -rf "$leftover"; fi
    done
    if [ ! -d lib ]; then
        # Update ids start with a timestamp, so the last match in glob order is the newest.
        previous=""
        for candidate in lib.previous-*; do
            if [ -d "$candidate" ]; then previous=$candidate; fi
        done
        if [ -n "$previous" ]; then
            warn "上次更新中断，正在恢复旧的UI库"
            mv "$previous" lib
        fi
    fi
    for leftover in lib.previous-*; do
        if [ -e "$leftover" ]; then rm -rf "$leftover"; fi
    done
}

# Fetches version and manifest from the first API that answers; sets VERSION and MANIFEST_FILE.
fetch_release() {
    MANIFEST_FILE="$LOCK_DIR/manifest.txt"
    for api in $APIS; do
        version=$(http_text "${api}/update/ui/p/${PLATFORM}/ver?format=text" 15 2>/dev/null | tr -d '[:space:]' || true)
        printf '%s' "$version" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*$' || continue
        http_text "${api}/update/ui/p/${PLATFORM}/libs/${version}?format=text" 30 >"$MANIFEST_FILE" 2>/dev/null || continue
        # Every line is "<sha1> <file name>"; names stay inside lib/.
        if [ -s "$MANIFEST_FILE" ] &&
            ! grep -Evq '^[0-9a-f]{40} [^/\\:]+$' "$MANIFEST_FILE" &&
            ! grep -Eq ' \.\.?$' "$MANIFEST_FILE" &&
            grep -q ' rdi-ui\.jar$' "$MANIFEST_FILE"; then
            VERSION="$version"
            return 0
        fi
    done
    return 1
}

# Writes "<sha1> <name>" for every file in lib/, sorted like the manifest.
hash_local_libraries() {
    : >"$LOCAL_HASHES"
    [ -d lib ] || return 0
    for file in lib/* lib/.[!.]*; do
        [ -f "$file" ] || continue
        printf '%s %s\n' "$(sha1_of "$file")" "${file#lib/}" >>"$LOCAL_HASHES"
    done
    LC_ALL=C sort -o "$LOCAL_HASHES" "$LOCAL_HASHES"
}

download_library() {
    name=$1
    expected=$2
    target=$3
    for api in $APIS; do
        if http_download "${api}/update/ui/p/${PLATFORM}/lib/${VERSION}/${name}" "${target}.part" &&
            [ "$(sha1_of "${target}.part")" = "$expected" ]; then
            mv "${target}.part" "$target"
            return 0
        fi
        rm -f "${target}.part"
    done
    return 1
}

update_libraries() {
    if ! fetch_release; then
        warn "无法获取UI库信息，将使用本地版本启动"
        return 0
    fi
    LC_ALL=C sort -o "$MANIFEST_FILE" "$MANIFEST_FILE"
    LOCAL_HASHES="$LOCK_DIR/local.txt"
    hash_local_libraries
    if cmp -s "$MANIFEST_FILE" "$LOCAL_HASHES"; then
        info "UI库已是最新版本${VERSION}"
        return 0
    fi
    info "正在更新UI库到${VERSION}"

    update_id="$(date +%Y%m%d%H%M%S)-$$"
    staging="lib.updating-${update_id}"
    mkdir "$staging"
    # Read the manifest on fd 3 so downloaders cannot consume the loop's input.
    exec 3<"$MANIFEST_FILE"
    while read -r expected name <&3; do
        if grep -qxF "$expected $name" "$LOCAL_HASHES"; then
            cp -p "lib/${name}" "${staging}/${name}"
            continue
        fi
        info "正在更新${name}"
        if ! download_library "$name" "$expected" "${staging}/${name}"; then
            exec 3<&-
            rm -rf "$staging"
            warn "${name}下载或校验失败，将使用本地版本启动"
            return 0
        fi
    done
    exec 3<&-

    if [ -d lib ]; then
        mv lib "lib.previous-${update_id}"
    fi
    if ! mv "$staging" lib; then
        if [ -d "lib.previous-${update_id}" ]; then mv "lib.previous-${update_id}" lib; fi
        rm -rf "$staging"
        warn "UI库切换失败，将使用本地版本启动"
        return 0
    fi
    rm -rf "lib.previous-${update_id}"
    info "UI库更新完成"
}

acquire_lock
recover_libraries
if [ "$NO_UPDATE" = false ]; then
    select_apis
    update_script
    update_libraries
fi
release_lock

[ -f lib/rdi-ui.jar ] || die "缺少UI库文件，请检查网络后重试"

# ---------- 查找JDK25 ----------

WRONG_ARCH_JDK=""

# Exit status 0 when $1 is a full JDK 25 home for this platform's architecture.
is_jdk25() {
    home=$1
    [ -x "${home}/bin/java" ] && [ -x "${home}/bin/javac" ] || return 1
    properties=$("${home}/bin/java" -XshowSettings:properties -version 2>&1) || return 1
    spec=$(printf '%s\n' "$properties" | sed -n 's/^ *java\.specification\.version = //p' | head -n 1)
    [ "$spec" = 25 ] || return 1
    case "$("${home}/bin/javac" -version 2>&1)" in
        "javac 25"*) ;;
        *) return 1 ;;
    esac
    java_arch=$(printf '%s\n' "$properties" | sed -n 's/^ *os\.arch = //p' | head -n 1)
    if [ "$java_arch" != "$JAVA_ARCH" ]; then
        WRONG_ARCH_JDK="$home"
        return 1
    fi
    return 0
}

# Prints candidate JDK homes, most specific first.
jdk_candidates() {
    if [ -n "${RDI_JAVA_HOME:-}" ]; then printf '%s\n' "$RDI_JAVA_HOME"; fi
    if [ "$SELECT_JAVA" = false ] && [ -f "$JAVA_HOME_FILE" ]; then
        head -n 1 "$JAVA_HOME_FILE"
    fi
    if [ -n "${JAVA_HOME:-}" ]; then printf '%s\n' "$JAVA_HOME"; fi
    if [ "$PLATFORM" = macos-arm64 ] && [ -x /usr/libexec/java_home ]; then
        /usr/libexec/java_home -v 25 2>/dev/null || true
    fi
    if command -v java >/dev/null 2>&1; then
        java_path=$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)
        dirname -- "$(dirname -- "$java_path")"
    fi
    if [ "$PLATFORM" = macos-arm64 ]; then
        for home in /Library/Java/JavaVirtualMachines/*/Contents/Home \
            "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
            "$HOME"/.sdkman/candidates/java/*; do
            if [ -d "$home" ]; then printf '%s\n' "$home"; fi
        done
    else
        for home in /usr/lib/jvm/* /opt/*jdk* "$HOME"/.sdkman/candidates/java/* "$HOME"/.jdks/*; do
            if [ -d "$home" ]; then printf '%s\n' "$home"; fi
        done
    fi
    return 0
}

find_jdk25() {
    jdk_candidates >"${ROOT}/.rdi-java-candidates.$$"
    JDK_HOME=""
    while IFS= read -r candidate; do
        [ -n "$candidate" ] || continue
        if is_jdk25 "$candidate"; then
            JDK_HOME="$candidate"
            break
        fi
    done <"${ROOT}/.rdi-java-candidates.$$"
    rm -f "${ROOT}/.rdi-java-candidates.$$"
}

find_jdk25
if [ -z "$JDK_HOME" ]; then
    if [ -n "$WRONG_ARCH_JDK" ]; then
        die "找到的JDK25（${WRONG_ARCH_JDK}）不是本机架构的版本。请安装arm64版（Apple Silicon）JDK25，例如brew install --cask temurin@25"
    fi
    cat >&2 <<'EOF'
错误：未找到可用的JDK25（需要完整JDK，主版本号25）。
macOS:  brew install --cask temurin@25    或从 https://adoptium.net 下载 .pkg
Ubuntu/Debian: sudo apt install openjdk-25-jdk
Fedora: sudo dnf install java-25-openjdk-devel
Arch: sudo pacman -S jdk25-openjdk
已安装在其他位置时：RDI_JAVA_HOME=/path/to/jdk sh rdi.sh
EOF
    exit 1
fi
printf '%s\n' "$JDK_HOME" >"$JAVA_HOME_FILE"
info "将使用Java25启动：${JDK_HOME}"

# ---------- 启动 ----------

if [ "$DEBUG" = true ]; then
    SERVER_URL="$DEBUG_URL"
else
    SERVER_URL="$OFFICIAL_URL"
fi
# A debug master on another machine is reached over plain HTTP: its certificate only covers 127.0.0.1.
USE_HTTP=false
if [ "$DEBUG" = true ] && [ -n "${RDI_DEBUG_URL:-}" ]; then
    USE_HTTP=true
fi

eval "set -- $USER_JVM_ARGS"
set -- -Dfile.encoding=UTF-8 "$@" -Drdi.debug="$DEBUG"
if [ "$NO_UPDATE" = true ]; then set -- "$@" -Drdi.noUpdate=true; fi
if [ "$SOLID_WINDOW" = true ]; then set -- "$@" -Drdi.window.transparent=false; fi
if [ "$USE_HTTP" = true ]; then set -- "$@" -Drdi.noHttps=true; fi
set -- "$@" -Drdi.updater.islogmode="$APP_LOGS" -Drdi.server.url="$SERVER_URL" \
    -cp "lib/*" --enable-native-access=ALL-UNNAMED "$MAIN_CLASS"

if [ "$APP_LOGS" = true ]; then
    exec "${JDK_HOME}/bin/java" "$@"
fi

LOG_DIR="${HOME}/.rdi/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="${LOG_DIR}/launcher-stdout.log"
nohup "${JDK_HOME}/bin/java" "$@" >"$LOG_FILE" 2>&1 &
client_pid=$!
sleep 3
if kill -0 "$client_pid" 2>/dev/null; then
    info "RDI已启动，日志：${LOG_FILE}"
    exit 0
fi
exit_code=0
wait "$client_pid" || exit_code=$?
warn "RDI启动后很快退出（退出码${exit_code}），日志末尾："
tail -n 20 "$LOG_FILE" >&2
exit "$exit_code"

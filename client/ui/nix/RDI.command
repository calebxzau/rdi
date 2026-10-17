#!/bin/sh
# macOS：双击运行RDI。会在终端窗口里执行rdi.sh，出错时提示会留在窗口里。
cd "$(dirname "$0")" || exit 1
exec sh ./rdi.sh "$@"

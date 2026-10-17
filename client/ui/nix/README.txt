rdi多人房间系统Linux / macOS

【系统要求】
- Linux：x64，
Ubuntu 22.04、Debian 12、Fedora 36或更新的发行版（glibc 2.35+）
需要桌面环境(gui)
gui要有桌面合成器(k11 xde gnome wayland...)
- macOS：苹果cpu(m系列），macOS 15/+
- 已安装java25（JDK）并且在$PATH里

【安装java25】
- Ubuntu/Debian：sudo apt install openjdk-25-jdk
- Fedora：sudo dnf install java-25-openjdk-devel
- Arch：sudo pacman -S jdk25-openjdk
- macOS：brew install --cask temurin@25

或从https://adoptium.net 下载arm64版的.pkg

【启动】

 sh rdi.sh

macOS也可以双击RDI.command启动。第一次双击如果提示"无法验证开发者"，
先在"终端"里执行一次 sh rdi.sh（之后双击就不再提示），
或者到"系统设置 → 隐私与安全性"里点"仍要打开"。

 

【常用参数】
--solid-window   使用不透明窗口（窗口显示异常时使用）


java25装在其他位置时：
    RDI_JAVA_HOME=/path/to/jdk sh rdi.sh

【卸载】
删除整个目录即可

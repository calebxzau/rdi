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

 

【常用参数】
--solid-window   使用不透明窗口（窗口显示异常时使用）


java25装在其他位置时：
    RDI_JAVA_HOME=/path/to/jdk sh rdi.sh

【卸载】
删除整个目录即可

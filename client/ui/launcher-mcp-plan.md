# 启动器MCP计划

状态：方案已写好，尚未批准，尚未实现（2026-11-04）。

## 目标

玩家点击标题栏设置图标旁边的MCP图标，就能启动启动器MCP，并一键复制提示词。把提示词粘贴给本机Agent后，Agent可以替玩家查看房间、下载整合包、进入游戏。

现状：游戏内已有RMCP（`mc/rmcp`，提供`screen_*`、`command`等工具），启动器还没有MCP服务，所以Agent管不了房间、整合包和启动。

## 开始前需要确认

1. 点击图标后直接启动并复制提示词。备选做法是先弹菜单，再让玩家点“启动”，但会多一步。建议直接启动，因为真正有影响的操作还有弹窗确认。
2. 第一期工具范围是否够用（见下方工具表）。

## 玩家体验

图标放在标题栏设置图标的左边（当前位置`src/main/kotlin/calebxzau/rdi/client/ui/window/WindowChrome.kt:255`），使用现有的`ChromeIconButton`，运行中时叠加绿点。

| 当前状态 | 点击图标后 |
|---|---|
| 未启动 | 启动MCP并复制提示词，提示“MCP已启动，提示词已复制” |
| 运行中 | 打开菜单：状态、复制提示词、停止MCP |
| 启动失败 | 显示错误原因，不复制提示词 |

- 重开启动器后默认关闭，需要再点一次才启动。
- 端口和token保存在本地，Agent配置一次后无需重配。
- 只有能访问本机的Agent才能使用，例如Claude Code、Cursor。网页版聊天AI连不上。
- 面向玩家的文字使用房间、整合包等说法，不暴露后端细节。

## 提示词模板

```text
请把RDI启动器添加为MCP服务器，然后帮我操作它。
地址：http://127.0.0.1:{端口}/mcp
传输方式：Streamable HTTP
请求头：Authorization: Bearer {token}
如果需要重启客户端才能生效，请告诉我。
连接后先调用get_status。不要把token发到别处。
```

## 第一期工具

| 工具 | 作用 | 确认方式 |
|---|---|---|
| `get_status` | 查看登录状态、运行中的游戏、下载任务 | 不需要 |
| `list_hosts` / `get_host` | 查看我的房间和房间详情 | 不需要 |
| `play_host` | 启动房间并进入游戏 | 启动器弹窗，可选“本次都允许” |
| `install_host_modpack` | 房间缺整合包时下载 | 同上 |

- 未登录时，工具返回“请先在启动器登录”。
- `play_host`需要把`StartPlayResult`的各个结果都告诉Agent：`Ready`进入游戏，`NeedInstall`提示调用`install_host_modpack`，`Installing`返回任务ID，`NeedMod`返回缺少的Mod。
- `startHostPlay`会同时启动房间（`startPlay`的`startHost`默认为true），确认弹窗的文案要说明这一点。

删除房间、上传整合包和修改权限不在第一期。

## 安全要求

启动器掌握玩家账号，不照搬RMCP的“无token、不校验Origin”。

- 只监听`127.0.0.1`。
- 校验Bearer token和Origin，防止恶意网页通过浏览器访问本地端口。
- 确认必须在启动器弹窗里完成，不能靠Agent转述“用户同意了”，否则提示注入可以绕过确认。
- 不返回token或密码，不提供任意HTTP、文件读写工具。
- master的权限校验保持不变。

## 实现

所有新代码放在新包`calebxzau.rdi.client.mcp`，都在`client/ui`模块内。

1. `LauncherMcpServer`：HTTP服务、token和Origin校验、MCP会话。
2. 精简的JSON-RPC协议层。不复用`mc/rmcp`，因为它绑定了`McpGameInterface`和游戏侧的打包方式；可以参考`mc/rmcp/client/.../mcp/standard/McpProtocol.kt`和`JsonRpc.kt`，以及其中的协议版本`2025-11-25`。
3. `LauncherMcpTools`：通过可替换的gateway调用现有能力，包括`host/my`接口、`startHostPlay`和`ClientTaskManager`。
4. `LauncherMcpController`：启动和停止、保存token和端口、生成提示词、发出确认请求和进入游戏事件。可失败的操作返回`Result`。
5. `window/McpMenu.kt`：图标和菜单，可参考同目录的`TaskMenu.kt`；在`WindowChrome.kt`放入口。只使用Material 3。
6. `Main.kt`：接收MCP事件并弹出确认框，再走现有的`McPlayStore.pendingLaunch`和`McPlayRoute`（参考`AppNavigation.kt:181`）。现在进入游戏靠UI导航，所以工具不能直接启动。

### 实现时要确认

- HTTP服务优先用JDK自带的`com.sun.net.httpserver`，不增加依赖。要确认发布版运行时包含`jdk.httpserver`模块；如果没有，改用`ktor-server-cio`，版本与现有Ktor 3.6.1保持一致。
- 图标字形：`IconFontFamily`里是否有合适的字形。候选是机器人图标，实现时渲染确认。
- token和端口的本地保存位置。token用安全随机数生成，端口被占用时换一个新端口并重新提示复制。

## 验证

- 单元测试（`*Test.kt`）：缺少或错误token、非法Origin、`initialize`、`tools/list`、工具结果映射、提示词生成。
- 真实流程：用Claude Code粘贴提示词接入，再走一遍查房间、确认、进入游戏。进游戏那一步需要显示器开着。
- Gradle按`AGENTS.md`运行：优先IntelliJ MCP，失败时用Windows `pwsh.exe`和模块内的`gradlew.bat`。

## 后续阶段

- 第二期：创建房间，以及由启动器转发游戏内RMCP工具（游戏MCP端口不固定，玩家不应该每次重新配置）。
- 最后：删除和上传这类高风险操作，每次都弹窗确认。
- 如需支持只能走stdio的客户端，再加一个小的stdio转接程序。
- 单独事项：RMCP的`StandardMcpServer.kt`没有Origin和token校验，而`command`工具能执行游戏命令，建议补上。这会改变2026-10-26的决定，需要先经用户确认。

package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceType(val capabilities: WorkspaceCapabilities) {
    /** 本机 PRoot Rootfs */
    LOCAL(WorkspaceCapabilities(rootfs = true, terminal = true, localFiles = true, fileWriteTools = true)),

    /** 通过 SSH 连接的远程机器 */
    SSH(WorkspaceCapabilities(rootfs = false, terminal = true, localFiles = false, fileWriteTools = true)),
}

/** 一种工作区能用哪些功能; 调用方按能力判断, 不直接比较类型 */
data class WorkspaceCapabilities(
    /** 运行在本机 PRoot Rootfs 里: 需要安装 Rootfs, 挂载了 /skills、/upload、/tool_outputs 等应用目录 */
    val rootfs: Boolean,
    /** 支持交互式终端 */
    val terminal: Boolean,
    /** 文件在本机; 否则每次访问都要走网络, 也没法交给系统文件选择器 */
    val localFiles: Boolean,
    /**
     * AI 可以用写文件、编辑文件工具直接写入; 否则只能经 shell 修改文件。
     * SSH 工具使用版本校验、临时文件和原子发布，不能直接调用会截断原文件的 SFTP 覆盖写入。
     * 服务器缺少必要扩展时必须拒绝对应写入，不降级为不安全覆盖。
     */
    val fileWriteTools: Boolean,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

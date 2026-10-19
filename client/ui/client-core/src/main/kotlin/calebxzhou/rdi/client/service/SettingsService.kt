package calebxzhou.rdi.client.service

import calebxzau.rdi.client.CONF
import calebxzhou.rdi.client.AppConfig
import calebxzau.rdi.client.platform.readTotalPhysicalMemoryMb
import calebxzau.rdi.client.platform.validateJdkExecutablePath
import calebxzhou.rdi.common.ProxyConfig

object SettingsService {

    data class ValidationResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Get total physical memory in MB
     */
    fun getTotalPhysicalMemoryMb(): Int = readTotalPhysicalMemoryMb()

    /**
     * Validate memory settings
     */
    fun validateMemory(maxMemoryText: String, totalMemoryMb: Int): ValidationResult {
        if (maxMemoryText.isBlank()) {
            return ValidationResult(true)
        }

        val memoryValue = maxMemoryText.trim().toIntOrNull()
            ?: return ValidationResult(false, "最大内存格式不正确")

        if (memoryValue == 0) {
            return ValidationResult(true)
        }

        if (memoryValue <= 4096) {
            return ValidationResult(false, "最大内存必须大于4096MB")
        }

        if (totalMemoryMb in 1..memoryValue) {
            return ValidationResult(false, "最大内存必须小于总内存 ${totalMemoryMb}MB")
        }

        return ValidationResult(true)
    }

    /**
     * Validate proxy port
     */
    fun validateProxyPort(proxyPortText: String): ValidationResult {
        if (proxyPortText.isBlank()) {
            return ValidationResult(true)
        }

        val port = proxyPortText.trim().toIntOrNull()
            ?: return ValidationResult(false, "代理端口格式不正确")

        if (port !in 1..65535) {
            return ValidationResult(false, "代理端口必须在1-65535之间")
        }

        return ValidationResult(true)
    }

    fun validateJdkPath(rawPath: String): Result<String> =
        validateJdkExecutablePath(rawPath)

    /**
     * Save settings configuration
     */
    suspend fun saveSettings(
        preferModMirror: Boolean,
        preferMcMirror: Boolean,
        maxMemoryText: String,
        proxyEnabled: Boolean,
        proxySystem: Boolean,
        proxyHost: String,
        proxyPortText: String,
        proxyUsr: String,
        proxyPwd: String,
        solidWindow: Boolean
    ): Result<Unit> = runCatching {
        val memoryValue = maxMemoryText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val proxyPort = proxyPortText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

        val config = AppConfig(
            preferModMirror = preferModMirror,
            preferMcMirror = preferMcMirror,
            maxMemory = memoryValue ?: 0,
            proxyConfig = ProxyConfig(
                enabled = proxyEnabled,
                systemProxy = proxySystem,
                host = proxyHost,
                port = proxyPort ?: 10808,
                usr = proxyUsr.takeIf { it.isNotBlank() },
                pwd = proxyPwd.takeIf { it.isNotBlank() }
            ),
            pinyinName = CONF.pinyinName,
            solidWindow = solidWindow
        )
        AppConfig.save(config)
    }

    /**
     * Validate profile change
     */
    fun validateProfileChange(name: String, pwd: String, currentName: String): ValidationResult {
        val nameBytes = name.toByteArray(Charsets.UTF_8).size
        if (nameBytes !in 3..24) {
            return ValidationResult(false, "昵称须在3~24个字节，当前为${nameBytes}")
        }

        if (pwd.isNotEmpty() && pwd.length !in 6..16) {
            return ValidationResult(false, "密码长度须在6~16个字符")
        }

        if (name == currentName && pwd.isEmpty()) {
            return ValidationResult(false, "没有修改内容")
        }

        return ValidationResult(true)
    }
}

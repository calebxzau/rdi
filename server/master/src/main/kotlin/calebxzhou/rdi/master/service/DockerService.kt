package calebxzhou.rdi.master.service

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.master.CONF
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback.Adapter
import com.github.dockerjava.api.model.*
import com.github.dockerjava.api.model.PortBinding.parse
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import java.io.Closeable

object DockerService {
    private val lgr by Loggers

    private val client: DockerClient by lazy {
        val dockerConfig = CONF.docker
        val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("tcp://${dockerConfig.host}:${dockerConfig.port}")
            .withApiVersion(dockerConfig.apiVersion)

        if (dockerConfig.tlsEnabled) {
            configBuilder.withDockerTlsVerify(dockerConfig.tlsVerify)
            dockerConfig.certPath.takeIf { it.isNotBlank() }?.let { configBuilder.withDockerCertPath(it) }
        } else {
            configBuilder.withDockerTlsVerify(false)
        }

        val config = configBuilder.build()
        val httpClient: DockerHttpClient = OkDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .connectTimeout(300_000)
            .readTimeout(300_000)
            .build()

        DockerClientBuilder.getInstance(config)
            .withDockerHttpClient(httpClient)
            .build()
    }


    fun findContainer(containerName: String, includeStopped: Boolean = true) =
        client.listContainersCmd()
            .withNameFilter(listOf(containerName))
            .withShowAll(includeStopped)
            .exec().firstOrNull()

    fun listContainers(includeStopped: Boolean = true) =
        client.listContainersCmd()
            .withShowAll(includeStopped)
            .exec()

    fun getContainerStatusSnapshot(): Map<String, HostStatus>? {
        return try {
            listContainers(includeStopped = true)
                .mapNotNull { container ->
                    val name = container.names?.firstOrNull()?.removePrefix("/") ?: return@mapNotNull null
                    name to parseContainerStatus(container.state)
                }
                .toMap()
        } catch (e: Exception) {
            lgr.warn { "Error getting container status snapshot: ${e.message}" }
            null
        }
    }

    internal fun parseContainerStatus(state: String?): HostStatus = when (state?.lowercase()) {
        "running" -> HostStatus.STARTED
        "paused" -> HostStatus.PAUSED
        "exited", "created", "dead", "removing", "stopped" -> HostStatus.STOPPED
        null -> HostStatus.UNKNOWN
        else -> HostStatus.UNKNOWN
    }
    /**
     * Limit CPU after container has started
     * @param cpuQuota CPU quota in microseconds (100000 = 1 CPU core)
     * @param cpuPeriod CPU period in microseconds (default 100000)
     */
    fun limitCpu(containerName: String, cpuQuota: Long, cpuPeriod: Long = 100_000L) {
        findContainer(containerName)?.run {
            client.updateContainerCmd(id)
                .withCpuQuota(cpuQuota)
                .withCpuPeriod(cpuPeriod)
                .exec()
        }
    }

    /**
     * Limit to specific number of CPU cores
     * @param cores Number of CPU cores (e.g., 0.5 for half a core, 2.0 for 2 cores)
     */
    fun limitCpuCores(containerId: String, cores: Double) {
        val cpuPeriod = 100_000L
        val cpuQuota = (cores * cpuPeriod).toLong()
        limitCpu(containerId, cpuQuota, cpuPeriod)
    }

    fun createContainer(
        port: Int,
        containerName: String,
        cpu: Int,
        memory: Long,
        memorySwap: Long,
        mounts: List<Mount>,
        image: String,
        env: List<String>,
        cmd: List<String>? = null
    ): String {

        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(parse("$port:$port"))
            .withCpuCount(cpu.toLong())
            .withPidsLimit(2048L)
            .withExtraHosts("host.docker.internal:host-gateway")
            .withMounts(mounts)
            .withCapAdd(Capability.NET_ADMIN)
            .withLogConfig(LogConfig(LogConfig.LoggingType.LOCAL, mapOf("max-size" to "10m", "max-file" to "3")))
       .withMemory(memory)
                .withMemorySwap(memorySwap)


        val createCmd = client.createContainerCmd(image)
            .withName(containerName)
            .withEnv(env)
            .withExposedPorts(ExposedPort(port))
            .withHostConfig(hostConfig)


        if (!cmd.isNullOrEmpty()) {
            createCmd.withCmd(cmd).cmd
        }

        return createCmd.exec().id
    }

    fun deleteContainer(containerName: String, removeVolumes: Boolean = false) {
        // First, try to force stop and remove the container if it exists
        findContainer(containerName)?.let { container ->
            try {
                // Force kill container if it's running
                if (container.state == "running") {
                    client.killContainerCmd(container.id).exec()
                }
                // Force remove the container
                client.removeContainerCmd(container.id)
                    .withForce(true)
                    .withRemoveVolumes(removeVolumes)
                    .exec()
            } catch (e: Exception) {
                lgr.warn { "Error force stopping/removing container ${container.id}: ${e.message}" }
            }
        }
    }


    fun start(containerName: String) {
        val container = findContainer(containerName) ?: throw RequestError("找不到此容器")
        client.startContainerCmd(container.id)
            .exec()
    }

    fun stop(containerName: String) {
        // Check if container exists first
        val container = findContainer(containerName) ?: throw RequestError("找不到此容器")
        // Only stop if the container is actually running
        if (container.state.equals("running", ignoreCase = true)) {
            client.stopContainerCmd(container.id).exec()
        } else {
            throw RequestError("早就停了")
        }

    }

    fun forceStop(containerName: String) {
        // Check if container exists first
        val container = findContainer(containerName) ?: throw RequestError("找不到此容器")
        // Only stop if the container is actually running
        if (container.state.equals("running", ignoreCase = true)) {
            client.killContainerCmd(container.id).exec()
        } else {
            throw RequestError("早就停了")
        }

    }

    fun restart(containerName: String) {
        // Check if container exists first
        val container = findContainer(containerName) ?: throw RequestError("找不到此容器")
        client.restartContainerCmd(container.id).exec()
    }

    // startLine/endLine are indices from the newest line (0 = newest), slicing [startLine, endLine)
    // 两个参数都是“从后往前”的行号：0 表示最新一行，返回区间为 [startLine, endLine)
    fun getLog(containerName: String, startLine: Int = 0, endLine: Int): String {
        return try {
            val callback = object : Adapter<Frame>() {
                val logs = mutableListOf<String>()

                override fun onNext(frame: Frame) {
                    val logLine = String(frame.payload, Charsets.UTF_8).trim()
                    if (logLine.isNotEmpty()) {
                        logs.add(logLine)
                    }
                }
            }

            // Get all logs first
            client.logContainerCmd(containerName)
                .withStdOut(true)
                .withStdErr(true)
                .exec(callback)
                .awaitCompletion()

            // Reverse logs to most-recent-first so index 0 is the newest line
            val allLogs = callback.logs.reversed()
            val total = allLogs.size

            if (total == 0) return ""

            // Normalize and clamp the requested range [startLine, endLine)
            val from = startLine.coerceAtLeast(0)
            val toExclusive = endLine.coerceAtMost(total)

            if (from >= toExclusive) return ""

            allLogs.subList(from, toExclusive).joinToString("\n")
        } catch (e: Exception) {
            lgr.warn { "Error getting logs for container $containerName: ${e.message}" }
            ""
        }
    }

    /**
     * Follow the container log stream and invoke [onLine] for each new line.
     * Returns a [Closeable] you should close to stop streaming.
     */
    fun listenLog(
        containerName: String,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit = {},
        onFinished: () -> Unit = {}
    ): Closeable {
        val callback = object : Adapter<Frame>() {
            override fun onNext(frame: Frame) {
                val logLine = String(frame.payload, Charsets.UTF_8).trim()
                if (logLine.isNotEmpty()) {
                    try {
                        onLine(logLine)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun onError(throwable: Throwable) {
                try {
                    onError(throwable)
                } catch (_: Throwable) {
                }
                super.onError(throwable)
            }

            override fun onComplete() {
                try {
                    onFinished()
                } catch (_: Throwable) {
                }
                super.onComplete()
            }
        }
        client.logContainerCmd(containerName)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTail(0)
            .exec(callback)
        return callback
    }

    fun isStarted(containerName: String): Boolean {
        return try {
            val container = findContainer(containerName)
            container?.state?.equals("running", ignoreCase = true) == true
        } catch (e: Exception) {
            lgr.warn { "Error checking container $containerName status: ${e.message}" }
            false
        }
    }

    fun getContainerStatus(containerName: String): HostStatus {
        return try {
            val container = findContainer(containerName) ?: return HostStatus.STOPPED
            parseContainerStatus(container.state)
        } catch (e: Exception) {
            lgr.warn { "Error getting status for container $containerName: ${e.message}" }
            HostStatus.UNKNOWN
        }
    }

}

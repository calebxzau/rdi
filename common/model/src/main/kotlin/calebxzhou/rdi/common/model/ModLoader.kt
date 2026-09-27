package calebxzhou.rdi.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModLoader {
    forge,
    neoforge,
    @SerialName("fabric") Fabric;

    /** Stable lowercase identifier for runtime directories and assets. */
    val directorySlug: String
        get() = name.lowercase()

    companion object{
        fun from(name:String):ModLoader?{
            val normalized = name.trim()
            if (normalized.equals("fabric", true) || normalized.equals("fabric-loader", true)) {
                return Fabric
            }
            if (FABRIC_VERSION_NAME.matches(normalized)) return Fabric
            val legacyName = normalized.substringBefore('-')
            return entries.firstOrNull {
                it != Fabric && it.name.equals(legacyName, true)
            }
        }

        private val FABRIC_VERSION_NAME = Regex("fabric(?:-loader)?-\\d+(?:\\.\\d+)+(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?", RegexOption.IGNORE_CASE)
    }
    class Version(
        val loader: ModLoader,
        //version目录的名字
        val dirName: String,
        val installerUrl: String,
        val installerSha1: String
    ){
        //1.18.2-40.3.12
        val id get() = dirName.replace("${loader.name}-","")
        //40.3.12
        val ver get() = dirName.split("-").lastOrNull()?:""
        //1.16-
        val serverJarName get() ="${loader.name}-${id}.jar"
        //1.18+
        val serverArgsPath get() = { unix: Boolean ->
             when (loader) {
                neoforge -> "@libraries/net/neoforged/neoforge/"
                forge -> "@libraries/net/minecraftforge/forge/"
                Fabric -> throw UnsupportedOperationException("Fabric server arguments are not supported yet")
            } + "${id}/${if(unix) "unix" else "win"}_args.txt"
        }
    }
}

package calebxzau.rdi.server.infra

import calebxzhou.rdi.master.DatabaseConfig
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import org.bson.UuidRepresentation

internal fun mongoClientSettings(config: DatabaseConfig): MongoClientSettings {
    val usernamePresent = config.username.isNotEmpty()
    val passwordPresent = config.password.isNotEmpty()

    val credential = if (!usernamePresent && !passwordPresent) {
        null
    } else {
        require(usernamePresent && passwordPresent) {
            "MongoDB username and password must both be provided for authentication"
        }
        require(config.username.isNotBlank()) {
            "MongoDB username must not be blank when authentication is enabled"
        }
        require(config.authSource.isNotBlank()) {
            "MongoDB authSource must not be blank when authentication is enabled"
        }
        MongoCredential.createCredential(config.username, config.authSource, config.password.toCharArray())
    }

    return MongoClientSettings.builder()
        .applyToClusterSettings { builder ->
            builder.hosts(listOf(ServerAddress(config.host, config.port)))
        }
        .apply {
            if (credential != null) {
                credential(credential)
            }
        }
        .uuidRepresentation(UuidRepresentation.STANDARD)
        .codecRegistry(productionMongoCodecRegistry())
        .build()
}

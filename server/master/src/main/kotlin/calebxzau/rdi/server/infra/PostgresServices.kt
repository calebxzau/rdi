package calebxzau.rdi.server.infra

import calebxzau.rdi.server.account.AccountMirrorService
import calebxzau.rdi.server.account.PgAccountRepo
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import calebxzau.rdi.server.service.baseworld.PgBaseWorldRepo
import calebxzau.rdi.server.service.hostworldimport.HostWorldImportService
import calebxzhou.rdi.master.CONF
import calebxzhou.rdi.master.lgr
import calebxzhou.rdi.master.infra.postgres.DatabaseProvider
import calebxzhou.rdi.master.service.PlayerService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configurePostgresServices() {
    val databaseProvider = DatabaseProvider(CONF.postgres)
    install(Koin) {
        slf4jLogger()
        modules(
            postgresModule(databaseProvider)
        )
    }

    val koin = getKoin()
    val accountMirrorService = koin.get<AccountMirrorService>()
    val baseWorldService = koin.get<BaseWorldService>()
    val hostWorldImportService = koin.get<HostWorldImportService>()
    PlayerService.configureAccountMirror(accountMirrorService)
    launch(Dispatchers.IO) {
        accountMirrorService.scan(PlayerService.accountCol.find())
            .onSuccess { summary ->
                lgr.info {
                    "account mirror startup scan complete " +
                        "inserted=${summary.inserted} existing=${summary.existing} " +
                        "conflicts=${summary.conflicts} failed=${summary.failed}"
                }
            }
            .onFailure { error ->
                lgr.error(error) { "account mirror startup scan failed" }
            }
    }
    baseWorldService.startRecovery()
    hostWorldImportService.startRecovery()

    monitor.subscribe(ApplicationStopping) {
        runBlocking {
            baseWorldService.shutdown()
            hostWorldImportService.shutdown()
        }
        databaseProvider.close()
    }
}

private fun postgresModule(databaseProvider: DatabaseProvider) = module {
    single { databaseProvider }
    single { PgAccountRepo() }
    single { PgBaseWorldRepo() }
    single { BaseWorldService(get(), get(), accounts = get()) }
    single { AccountMirrorService(get(), get()) }
    single { HostWorldImportService() }
}

package calebxzhou.rdi.mc.rcmd

object RcmdCommonServerCommands {
    @JvmStatic
    fun register(
        dispatcher: RcmdDispatcher,
        handler: RcmdServerCommandHandler,
        debug: Boolean = false,
        includeFirmSection: Boolean = true,
    ) {
        dispatcher.register(
            RcmdCommandSpec.builder("ping")
                .description("Test rcmd availability")
                .command { context -> handler.ping(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("chat", "range")
                .description("Set chat range")
                .argument("range", RcmdArgumentTypes.enumOf("host", "global"))
                .command { context -> handler.setChatRange(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("tpa")
                .description("Request teleport to another player")
                .argument("playerName", RcmdArgumentTypes.STRING)
                .command { context -> handler.requestTpa(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("tpok")
                .description("Accept pending teleport request")
                .command { context -> handler.acceptTpa(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("sethome")
                .description("Save current player position as a home")
                .argument("name", RcmdArgumentTypes.STRING)
                .command { context -> handler.setHome(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("home")
                .description("Teleport player to a saved home")
                .argument("name", RcmdArgumentTypes.STRING)
                .command { context -> handler.goHome(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("listhome")
                .description("List saved player homes")
                .command { context -> handler.listHome(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("delhome")
                .description("Delete a saved player home")
                .argument("name", RcmdArgumentTypes.STRING)
                .command { context -> handler.deleteHome(context) }
                .build()
        )
        dispatcher.register(
            RcmdCommandSpec.builder("poslock")
                .description("Toggle current player position lock")
                .command { context -> handler.togglePosLock(context) }
                .build()
        )
        if (debug) {
            dispatcher.register(
                RcmdCommandSpec.builder("testentity")
                    .description("Generate test item entities")
                    .command { context -> handler.testEntity(context) }
                    .build()
            )
        }
    }
}

package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrInstanceConfig

/**
 * How far a boot goes (issue #1289). A serving node boots to [ready]; a script boots only as far as it needs, so it
 * starts nothing that only a server should and, at [collected], reaches no database at all.
 *
 * The stages follow the registry's own passes over the services (`onCreate`, `checkInit`, `checkReady`), so a boot
 * stops between passes rather than every service asking whether it is in a script. That works because each pass has a
 * job: `checkReady` is where a service begins to serve -- starts the job scheduler, provisions the conference demo,
 * announces itself to its peers, preloads its caches -- and nothing a script does needs any of it.
 */
@Suppress("EnumEntryName")
enum class BootStage {
    /**
     * The instance config and its flavors, the components loaded, and their contributions collected into the
     * `SchemaCollector` -- declared file buckets, source-defined clients, schema, fragments. No service runs except the
     * ones the boot names, and those only through `checkInit`. Nothing reaches the database. For a script working on
     * the file store.
     */
    collected,

    /**
     * Every service through `onCreate` and `checkInit`, and none through `checkReady`: the database is usable, and
     * nothing runs in the background or provisions anything. For a script working on a deployment's data.
     */
    initialized,

    /** Everything, as a serving node boots. The default. */
    ready;

    companion object {
        /** The instance-config key the stage a boot went to is recorded under. */
        const val configKey = "bootStage"

        /** The stage [config] was booted to; [ready] for one that recorded none. */
        fun of(config: KdrInstanceConfig): BootStage = config.get(configKey) as? BootStage ?: ready
    }
}

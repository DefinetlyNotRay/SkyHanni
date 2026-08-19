package at.hannibal2.skyhanni.features.garden.greenhouse

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandCategory
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.ItemAddManager
import at.hannibal2.skyhanni.data.garden.CropCollectionApi.addCollectionCounter
import at.hannibal2.skyhanni.events.ItemAddEvent
import at.hannibal2.skyhanni.events.MobEvent
import at.hannibal2.skyhanni.events.entity.EntityDeathEvent
import at.hannibal2.skyhanni.events.garden.pests.PestKillEvent
import at.hannibal2.skyhanni.events.garden.pests.PestItemDropEvent
import at.hannibal2.skyhanni.events.item.ShardGainEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.features.garden.CropCollectionType
import at.hannibal2.skyhanni.features.garden.CropType
import at.hannibal2.skyhanni.features.garden.pests.SprayType
import at.hannibal2.skyhanni.features.garden.tracker.PestProfitTracker
import at.hannibal2.skyhanni.features.garden.tracker.RareCropTracker
import at.hannibal2.skyhanni.features.inventory.attribute.AttributeShardsData
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.DelayedRun
import at.hannibal2.skyhanni.utils.ItemCategory
import at.hannibal2.skyhanni.utils.ItemUtils.itemNameWithoutColor
import at.hannibal2.skyhanni.utils.MobUtils.mob
import at.hannibal2.skyhanni.utils.NeuInternalName
import at.hannibal2.skyhanni.utils.NeuInternalName.Companion.toInternalName
import at.hannibal2.skyhanni.utils.NeuItems
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.RegexUtils.matches
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.collection.RenderableCollectionUtils.addSearchString
import at.hannibal2.skyhanni.utils.renderables.Searchable
import at.hannibal2.skyhanni.utils.tracker.ItemTrackerData
import at.hannibal2.skyhanni.utils.tracker.SessionUptime
import at.hannibal2.skyhanni.utils.tracker.SkyHanniItemTracker
import com.google.gson.annotations.Expose
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object GreenhouseProfitTracker {
    private val patternGroup = RepoPattern.group("garden.greenhouse.profittracker")

    /**
     * REGEX-TEST: Timestalk Clone
     * REGEX-TEST: Zombuddy
     */
    private val mutationMobNamePattern by patternGroup.pattern(
        "mutationmob.name.colorless",
        "(?:Timestalk Clone|Zombuddy)",
    )

    private val PEST_ATTRIBUTION_WINDOW = 1.seconds
    private val PEST_KILL_DROP_WINDOW = 3.seconds
    private val MUTATION_DROP_ATTRIBUTION_WINDOW = 1.seconds
    private val config get() = SkyHanniMod.feature.garden.greenhouse.profitTracker
    private var wasTracking = false
    private var lastPestKill = SimpleTimeMark.farPast()
    private val pendingPestDrops = DropCredits()
    private val detectedMutationMobs = mutableSetOf<Int>()
    private val pendingMutationMobDrops = mutableMapOf<MutationMobDrop, Int>()
    private val pendingMutationShardDrops = mutableMapOf<MutationMobDrop, Int>()
    private var mutationDropFlushScheduled = false
    private val pendingItemAdds = mutableListOf<ItemAddEvent>()
    private val inventoryCropCredits = mutableMapOf<NeuInternalName, Long>()
    private var itemAddFlushScheduled = false
    private var debugRecording = false
    private var debugSequence = 0
    private val debugLog = mutableListOf<String>()
    private val rareCropDrops by lazy {
        RareCropTracker.RareCropDropType.entries.mapTo(mutableSetOf()) {
            NeuInternalName.fromItemNameOrInternalName(it.cleanName)
        }
    }

    private enum class MutationMobDrop(val mobName: String, val internalName: NeuInternalName) {
        TIMESTALK_CLONE("Timestalk Clone", "TIMESTALK".toInternalName()),
        ZOMBUDDY("Zombuddy", "ZOMBUD".toInternalName()),
        ;

        companion object {
            private val byMobName = entries.associateBy { it.mobName }
            private val internalNames = entries.mapTo(mutableSetOf()) { it.internalName }

            fun getByMobName(name: String): MutationMobDrop? = byMobName[name]

            fun isMobDrop(internalName: NeuInternalName): Boolean = internalName in internalNames
        }
    }

    private class DropCredits {
        private val credits = mutableMapOf<NeuInternalName, Int>()

        fun add(internalName: NeuInternalName, amount: Int) {
            credits[internalName] = (credits[internalName] ?: 0) + amount
        }

        fun consume(internalName: NeuInternalName, amount: Int): Int {
            val credit = credits[internalName] ?: return 0
            val consumed = minOf(amount, credit)
            val remaining = credit - consumed
            if (remaining <= 0) credits.remove(internalName) else credits[internalName] = remaining
            return consumed
        }

        fun clear() = credits.clear()
    }

    private val tracker = SkyHanniItemTracker(
        "Greenhouse Profit Tracker",
        ::Data,
        { it.garden.greenhouse.getOrCreateProfitTracker() },
        trackerConfig = { config.perTrackerConfig },
        customUptimeControl = true,
    ) { drawDisplay(it) }

    data class Data(
        @Expose var pickups: Long = 0,
    ) : ItemTrackerData<SessionUptime.Normal>(SessionUptime.Normal::class) {
        override fun getDescription(timesGained: Long): List<String> = listOf(
            "§7Picked up on a Greenhouse plot §e${timesGained.addSeparators()} §7times.",
        )

        override fun getCoinName(item: TrackedItem) = "§6Greenhouse Coins"

        override fun getCoinDescription(item: TrackedItem): List<String> = listOf(
            "§7Coins gained while on a Greenhouse plot.",
        )
    }

    init {
        tracker.initRenderer(
            { config.position },
            onlyOnIsland = IslandType.GARDEN,
        ) { isEnabled() }
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onItemAdd(event: ItemAddEvent) {
        debug("ItemAdd received: ${event.internalName} x${event.amount}, source=${event.source}, enabled=${isEnabled()}")
        if (!isEnabled()) {
            debug("ItemAdd rejected: config.enabled=${config.enabled}, inGreenhouse=${GreenhouseUtils.isInGreenhouse()}")
            return
        }

        if (event.source != ItemAddManager.Source.COMMAND) {
            pendingItemAdds.add(event)
            debug("ItemAdd queued for attribution; queueSize=${pendingItemAdds.size}")
            if (!itemAddFlushScheduled) {
                itemAddFlushScheduled = true
                debug("Scheduled item attribution flush in $PEST_ATTRIBUTION_WINDOW")
                DelayedRun.runDelayed(PEST_ATTRIBUTION_WINDOW, "Greenhouse item attribution") {
                    flushPendingItemAdds()
                }
            }
            return
        }
        processItemAdd(event)
    }

    private fun flushPendingItemAdds() {
        itemAddFlushScheduled = false
        val events = pendingItemAdds.toList()
        pendingItemAdds.clear()
        debug("Flushing ${events.size} queued item adds")

        events.filter { it.source == ItemAddManager.Source.ITEM_ADD && isHarvestDrop(it.internalName) }.forEach { event ->
            val primitive = NeuItems.getPrimitiveMultiplier(event.internalName)
            val amount = primitive.amount.toLong() * event.amount
            inventoryCropCredits[primitive.internalName] = (inventoryCropCredits[primitive.internalName] ?: 0) + amount
            debug("Inventory credit: ${primitive.internalName} +$amount base units")
        }

        for (event in events) {
            if (event.source != ItemAddManager.Source.SACKS) {
                processItemAdd(event)
                continue
            }

            val primitive = NeuItems.getPrimitiveMultiplier(event.internalName)
            val primitivePerItem = primitive.amount.toLong()
            val cropCredit = inventoryCropCredits[primitive.internalName] ?: 0
            val duplicatedAmount = minOf(event.amount.toLong(), cropCredit / primitivePerItem).toInt()
            inventoryCropCredits[primitive.internalName] = cropCredit - duplicatedAmount * primitivePerItem
            val uniqueAmount = event.amount - duplicatedAmount
            debug(
                "Sack dedupe: ${event.internalName} x${event.amount}, primitive=${primitive.internalName}" +
                    ", credit=$cropCredit, duplicate=$duplicatedAmount, unique=$uniqueAmount",
            )
            if (uniqueAmount > 0) {
                processItemAdd(ItemAddEvent(event.internalName, uniqueAmount, event.source))
            }
        }
    }

    private fun processItemAdd(event: ItemAddEvent) {
        val fromCommand = event.source == ItemAddManager.Source.COMMAND
        if (!fromCommand && !isHarvestDrop(event.internalName)) {
            debug("Item rejected: ${event.internalName} is not a recognized harvest drop")
            return
        }
        if (!fromCommand && isRecentPestDrop(event.internalName)) {
            debug("Item rejected: ${event.internalName} matched the recent pest-kill exclusion")
            return
        }
        val pestAmount = if (fromCommand) 0 else consumePestDrops(event)
        val greenhouseAmount = event.amount - pestAmount
        if (greenhouseAmount <= 0) {
            debug("Item rejected: ${event.internalName} x${event.amount} fully consumed by pest credit ($pestAmount)")
            return
        }

        val greenhouseEvent = if (greenhouseAmount == event.amount) event else {
            ItemAddEvent(event.internalName, greenhouseAmount, event.source)
        }

        with(tracker) { greenhouseEvent.addItemFromEvent() }
        debug("Item attributed: ${greenhouseEvent.internalName} x${greenhouseEvent.amount}, source=${greenhouseEvent.source}")
        if (event.source == ItemAddManager.Source.COMMAND) return

        tracker.modify { it.pickups++ }
        addToGreenhouseCollection(greenhouseEvent)
    }

    private fun isHarvestDrop(internalName: NeuInternalName): Boolean {
        if (SprayType.getByInternalName(internalName) != null) return false
        if (internalName in rareCropDrops) return true
        if (!MutationMobDrop.isMobDrop(internalName) &&
            internalName.getItemCategoryOrNull() == ItemCategory.MUTATION
        ) return true
        val primitiveName = NeuItems.getPrimitiveMultiplier(internalName).internalName.itemNameWithoutColor
        return CropType.getByNameOrNull(primitiveName) != null
    }

    private fun isRecentPestDrop(internalName: NeuInternalName): Boolean =
        lastPestKill.passedSince() < PEST_KILL_DROP_WINDOW && PestProfitTracker.isPestDropItem(internalName)

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onMobFirstSeen(event: MobEvent.FirstSeen) {
        if (!debugRecording) return
        val name = event.mob.name
        if (name.contains("timestalk", ignoreCase = true) || name.contains("zombud", ignoreCase = true)) {
            debug(
                "Mutation-like mob first seen: id=${event.mob.id}, name='$name', " +
                    "patternMatch=${mutationMobNamePattern.matches(name)}",
            )
        }
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onEntityDeath(event: EntityDeathEvent<*>) {
        if (!isEnabled()) {
            debug("EntityDeath ignored: tracker disabled or outside Greenhouse")
            return
        }
        val mob = event.entity.mob
        if (mob == null) {
            debug("EntityDeath ignored: entity did not resolve to a SkyBlock mob")
            return
        }
        debug("EntityDeath: id=${mob.id}, name='${mob.name}'")
        detectMutationMobDrop(mob.id, mob.name)
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onMobDespawn(event: MobEvent.DeSpawn) {
        if (!isEnabled()) {
            debug("MobDespawn ignored: tracker disabled or outside Greenhouse")
            return
        }
        debug("MobDespawn: id=${event.mob.id}, name='${event.mob.name}'")
        detectMutationMobDrop(event.mob.id, event.mob.name)
    }

    private fun detectMutationMobDrop(mobId: Int, mobName: String) {
        if (!mutationMobNamePattern.matches(mobName)) {
            debug("Mutation mob rejected: name '$mobName' did not match mutationMobNamePattern")
            return
        }
        if (!detectedMutationMobs.add(mobId)) {
            debug("Mutation mob rejected: entity id $mobId was already detected")
            return
        }
        val drop = MutationMobDrop.getByMobName(mobName)
        if (drop == null) {
            debug("Mutation mob rejected: matched name '$mobName' has no MutationMobDrop mapping")
            return
        }
        pendingMutationMobDrops[drop] = (pendingMutationMobDrops[drop] ?: 0) + 1
        debug("Mutation mob accepted: $mobName -> ${drop.internalName}; pendingMob=${pendingMutationMobDrops[drop]}")
        scheduleMutationDropFlush()
    }

    private fun scheduleMutationDropFlush() {
        if (mutationDropFlushScheduled) return
        mutationDropFlushScheduled = true
        debug("Scheduled mutation attribution flush in $MUTATION_DROP_ATTRIBUTION_WINDOW")
        DelayedRun.runDelayed(MUTATION_DROP_ATTRIBUTION_WINDOW, "Greenhouse mutation drop attribution") {
            mutationDropFlushScheduled = false
            MutationMobDrop.entries.forEach { drop ->
                val mobAmount = pendingMutationMobDrops[drop] ?: 0
                val shardAmount = pendingMutationShardDrops[drop] ?: 0
                val amount = maxOf(mobAmount, shardAmount)
                debug("Mutation flush: $drop, mobSignals=$mobAmount, shardSignals=$shardAmount, attributed=$amount")
                if (amount > 0) {
                    tracker.addItem(drop.internalName, amount, command = false)
                    tracker.modify { it.pickups += amount }
                    debug("Mutation attributed: ${drop.internalName} x$amount")
                }
            }
            pendingMutationMobDrops.clear()
            pendingMutationShardDrops.clear()
        }
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onShardGain(event: ShardGainEvent) {
        debug("ShardGain received: ${event.shardInternalName} x${event.amount}, source=${event.source}, enabled=${isEnabled()}")
        if (!isEnabled()) {
            debug("ShardGain rejected: config.enabled=${config.enabled}, inGreenhouse=${GreenhouseUtils.isInGreenhouse()}")
            return
        }
        val shardName = AttributeShardsData.shardInternalNameToShardName(event.shardInternalName)
        val drop = MutationMobDrop.getByMobName(shardName)
        if (drop == null) {
            debug("ShardGain rejected: resolved name '$shardName' has no MutationMobDrop mapping")
            return
        }
        pendingMutationShardDrops[drop] = (pendingMutationShardDrops[drop] ?: 0) + 1
        debug("ShardGain accepted: '$shardName' -> ${drop.internalName}; pendingShard=${pendingMutationShardDrops[drop]}")
        scheduleMutationDropFlush()
    }

    @HandleEvent(PestKillEvent::class)
    private fun onPestKill() {
        if (!isEnabled()) return
        lastPestKill = SimpleTimeMark.now()
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onPestItemDrop(event: PestItemDropEvent) {
        if (!isEnabled()) return
        pendingPestDrops.add(event.internalName, event.amount)
        debug("Pest credit added: ${event.internalName} x${event.amount}")
    }

    private fun consumePestDrops(event: ItemAddEvent): Int {
        val consumed = pendingPestDrops.consume(event.internalName, event.amount)
        if (consumed > 0) debug("Pest credit consumed: ${event.internalName} x$consumed")
        return consumed
    }

    private fun addToGreenhouseCollection(event: ItemAddEvent) {
        val primitiveStack = NeuItems.getPrimitiveMultiplier(event.internalName)
        val crop = CropType.getByNameOrNull(primitiveStack.internalName.itemNameWithoutColor) ?: return
        crop.addCollectionCounter(CropCollectionType.GREENHOUSE, primitiveStack.amount.toLong() * event.amount)
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onTick(event: SkyHanniTickEvent) {
        if (!event.isMod(10)) return
        val tracking = isEnabled()
        if (tracking == wasTracking) return
        wasTracking = tracking
        if (tracking) {
            tracker.startSessionUptime()
        } else {
            clearAttributionState()
            tracker.pauseSessionUptime()
        }
    }

    @HandleEvent
    private fun onWorldChange() {
        wasTracking = false
        clearAttributionState()
        tracker.pauseSessionUptime()
    }

    private fun clearAttributionState() {
        debug("Clearing attribution state")
        lastPestKill = SimpleTimeMark.farPast()
        pendingPestDrops.clear()
        detectedMutationMobs.clear()
        pendingMutationMobDrops.clear()
        pendingMutationShardDrops.clear()
        mutationDropFlushScheduled = false
        pendingItemAdds.clear()
        inventoryCropCredits.clear()
        itemAddFlushScheduled = false
    }

    private fun drawDisplay(data: Data): List<Searchable> = buildList {
        addSearchString("§6§lGreenhouse Profit Tracker")
        val profit = tracker.drawItems(data, { true }, this)
        addAll(tracker.addTotalProfit(profit, data.pickups, "pickup", data.getTotalUptime(), "Pickups"))
        tracker.addPriceFromButton(this)
    }

    private fun isEnabled() = config.enabled && GreenhouseUtils.isInGreenhouse()

    private fun debug(message: String) {
        if (!debugRecording) return
        debugSequence++
        debugLog.add("#$debugSequence $message")
        if (debugLog.size > MAX_DEBUG_LINES) debugLog.removeAt(0)
    }

    @HandleEvent
    private fun onCommandRegistration(event: CommandRegistrationEvent) {
        event.registerBrigadier("shresetgreenhousetracker") {
            aliases = listOf("shresetght")
            description = "Resets the Greenhouse Profit Tracker"
            category = CommandCategory.USERS_RESET
            simpleCallback { tracker.resetCommand() }
        }
        event.registerBrigadier("shdebugghprofit") {
            description = "Records Greenhouse Profit Tracker attribution decisions"
            category = CommandCategory.DEVELOPER_DEBUG
            simpleCallback {
                if (!debugRecording) {
                    debugLog.clear()
                    debugSequence = 0
                    debugRecording = true
                    debug(
                        "Recording started: config.enabled=${config.enabled}, " +
                            "inGreenhouse=${GreenhouseUtils.isInGreenhouse()}, isEnabled=${isEnabled()}",
                    )
                    ChatUtils.chat("Greenhouse Profit Tracker debug recording started. Run §e/shdebugghprofit §ragain to stop and copy it.")
                } else {
                    debug("Recording stopped")
                    debugRecording = false
                    ChatUtils.clickToClipboard("Greenhouse Profit Tracker debug", debugLog)
                }
            }
        }
    }

    private const val MAX_DEBUG_LINES = 500
}

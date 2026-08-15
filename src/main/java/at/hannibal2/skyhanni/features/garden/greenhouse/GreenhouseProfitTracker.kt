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
import at.hannibal2.skyhanni.events.entity.EntityClickEvent
import at.hannibal2.skyhanni.events.entity.EntityDeathEvent
import at.hannibal2.skyhanni.events.garden.pests.PestKillEvent
import at.hannibal2.skyhanni.events.garden.pests.PestItemDropEvent
import at.hannibal2.skyhanni.events.item.ShardGainEvent
import at.hannibal2.skyhanni.events.item.ShardSource
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.features.garden.CropCollectionType
import at.hannibal2.skyhanni.features.garden.CropType
import at.hannibal2.skyhanni.features.garden.tracker.PestProfitTracker
import at.hannibal2.skyhanni.features.garden.tracker.RareCropTracker
import at.hannibal2.skyhanni.features.inventory.attribute.AttributeShardsData
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.DelayedRun
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
import kotlin.time.Duration
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
    private val PEST_DROP_EXPIRY = 10.seconds
    private val PEST_KILL_DROP_WINDOW = 3.seconds
    private val MUTATION_MOB_DROP_EXPIRY = 5.seconds
    private val config get() = SkyHanniMod.feature.garden.greenhouse.profitTracker
    private var wasTracking = false
    private var lastPestKill = SimpleTimeMark.farPast()
    private val pendingPestDrops = TimedDropCredits(PEST_DROP_EXPIRY)
    private val attackedMutationMobs = mutableSetOf<Int>()
    private val pendingMutationMobDrops = TimedDropCredits(MUTATION_MOB_DROP_EXPIRY)
    private val pendingItemAdds = mutableListOf<ItemAddEvent>()
    private val inventoryCropCredits = mutableMapOf<NeuInternalName, Long>()
    private var itemAddFlushScheduled = false
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
            fun getByShardInternalName(internalName: NeuInternalName): MutationMobDrop? =
                entries.firstOrNull { AttributeShardsData.shardNameToInternalName(it.mobName) == internalName }

            fun isDrop(internalName: NeuInternalName): Boolean = internalName in internalNames
        }
    }

    private class TimedDropCredits(private val expiry: Duration) {
        private data class Credit(var amount: Int, val detectedAt: SimpleTimeMark)

        private val credits = mutableMapOf<NeuInternalName, Credit>()

        fun add(internalName: NeuInternalName, amount: Int) {
            val current = credits[internalName]
            if (current == null || current.detectedAt.passedSince() >= expiry) {
                credits[internalName] = Credit(amount, SimpleTimeMark.now())
            } else {
                current.amount += amount
            }
        }

        fun consume(internalName: NeuInternalName, amount: Int): Int {
            removeExpired()
            val credit = credits[internalName] ?: return 0
            val consumed = minOf(amount, credit.amount)
            credit.amount -= consumed
            if (credit.amount <= 0) credits.remove(internalName)
            return consumed
        }

        fun clear() = credits.clear()

        private fun removeExpired() = credits.entries.removeIf { it.value.detectedAt.passedSince() >= expiry }
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
        if (!isEnabled()) return

        if (event.source != ItemAddManager.Source.COMMAND) {
            pendingItemAdds.add(event)
            if (!itemAddFlushScheduled) {
                itemAddFlushScheduled = true
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

        events.filter { it.source == ItemAddManager.Source.ITEM_ADD && isHarvestDrop(it.internalName) }.forEach { event ->
            val primitive = NeuItems.getPrimitiveMultiplier(event.internalName)
            val amount = primitive.amount.toLong() * event.amount
            inventoryCropCredits[primitive.internalName] = (inventoryCropCredits[primitive.internalName] ?: 0) + amount
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
            if (uniqueAmount > 0) {
                processItemAdd(ItemAddEvent(event.internalName, uniqueAmount, event.source))
            }
        }
    }

    private fun processItemAdd(event: ItemAddEvent) {
        val fromCommand = event.source == ItemAddManager.Source.COMMAND
        val mutationMobDropAmount = if (fromCommand) null else {
            consumeMutationMobDrops(event)
        }
        if (mutationMobDropAmount == 0) return
        if (mutationMobDropAmount == null && !fromCommand && !isHarvestDrop(event.internalName)) return
        if (mutationMobDropAmount == null && !fromCommand && isRecentPestDrop(event.internalName)) return
        val pestAmount = if (fromCommand) 0 else consumePestDrops(event)
        val greenhouseAmount = (mutationMobDropAmount ?: event.amount) - pestAmount
        if (greenhouseAmount <= 0) return

        val greenhouseEvent = if (greenhouseAmount == event.amount) event else {
            ItemAddEvent(event.internalName, greenhouseAmount, event.source)
        }

        with(tracker) { greenhouseEvent.addItemFromEvent() }
        if (event.source == ItemAddManager.Source.COMMAND) return

        tracker.modify { it.pickups++ }
        addToGreenhouseCollection(greenhouseEvent)
    }

    private fun isHarvestDrop(internalName: NeuInternalName): Boolean {
        if (internalName in rareCropDrops) return true
        val primitiveName = NeuItems.getPrimitiveMultiplier(internalName).internalName.itemNameWithoutColor
        return CropType.getByNameOrNull(primitiveName) != null
    }

    private fun isRecentPestDrop(internalName: NeuInternalName): Boolean =
        lastPestKill.passedSince() < PEST_KILL_DROP_WINDOW && PestProfitTracker.isPestDropItem(internalName)

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onEntityClick(event: EntityClickEvent) {
        if (!isEnabled() || event.action != EntityClickEvent.ActionType.ATTACK) return
        val mob = event.clickedEntity.mob ?: return
        if (mutationMobNamePattern.matches(mob.name)) attackedMutationMobs.add(mob.id)
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onEntityDeath(event: EntityDeathEvent<*>) {
        if (!isEnabled()) return
        val mob = event.entity.mob ?: return
        if (!attackedMutationMobs.remove(mob.id)) return
        val drop = MutationMobDrop.getByMobName(mob.name) ?: return
        pendingMutationMobDrops.add(drop.internalName, 1)
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onMobDespawn(event: MobEvent.DeSpawn.SkyblockMob) {
        attackedMutationMobs.remove(event.mob.id)
    }

    private fun consumeMutationMobDrops(event: ItemAddEvent): Int? {
        if (!MutationMobDrop.isDrop(event.internalName)) return null
        return pendingMutationMobDrops.consume(event.internalName, event.amount)
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onShardGain(event: ShardGainEvent) {
        if (!isEnabled() || event.source != ShardSource.HUNT) return
        val drop = MutationMobDrop.getByShardInternalName(event.shardInternalName) ?: return

        pendingMutationMobDrops.add(drop.internalName, 1)
        tracker.addItem(event.shardInternalName, event.amount, command = false)
        tracker.modify { it.pickups++ }
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
    }

    private fun consumePestDrops(event: ItemAddEvent) = pendingPestDrops.consume(event.internalName, event.amount)

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
        lastPestKill = SimpleTimeMark.farPast()
        pendingPestDrops.clear()
        attackedMutationMobs.clear()
        pendingMutationMobDrops.clear()
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

    @HandleEvent
    private fun onCommandRegistration(event: CommandRegistrationEvent) {
        event.registerBrigadier("shresetgreenhousetracker") {
            aliases = listOf("shresetght")
            description = "Resets the Greenhouse Profit Tracker"
            category = CommandCategory.USERS_RESET
            simpleCallback { tracker.resetCommand() }
        }
    }
}

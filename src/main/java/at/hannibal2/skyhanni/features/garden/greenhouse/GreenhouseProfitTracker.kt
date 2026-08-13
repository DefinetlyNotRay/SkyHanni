package at.hannibal2.skyhanni.features.garden.greenhouse

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandCategory
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.ItemAddManager
import at.hannibal2.skyhanni.data.garden.CropCollectionApi.addCollectionCounter
import at.hannibal2.skyhanni.events.ItemAddEvent
import at.hannibal2.skyhanni.events.garden.farming.CropClickEvent
import at.hannibal2.skyhanni.events.garden.pests.PestItemDropEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.features.garden.CropCollectionType
import at.hannibal2.skyhanni.features.garden.CropType
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.DelayedRun
import at.hannibal2.skyhanni.utils.ItemUtils.itemNameWithoutColor
import at.hannibal2.skyhanni.utils.NeuInternalName
import at.hannibal2.skyhanni.utils.NeuItems
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
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
    private val ITEM_ADD_HARVEST_WINDOW = 1.seconds
    private val SACK_BATCH_WINDOW = 6.seconds
    private val PEST_DROP_EXPIRY = 10.seconds
    private val config get() = SkyHanniMod.feature.garden.greenhouse.profitTracker
    private var wasTracking = false
    private var lastCropClick = SimpleTimeMark.farPast()
    private val pendingPestDrops = mutableMapOf<NeuInternalName, PendingPestDrop>()

    private data class PendingPestDrop(var amount: Int, val detectedAt: SimpleTimeMark)

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
            DelayedRun.runNextTickEnd { processItemAdd(event) }
            return
        }
        processItemAdd(event)
    }

    private fun processItemAdd(event: ItemAddEvent) {
        if (event.source != ItemAddManager.Source.COMMAND && !isRecentHarvest(event.source)) return
        val pestAmount = if (event.source == ItemAddManager.Source.COMMAND) 0 else consumePestDrops(event)
        val greenhouseAmount = event.amount - pestAmount
        if (greenhouseAmount <= 0) return

        val greenhouseEvent = if (greenhouseAmount == event.amount) event else {
            ItemAddEvent(event.internalName, greenhouseAmount, event.source)
        }

        with(tracker) { greenhouseEvent.addItemFromEvent() }
        if (event.source == ItemAddManager.Source.COMMAND) return

        tracker.modify { it.pickups++ }
        addToGreenhouseCollection(greenhouseEvent)
    }

    private fun isRecentHarvest(source: ItemAddManager.Source): Boolean {
        val window = if (source == ItemAddManager.Source.SACKS) SACK_BATCH_WINDOW else ITEM_ADD_HARVEST_WINDOW
        return lastCropClick.passedSince() < window
    }

    @HandleEvent(CropClickEvent::class, onlyOnIsland = IslandType.GARDEN)
    private fun onCropClick() {
        if (!isEnabled()) return
        lastCropClick = SimpleTimeMark.now()
    }

    @HandleEvent(onlyOnIsland = IslandType.GARDEN)
    private fun onPestItemDrop(event: PestItemDropEvent) {
        if (!isEnabled()) return
        val pending = pendingPestDrops[event.internalName]
        if (pending == null || pending.detectedAt.passedSince() >= PEST_DROP_EXPIRY) {
            pendingPestDrops[event.internalName] = PendingPestDrop(event.amount, SimpleTimeMark.now())
        } else {
            pending.amount += event.amount
        }
    }

    private fun consumePestDrops(event: ItemAddEvent): Int {
        pendingPestDrops.entries.removeIf { it.value.detectedAt.passedSince() >= PEST_DROP_EXPIRY }
        val pending = pendingPestDrops[event.internalName] ?: return 0
        val consumed = minOf(event.amount, pending.amount)
        pending.amount -= consumed
        if (pending.amount <= 0) pendingPestDrops.remove(event.internalName)
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
        if (tracking) tracker.startSessionUptime() else tracker.pauseSessionUptime()
    }

    @HandleEvent
    private fun onWorldChange() {
        wasTracking = false
        lastCropClick = SimpleTimeMark.farPast()
        pendingPestDrops.clear()
        tracker.pauseSessionUptime()
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

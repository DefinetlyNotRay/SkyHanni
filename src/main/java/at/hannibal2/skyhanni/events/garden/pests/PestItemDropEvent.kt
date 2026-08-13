package at.hannibal2.skyhanni.events.garden.pests

import at.hannibal2.skyhanni.api.event.SkyHanniEvent
import at.hannibal2.skyhanni.utils.NeuInternalName

class PestItemDropEvent(val internalName: NeuInternalName, val amount: Int) : SkyHanniEvent()

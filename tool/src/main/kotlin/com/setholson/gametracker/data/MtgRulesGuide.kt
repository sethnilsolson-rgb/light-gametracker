package com.setholson.gametracker.data

import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgRulesTopic
import java.util.Locale

/**
 * 100% offline MTG Common Rulings & Misconceptions Guide + 2-Card Interaction Detector.
 */
object MtgRulesGuide {

    val TOPICS: List<MtgRulesTopic> = listOf(
        MtgRulesTopic(
            id = "deathtouch_trample",
            title = "Deathtouch + Trample",
            category = "Combat",
            summary = "Only 1 damage is lethal to each blocker—the rest tramples over to the player or planeswalker.",
            keyPoints = listOf(
                "A creature with both Deathtouch and Trample only needs to assign 1 damage to each blocking creature to count as lethal.",
                "Even if the blocker has Indestructible or Protection, 1 damage still satisfies lethal assignment—excess damage still tramples through.",
                "Note: If a blocker has Damage Prevention, lethal assignment is still calculated BEFORE prevention, so excess damage still tramples over.",
            ),
            triggers = setOf("deathtouch", "trample"),
        ),
        MtgRulesTopic(
            id = "indestructible",
            title = "Indestructible vs. -X/-X, Exile & Sacrifice",
            category = "Removal",
            summary = "Indestructible only stops lethal damage and effects that literally say 'destroy'.",
            keyPoints = listOf(
                "0 toughness (from -X/-X or -1/-1 counters) STILL kills an Indestructible creature as a state-based action.",
                "Exile, Sacrifice, Bounce to hand, and Tuck into library all work normally on Indestructible permanents.",
                "Damage does NOT lower toughness—a 4/4 Indestructible creature taking 3 damage and getting -1/-1 becomes a 3/3 with 3 damage (still survives!).",
            ),
            triggers = setOf("indestructible", "destroy", "exile", "sacrifice", "-1/-1", "gets -"),
        ),
        MtgRulesTopic(
            id = "protection_debt",
            title = "Protection (D.E.B.T.)",
            category = "Keywords",
            summary = "Protection from [X] prevents Damage, Enchant/Equip, Blocking, and Targeting—nothing else.",
            keyPoints = listOf(
                "D = Damage from [X] is prevented.",
                "E = Cannot be Enchanted or Equipped by [X] (existing ones fall off).",
                "B = Cannot be Blocked by creatures with quality [X].",
                "T = Cannot be Targeted by spells/abilities of quality [X].",
                "Board wipes that don't target or deal damage (like Wrath of God, Farewell, Toxic Deluge) STILL affect creatures with Protection!",
            ),
            triggers = setOf("protection from", "hexproof", "shroud", "target"),
        ),
        MtgRulesTopic(
            id = "ward_hexproof_shroud",
            title = "Ward vs. Hexproof vs. Shroud",
            category = "Keywords",
            summary = "Ward is a triggered ability that counters unless paid; non-targeting effects bypass all three.",
            keyPoints = listOf(
                "Hexproof: Opponents can't target it, but YOU still can.",
                "Shroud: Nobody (including you) can target it.",
                "Ward: Opponents CAN target it, which puts the Ward trigger on the stack. Uncounterable spells (e.g. Abrupt Decay, Dovin's Veto) bypass Ward without paying!",
                "If a card says 'choose' (not 'target'), like Clone or Council's Judgment, it ignores Hexproof, Shroud, and Ward.",
            ),
            triggers = setOf("ward", "hexproof", "shroud", "can't be countered", "choose"),
        ),
        MtgRulesTopic(
            id = "stack_priority_apnap",
            title = "The Stack, Priority & APNAP Order",
            category = "Core Rules",
            summary = "The stack resolves Last-In, First-Out. Simultaneous triggers go on in Active Player → Non-Active Player order.",
            keyPoints = listOf(
                "Active Player puts all of their simultaneous triggers on the stack first, then each other player in turn order.",
                "Because the stack resolves top-down, the LAST player's triggers resolve FIRST.",
                "Removing a creature AFTER its ability is already on the stack does NOT stop the ability from resolving (throwing a grenade still explodes if the thrower leaves).",
                "Mana abilities (like tapping a Forest or Sol Ring) do NOT use the stack and cannot be responded to.",
            ),
            triggers = setOf("when ", "whenever ", "at the beginning", "counter target", "split second"),
        ),
        MtgRulesTopic(
            id = "replacement_vs_trigger",
            title = "Replacement Effects vs. Triggers",
            category = "Core Rules",
            summary = "'If ... would ... instead' modifies an event directly without using the stack.",
            keyPoints = listOf(
                "Triggers start with 'When', 'Whenever', or 'At' and go on the stack.",
                "Replacement effects use 'instead', 'as [this] enters', or 'skip' and happen immediately—nobody can respond to them.",
                "If multiple replacement effects apply to the same event (e.g., Doubling Season + Hardened Scales, or Rest in Peace + Yawgmoth's Will), the AFFECTED player (or controller of the affected object) chooses the order!",
                "Each replacement effect can apply to a given event only once.",
            ),
            triggers = setOf("instead", "would ", "as ", "enters with", "skip "),
        ),
        MtgRulesTopic(
            id = "layers_timestamps",
            title = "Layers & Timestamps (Blood Moon / Humility)",
            category = "Advanced",
            summary = "Continuous effects always apply in strict Layer order 1→7, then by dependency, then timestamp.",
            keyPoints = listOf(
                "Layer 1: Copy effects • Layer 2: Control • Layer 3: Text • Layer 4: Type (Blood Moon, Urborg) • Layer 5: Color • Layer 6: Abilities (Humility, Dress Down) • Layer 7: Power/Toughness.",
                "Blood Moon + Urborg, Tomb of Yawgmoth: Both apply in Layer 4, but Urborg depends on Blood Moon removing its ability—so nonbasics are ONLY Mountains (no Swamp!).",
                "Humility + Opalescence: Both set P/T in Layer 7b—whichever entered the battlefield LAST wins for P/T (timestamp order), though all creatures lose abilities in Layer 6 either way.",
                "Sublayers of Layer 7: 7a characteristic-defining (*/*) → 7b set P/T (becomes 1/1) → 7c +X/+X & counters → 7d switch P/T.",
            ),
            triggers = setOf("blood moon", "urborg", "humility", "loses all abilities", "becomes a ", "base power", "nonbasic lands are"),
        ),
        MtgRulesTopic(
            id = "first_strike_lifelink",
            title = "First Strike, Double Strike & Lifelink",
            category = "Combat",
            summary = "Lifelink gains life simultaneously with damage (no stack); killing a blocker still leaves an attacker blocked.",
            keyPoints = listOf(
                "Lifelink happens at the exact moment damage is dealt—if you take lethal damage and gain life from a Lifelink blocker simultaneously, you stay alive if your resulting life is above 0!",
                "If a blocker dies or is sacrificed/bounced before combat damage, the attacking creature is STILL blocked and deals 0 damage to the defending player—UNLESS it has Trample.",
                "Giving a creature First Strike AFTER First Strike damage has already been dealt does not let it deal damage twice.",
            ),
            triggers = setOf("first strike", "double strike", "lifelink", "blocked", "combat damage"),
        ),
        MtgRulesTopic(
            id = "monarch_initiative",
            title = "The Monarch & The Initiative",
            category = "Multiplayer",
            summary = "Only one player can be the Monarch at a time; combat damage steals it immediately.",
            keyPoints = listOf(
                "At the beginning of the Monarch's end step, the Monarch draws a card.",
                "Whenever a creature deals combat damage to the Monarch, that creature's controller becomes the Monarch (a triggered ability using the stack).",
                "Non-combat damage (burn spells, life loss) does NOT steal the Monarch.",
                "If the Monarch leaves a multiplayer game, the Active Player becomes the Monarch (or if the Active Player left, the next player in turn order).",
            ),
            triggers = setOf("monarch", "initiative", "undercity"),
        ),
        MtgRulesTopic(
            id = "commander_rules",
            title = "Commander Damage & Command Zone",
            category = "Commander",
            summary = "21 combat damage from a single commander is lethal; Commander Tax is +2 per previous cast from command zone.",
            keyPoints = listOf(
                "Only COMBAT damage counts toward 21 Commander damage—and it's tracked separately per individual commander (even Partner commanders are tracked separately).",
                "Gaining life does NOT erase Commander damage.",
                "If your commander dies or is exiled, it goes to graveyard/exile first (so 'dies' triggers DO happen!), then as a state-based action you may move it to the command zone.",
                "Commander tax (+{2}) only counts times you CAST it from the command zone—not putting it into play via Yuriko, Derevi, or from your hand/graveyard.",
            ),
            triggers = setOf("commander", "command zone", "partner", "eminence"),
        ),
        MtgRulesTopic(
            id = "phasing_vs_blink",
            title = "Phasing Out vs. Blink / Flicker",
            category = "Keywords",
            summary = "Phasing keeps counters & auras without triggering ETB/LTB; Blinking creates a brand-new object.",
            keyPoints = listOf(
                "Phased-out permanents are treated as though they don't exist until your next untap step, when they phase back in—NO leaves/enters triggers happen, and counters/auras stay attached!",
                "Tokens that phase out DO phase back in safely (they are not destroyed).",
                "Blinking/Flickering (exile and return) wipes all counters, detaches auras/equipment, stops targeted spells on the stack, and DOES trigger leaves & enters abilities.",
            ),
            triggers = setOf("phase", "phases out", "exile", "return it to the battlefield"),
        ),
        MtgRulesTopic(
            id = "clones_copy",
            title = "Clones & Copying Permanents/Spells",
            category = "Core Rules",
            summary = "Copies see only printed card values (plus other copy effects), never counters, auras, or pumps.",
            keyPoints = listOf(
                "A Clone entering as a copy of a creature does NOT copy +1/+1 counters, Giant Growth pumps, damage marked, or tapped status.",
                "Copying a spell on the stack copies choices made when cast (like X value, modes, kicked status), and does NOT trigger 'whenever you cast' abilities.",
                "If a Clone copies a Legendary creature you already control, the Legend Rule forces you to keep one and put the rest into the graveyard immediately (not sacrificed!).",
            ),
            triggers = setOf("copy", "clone", "legendary", "becomes a copy"),
        ),
        MtgRulesTopic(
            id = "costs_vs_effects",
            title = "Paying Costs vs. Resolving Effects",
            category = "Core Rules",
            summary = "Anything before the colon (:) or 'as an additional cost' happens immediately and cannot be responded to.",
            keyPoints = listOf(
                "If an opponent casts Village Rites (sacrificing a creature as an additional cost), you CANNOT kill that creature in response—it's already in the graveyard before you get priority.",
                "Similarly, tapping or sacrificing a permanent to pay an activated ability cost (before the ':') happens before anyone can respond.",
                "If a spell is countered, its costs are NOT refunded.",
            ),
            triggers = setOf("additional cost", "sacrifice a ", "discard a ", "pay "),
        ),
        MtgRulesTopic(
            id = "state_based_actions",
            title = "State-Based Actions (0 Life, 10 Poison)",
            category = "Core Rules",
            summary = "Checked automatically before any player receives priority; they do not use the stack.",
            keyPoints = listOf(
                "A player with 0 or less life, 10+ poison counters, or 21+ combat damage from a single commander loses immediately when SBAs are checked.",
                "A creature with 0 or less toughness, or lethal damage (unless Indestructible), is put into the graveyard.",
                "+1/+1 and -1/-1 counters on the same permanent cancel each other out 1-for-1.",
                "Tokens in any zone other than the battlefield cease to exist immediately (though they DO hit the graveyard first for 'dies' triggers!).",
            ),
            triggers = setOf("poison", "toxic", "infect", "proliferate", "dies", "token"),
        ),
    )

    fun searchTopics(query: String): List<MtgRulesTopic> {
        val q = query.trim().lowercase(Locale.US)
        if (q.isBlank()) return TOPICS
        return TOPICS.filter { topic ->
            topic.title.lowercase(Locale.US).contains(q) ||
                topic.category.lowercase(Locale.US).contains(q) ||
                topic.summary.lowercase(Locale.US).contains(q) ||
                topic.keyPoints.any { it.lowercase(Locale.US).contains(q) } ||
                topic.triggers.any { it.contains(q) }
        }
    }

    /**
     * Automatically surfaces relevant rules clarifications when comparing two MTG cards
     * (or inspecting a single complex card).
     */
    fun detectInteractions(cardA: MtgCardDetail?, cardB: MtgCardDetail?): List<MtgRulesTopic> {
        val combined = buildString {
            cardA?.let {
                append(it.name).append(' ').append(it.typeLine).append(' ').append(it.oracleText).append(' ')
            }
            cardB?.let {
                append(it.name).append(' ').append(it.typeLine).append(' ').append(it.oracleText)
            }
        }.lowercase(Locale.US)

        if (combined.isBlank()) return emptyList()

        return TOPICS.mapNotNull { topic ->
            val hits = topic.triggers.count { trigger -> combined.contains(trigger) }
            if (hits > 0) topic to hits else null
        }.sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }
}

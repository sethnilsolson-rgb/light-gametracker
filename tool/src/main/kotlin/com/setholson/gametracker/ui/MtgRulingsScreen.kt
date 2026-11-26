package com.setholson.gametracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.setholson.gametracker.data.GameRepository
import com.setholson.gametracker.data.MtgRulesGuide
import com.setholson.gametracker.db.GameDatabase
import com.setholson.gametracker.model.MtgCardDetail
import com.setholson.gametracker.model.MtgRulesTopic
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RulingsTab(val label: String) {
    LOOKUP("Lookup"),
    COMPARE("2 Cards"),
    GUIDE("Guide"),
}

enum class EditorPurpose {
    LOOKUP_CARD,
    PICK_CARD_A,
    PICK_CARD_B,
    FILTER_GUIDE,
}

class MtgRulingsViewModel(
    private val repository: GameRepository,
) : LightViewModel<Unit>() {

    private val _tab = MutableStateFlow(RulingsTab.LOOKUP)
    val tab: StateFlow<RulingsTab> = _tab.asStateFlow()

    private val _editorPurpose = MutableStateFlow<EditorPurpose?>(null)
    val editorPurpose: StateFlow<EditorPurpose?> = _editorPurpose.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _pendingAssignSlot = MutableStateFlow<String?>(null)
    val pendingAssignSlot: StateFlow<String?> = _pendingAssignSlot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedCard = MutableStateFlow<MtgCardDetail?>(null)
    val selectedCard: StateFlow<MtgCardDetail?> = _selectedCard.asStateFlow()

    private val _recentCards = MutableStateFlow<List<MtgCardDetail>>(emptyList())
    val recentCards: StateFlow<List<MtgCardDetail>> = _recentCards.asStateFlow()

    private val _cardA = MutableStateFlow<MtgCardDetail?>(null)
    val cardA: StateFlow<MtgCardDetail?> = _cardA.asStateFlow()

    private val _cardB = MutableStateFlow<MtgCardDetail?>(null)
    val cardB: StateFlow<MtgCardDetail?> = _cardB.asStateFlow()

    private val _guideQuery = MutableStateFlow("")
    val guideQuery: StateFlow<String> = _guideQuery.asStateFlow()

    private val _expandedTopicId = MutableStateFlow<String?>(null)
    val expandedTopicId: StateFlow<String?> = _expandedTopicId.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshRecentCards()
    }

    private fun refreshRecentCards() {
        viewModelScope.launch {
            _recentCards.value = repository.getRecentCachedCards()
        }
    }

    fun selectTab(newTab: RulingsTab) {
        _tab.value = newTab
        _errorMessage.value = null
        _suggestions.value = emptyList()
        _pendingAssignSlot.value = null
    }

    fun openEditor(purpose: EditorPurpose) {
        _editorPurpose.value = purpose
    }

    fun closeEditor() {
        _editorPurpose.value = null
    }

    fun submitEditorText(raw: String) {
        val purpose = _editorPurpose.value ?: return
        _editorPurpose.value = null
        val trimmed = raw.trim()

        when (purpose) {
            EditorPurpose.FILTER_GUIDE -> {
                _guideQuery.value = trimmed
            }
            EditorPurpose.LOOKUP_CARD -> {
                if (trimmed.isNotBlank()) lookupCard(trimmed, assignToSlot = null)
            }
            EditorPurpose.PICK_CARD_A -> {
                if (trimmed.isNotBlank()) lookupCard(trimmed, assignToSlot = "A")
            }
            EditorPurpose.PICK_CARD_B -> {
                if (trimmed.isNotBlank()) lookupCard(trimmed, assignToSlot = "B")
            }
        }
    }

    fun pickSuggestion(cardName: String) {
        val slot = _pendingAssignSlot.value
        lookupCard(cardName, forceRefresh = false, assignToSlot = slot)
    }

    fun dismissSuggestions() {
        _suggestions.value = emptyList()
        _errorMessage.value = null
        _pendingAssignSlot.value = null
    }

    fun lookupCard(query: String, forceRefresh: Boolean = false, assignToSlot: String? = null) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        _searchQuery.value = trimmed
        _errorMessage.value = null
        _suggestions.value = emptyList()
        _pendingAssignSlot.value = assignToSlot

        if (trimmed.length < 2) {
            _errorMessage.value = "Enter at least 2 characters to search."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.lookupCard(trimmed, forceRefresh = forceRefresh)
            result.onSuccess { card ->
                _pendingAssignSlot.value = null
                _selectedCard.value = card
                if (assignToSlot == "A") {
                    _cardA.value = card
                    _tab.value = RulingsTab.COMPARE
                } else if (assignToSlot == "B") {
                    _cardB.value = card
                    _tab.value = RulingsTab.COMPARE
                }
                _recentCards.value = repository.getRecentCachedCards()
            }.onFailure { err ->
                if (assignToSlot == null) {
                    _selectedCard.value = null
                }
                val alts = repository.autocompleteCardNames(trimmed)
                _suggestions.value = alts
                _errorMessage.value = if (alts.isNotEmpty()) {
                    when (assignToSlot) {
                        "A" -> "Did you mean? Tap a card for Card A:"
                        "B" -> "Did you mean? Tap a card for Card B:"
                        else -> "Did you mean? Tap a card below:"
                    }
                } else {
                    _pendingAssignSlot.value = null
                    err.message ?: "Card not found. Check spelling or Wi-Fi connection."
                }
            }
            _isLoading.value = false
        }
    }

    fun openCachedCard(card: MtgCardDetail) {
        _selectedCard.value = card
        _searchQuery.value = card.name
        _errorMessage.value = null
        _suggestions.value = emptyList()
    }

    fun clearSelectedCard() {
        _selectedCard.value = null
        _errorMessage.value = null
        _suggestions.value = emptyList()
    }

    fun assignSelectedToCardA() {
        _selectedCard.value?.let {
            _cardA.value = it
            _tab.value = RulingsTab.COMPARE
        }
    }

    fun assignSelectedToCardB() {
        _selectedCard.value?.let {
            _cardB.value = it
            _tab.value = RulingsTab.COMPARE
        }
    }

    fun clearCardA() {
        _cardA.value = null
    }

    fun clearCardB() {
        _cardB.value = null
    }

    fun toggleTopic(topicId: String) {
        _expandedTopicId.value = if (_expandedTopicId.value == topicId) null else topicId
    }
}

class MtgRulingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, MtgRulingsViewModel>(sealedActivity) {

    private val repository = GameRepository.getInstance {
        lightContext.buildDatabase(
            GameDatabase::class.java,
            GameRepository.DATABASE_NAME,
        ) {
            fallbackToDestructiveMigration(true)
        }
    }

    private val isForegroundFlow = MutableStateFlow(true)

    override val viewModelClass: Class<MtgRulingsViewModel>
        get() = MtgRulingsViewModel::class.java

    override fun createViewModel() = MtgRulingsViewModel(repository)

    override fun willShow() {
        super.willShow()
        isForegroundFlow.value = true
    }

    override fun willHide() {
        isForegroundFlow.value = false
        super.willHide()
    }

    override fun onAppPause() {
        isForegroundFlow.value = false
        super.onAppPause()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val tab by viewModel.tab.collectAsState()
        val editorPurpose by viewModel.editorPurpose.collectAsState()
        val searchQuery by viewModel.searchQuery.collectAsState()
        val suggestions by viewModel.suggestions.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val errorMessage by viewModel.errorMessage.collectAsState()
        val selectedCard by viewModel.selectedCard.collectAsState()
        val recentCards by viewModel.recentCards.collectAsState()
        val cardA by viewModel.cardA.collectAsState()
        val cardB by viewModel.cardB.collectAsState()
        val guideQuery by viewModel.guideQuery.collectAsState()
        val expandedTopicId by viewModel.expandedTopicId.collectAsState()
        val isForeground by isForegroundFlow.collectAsState()

        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack {
                        if (editorPurpose != null) viewModel.closeEditor() else goBack()
                    },
            ) {
                if (editorPurpose != null) {
                    val title = when (editorPurpose) {
                        EditorPurpose.LOOKUP_CARD -> "Lookup MTG Card"
                        EditorPurpose.PICK_CARD_A -> "Select Card A"
                        EditorPurpose.PICK_CARD_B -> "Select Card B"
                        EditorPurpose.FILTER_GUIDE -> "Filter Rules Guide"
                        null -> ""
                    }
                    LightTextInputEditor(
                        title = title,
                        state = textFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        submitIcon = LightIcons.SEARCH,
                        singleLine = true,
                        onSubmit = { seq -> viewModel.submitEditorText(seq.toString()) },
                        onBack = { viewModel.closeEditor() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        BatteryTopBar(
                            isForeground = isForeground,
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("MTG Rulings"),
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )

                        // Tab selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RulingsTab.entries.forEach { t ->
                                val active = t == tab
                                val content = LightThemeTokens.colors.content
                                val bg = LightThemeTokens.colors.background
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (active) content else bg,
                                            RoundedCornerShape(5.dp),
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = content.copy(alpha = if (active) 1f else 0.45f),
                                            shape = RoundedCornerShape(5.dp),
                                        )
                                        .lightClickable { viewModel.selectTab(t) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = t.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) bg else content,
                                    )
                                }
                            }
                        }

                        when (tab) {
                            RulingsTab.LOOKUP -> LookupTabContent(
                                searchQuery = searchQuery,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                suggestions = suggestions,
                                selectedCard = selectedCard,
                                recentCards = recentCards,
                                expandedTopicId = expandedTopicId,
                                onOpenSearch = {
                                    textFieldState.setTextAndPlaceCursorAtEnd(searchQuery)
                                    viewModel.openEditor(EditorPurpose.LOOKUP_CARD)
                                },
                                onPickSuggestion = { name -> viewModel.pickSuggestion(name) },
                                onOpenCached = { card -> viewModel.openCachedCard(card) },
                                onRefreshCard = { card -> viewModel.lookupCard(card.name, forceRefresh = true) },
                                onClearCard = { viewModel.clearSelectedCard() },
                                onSetCardA = { viewModel.assignSelectedToCardA() },
                                onSetCardB = { viewModel.assignSelectedToCardB() },
                                onToggleTopic = { id -> viewModel.toggleTopic(id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )

                            RulingsTab.COMPARE -> CompareTabContent(
                                cardA = cardA,
                                cardB = cardB,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                suggestions = suggestions,
                                expandedTopicId = expandedTopicId,
                                onPickA = {
                                    textFieldState.setTextAndPlaceCursorAtEnd(cardA?.name.orEmpty())
                                    viewModel.openEditor(EditorPurpose.PICK_CARD_A)
                                },
                                onPickB = {
                                    textFieldState.setTextAndPlaceCursorAtEnd(cardB?.name.orEmpty())
                                    viewModel.openEditor(EditorPurpose.PICK_CARD_B)
                                },
                                onPickSuggestion = { name -> viewModel.pickSuggestion(name) },
                                onDismissSuggestions = { viewModel.dismissSuggestions() },
                                onClearA = { viewModel.clearCardA() },
                                onClearB = { viewModel.clearCardB() },
                                onToggleTopic = { id -> viewModel.toggleTopic(id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )

                            RulingsTab.GUIDE -> GuideTabContent(
                                guideQuery = guideQuery,
                                expandedTopicId = expandedTopicId,
                                onOpenFilter = {
                                    textFieldState.setTextAndPlaceCursorAtEnd(guideQuery)
                                    viewModel.openEditor(EditorPurpose.FILTER_GUIDE)
                                },
                                onClearFilter = { viewModel.submitEditorText("") },
                                onToggleTopic = { id -> viewModel.toggleTopic(id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LookupTabContent(
    searchQuery: String,
    isLoading: Boolean,
    errorMessage: String?,
    suggestions: List<String>,
    selectedCard: MtgCardDetail?,
    recentCards: List<MtgCardDetail>,
    expandedTopicId: String?,
    onOpenSearch: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onOpenCached: (MtgCardDetail) -> Unit,
    onRefreshCard: (MtgCardDetail) -> Unit,
    onClearCard: () -> Unit,
    onSetCardA: () -> Unit,
    onSetCardB: () -> Unit,
    onToggleTopic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePad = 1.5f.gridUnitsAsDp()
    val content = LightThemeTokens.colors.content

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePad, vertical = 4.dp),
        ) {
            LightTextField(
                label = "",
                value = searchQuery,
                placeholder = "Type MTG card name (e.g. Blood Moon)...",
                onClick = onOpenSearch,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = sidePad),
        ) {
            if (isLoading) {
                LightText(
                    text = "Looking up official Oracle text & rulings...",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(end = sidePad, top = 10.dp),
                )
            }

            errorMessage?.let { msg ->
                LightText(
                    text = msg,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(end = sidePad, top = 8.dp, bottom = 6.dp),
                )
            }

            if (suggestions.isNotEmpty()) {
                suggestions.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = sidePad)
                            .lightClickable { onPickSuggestion(name) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        LightText(
                            text = name,
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = "→",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = content.copy(alpha = 0.22f),
                        modifier = Modifier.padding(end = sidePad),
                    )
                }
            } else if (selectedCard != null) {
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = sidePad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        LightText(text = selectedCard.name, variant = LightTextVariant.Subheading)
                        val meta = buildString {
                            if (selectedCard.manaCost.isNotBlank()) append(selectedCard.manaCost).append(" • ")
                            append(selectedCard.typeLine)
                            selectedCard.powerToughness?.let { append(" • ").append(it) }
                        }
                        LightText(
                            text = meta,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // Action chips: Set Card A, Set Card B, Refresh, Back to Recent
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = sidePad),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MiniPill("Compare A", onSetCardA)
                    MiniPill("Compare B", onSetCardB)
                    MiniPill("Refresh") { onRefreshCard(selectedCard) }
                    MiniPill("Recent", onClearCard)
                }

                if (selectedCard.fromOfflineCache) {
                    LightText(
                        text = "Saved offline • Works without Wi-Fi",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad, top = 6.dp),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                LightText(text = "ORACLE TEXT", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(4.dp))
                LightText(
                    text = selectedCard.oracleText.ifBlank { "(No Oracle text)" },
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(end = sidePad),
                )

                // Automatic rules clarification hints
                val relatedTopics = MtgRulesGuide.detectInteractions(selectedCard, null)
                if (relatedTopics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    LightText(
                        text = "RELEVANT RULES CLARIFICATIONS",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad, bottom = 4.dp),
                    )
                    relatedTopics.forEach { topic ->
                        RulesTopicCard(
                            topic = topic,
                            expanded = expandedTopicId == topic.id,
                            onToggle = { onToggleTopic(topic.id) },
                            modifier = Modifier.padding(end = sidePad),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                LightText(
                    text = "OFFICIAL RULINGS (${selectedCard.rulings.size})",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(end = sidePad, bottom = 4.dp),
                )

                if (selectedCard.rulings.isEmpty()) {
                    LightText(
                        text = "No special Wizards/Gatherer rulings listed for this card.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad, bottom = 14.dp),
                    )
                } else {
                    selectedCard.rulings.forEach { ruling ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = sidePad, top = 6.dp, bottom = 8.dp),
                        ) {
                            if (ruling.publishedAt.isNotBlank()) {
                                LightText(
                                    text = ruling.publishedAt,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            LightText(
                                text = ruling.comment,
                                variant = LightTextVariant.Paragraph,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = content.copy(alpha = 0.16f),
                            modifier = Modifier.padding(end = sidePad),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                if (recentCards.isNotEmpty()) {
                    LightText(
                        text = "OFFLINE SAVED CARDS (${recentCards.size})",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad, bottom = 6.dp),
                    )
                    recentCards.forEach { card ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = sidePad)
                                .lightClickable { onOpenCached(card) }
                                .padding(vertical = 8.dp),
                        ) {
                            LightText(text = card.name, variant = LightTextVariant.Paragraph)
                            LightText(
                                text = "${card.typeLine} • ${card.rulings.size} rulings",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = content.copy(alpha = 0.16f),
                            modifier = Modifier.padding(end = sidePad),
                        )
                    }
                } else {
                    LightText(
                        text = "Search any Magic card by name to load its latest Oracle text and official Wizards rulings via Scryfall. Every looked-up card is saved permanently offline.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad, top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LightText(
                        text = "Try: Blood Moon, Urborg, Humility, Doubling Season, or Rest in Peace.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(end = sidePad),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CompareTabContent(
    cardA: MtgCardDetail?,
    cardB: MtgCardDetail?,
    isLoading: Boolean,
    errorMessage: String?,
    suggestions: List<String>,
    expandedTopicId: String?,
    onPickA: () -> Unit,
    onPickB: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onDismissSuggestions: () -> Unit,
    onClearA: () -> Unit,
    onClearB: () -> Unit,
    onToggleTopic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePad = 1.5f.gridUnitsAsDp()
    val content = LightThemeTokens.colors.content
    val interactions = MtgRulesGuide.detectInteractions(cardA, cardB)

    LightScrollView(
        modifier = modifier.padding(start = sidePad),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        LightText(
            text = "Compare two cards to clarify tricky interactions (Layers, Protection, Replacement Effects, Combat, etc.).",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(end = sidePad, bottom = 8.dp),
        )

        CompareSlotBox(
            slotLabel = "CARD A",
            card = cardA,
            onPick = onPickA,
            onClear = onClearA,
            modifier = Modifier.padding(end = sidePad),
        )
        Spacer(modifier = Modifier.height(8.dp))
        CompareSlotBox(
            slotLabel = "CARD B",
            card = cardB,
            onPick = onPickB,
            onClear = onClearB,
            modifier = Modifier.padding(end = sidePad),
        )

        if (isLoading) {
            LightText(
                text = "Loading card...",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(end = sidePad, top = 8.dp),
            )
        }
        errorMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = sidePad, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(
                    text = msg,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.weight(1f),
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    MiniPill("Cancel", onDismissSuggestions)
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            suggestions.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = sidePad)
                        .lightClickable { onPickSuggestion(name) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LightText(
                        text = name,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.weight(1f),
                    )
                    LightText(
                        text = "→",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = content.copy(alpha = 0.22f),
                    modifier = Modifier.padding(end = sidePad),
                )
            }
        }

        if (interactions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            LightText(
                text = "INTERACTION CLARIFICATIONS",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(end = sidePad, bottom = 6.dp),
            )
            interactions.forEach { topic ->
                RulesTopicCard(
                    topic = topic,
                    expanded = expandedTopicId == topic.id,
                    onToggle = { onToggleTopic(topic.id) },
                    modifier = Modifier.padding(end = sidePad),
                )
            }
        } else if (cardA != null || cardB != null) {
            Spacer(modifier = Modifier.height(12.dp))
            LightText(
                text = "No special keyword conflicts detected automatically—check each card's official rulings below.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(end = sidePad),
            )
        }

        // Combined official rulings summary
        listOfNotNull(cardA, cardB).forEach { card ->
            if (card.rulings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                LightText(
                    text = "${card.name.uppercase()} — RULINGS (${card.rulings.size})",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(end = sidePad, bottom = 4.dp),
                )
                card.rulings.take(4).forEach { ruling ->
                    LightText(
                        text = "• ${ruling.comment}",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(end = sidePad, bottom = 5.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun CompareSlotBox(
    slotLabel: String,
    card: MtgCardDetail?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, content.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .lightClickable(onClick = onPick)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(text = slotLabel, variant = LightTextVariant.Detail, lighten = true)
            if (card != null) {
                MiniPill("Clear", onClear)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (card == null) {
            LightText(text = "Tap to search & choose $slotLabel", variant = LightTextVariant.Paragraph)
        } else {
            LightText(text = card.name, variant = LightTextVariant.Subheading)
            LightText(text = card.typeLine, variant = LightTextVariant.Detail, lighten = true)
            if (card.oracleText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                LightText(text = card.oracleText, variant = LightTextVariant.Detail)
            }
        }
    }
}

@Composable
private fun GuideTabContent(
    guideQuery: String,
    expandedTopicId: String?,
    onOpenFilter: () -> Unit,
    onClearFilter: () -> Unit,
    onToggleTopic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePad = 1.5f.gridUnitsAsDp()
    val topics = MtgRulesGuide.searchTopics(guideQuery)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePad, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LightTextField(
                    label = "",
                    value = guideQuery,
                    placeholder = "Filter rules (e.g. trample, ward, layers)...",
                    onClick = onOpenFilter,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (guideQuery.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                MiniPill("Clear", onClearFilter)
            }
        }

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = sidePad),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            LightText(
                text = "100% OFFLINE COMMON RULINGS (${topics.size})",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(end = sidePad, bottom = 6.dp),
            )

            topics.forEach { topic ->
                RulesTopicCard(
                    topic = topic,
                    expanded = expandedTopicId == topic.id,
                    onToggle = { onToggleTopic(topic.id) },
                    modifier = Modifier.padding(end = sidePad),
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun RulesTopicCard(
    topic: MtgRulesTopic,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = 1.dp,
                color = content.copy(alpha = if (expanded) 0.7f else 0.3f),
                shape = RoundedCornerShape(6.dp),
            )
            .lightClickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(text = topic.title, variant = LightTextVariant.Paragraph)
                LightText(
                    text = topic.category,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
            LightText(
                text = if (expanded) "−" else "+",
                variant = LightTextVariant.Subheading,
            )
        }
        LightText(
            text = topic.summary,
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = content.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(6.dp))
            topic.keyPoints.forEach { point ->
                LightText(
                    text = "• $point",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPill(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
    }
}

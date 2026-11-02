package com.dashdeck.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dashdeck.dashDeck
import com.dashdeck.render.DashboardRenderer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Every shortcut as a grid, for when the dashboard's shortcut widget can't show them all. */
class ShortcutsScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.dashDeck
    private val runner = ShortcutRunner(carContext, lifecycleScope)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.configRepository.config.map { it.shortcuts }.distinctUntilChanged().collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Add shortcuts in the DashDeck phone app")
        app.configRepository.config.value.shortcuts.take(gridLimit()).forEach { shortcut ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(shortcut.name.ifBlank { shortcut.kind.label })
                    .setText(shortcut.kind.label)
                    .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, DashboardRenderer.iconFor(shortcut.kind))).build())
                    .setOnClickListener { runner.run(shortcut) }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle("Shortcuts")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun gridLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } else {
            6
        }
}

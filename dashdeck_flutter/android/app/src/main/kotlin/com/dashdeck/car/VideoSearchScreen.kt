package com.dashdeck.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.dashdeck.video.VideoBrowser

/**
 * The car's own keyboard for searching YouTube. Android Auto only offers the keyboard while parked,
 * which matches when the video page shows anything.
 */
class VideoSearchScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = SearchTemplate.Builder(
        object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) VideoBrowser.search(searchText.trim())
                screenManager.pop()
            }
        },
    )
        .setHeaderAction(Action.BACK)
        .setSearchHint("Search YouTube")
        .setShowKeyboardByDefault(true)
        .setItemList(ItemList.Builder().setNoItemsMessage("Type a search and press Enter").build())
        .build()
}

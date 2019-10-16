package io.github.yusukeiwaki.better_always_drink.shop_list

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.observe
import androidx.lifecycle.viewModelScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.yusukeiwaki.better_always_drink.extension.cameraPositionAsFlow
import io.github.yusukeiwaki.better_always_drink.extension.observeOnce
import io.github.yusukeiwaki.better_always_drink.model.Shop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.DrawableCompat
import android.graphics.Bitmap
import androidx.core.content.res.ResourcesCompat
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.google.android.gms.maps.model.BitmapDescriptor
import android.graphics.Canvas
import android.view.ViewGroup
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.github.yusukeiwaki.better_always_drink.R


class ShopListActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var viewPager: ViewPager2
    private lateinit var googleMap: GoogleMap
    private lateinit var bottomSheet: BottomSheetBehavior<View>
    private val viewModel: ShopListViewModel by viewModels()

    private val markers: ArrayList<Marker> = arrayListOf()
    private val alwaysShopUuid: String? by AlwaysPreference(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop_list)
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE.or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN))

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        viewPager = findViewById(R.id.view_pager)
        bottomSheet = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet_container))
        val viewPagerAdapter = ShopListAdapter()

        // 左右のカードを少しだけ見えるようにする
        viewPager.offscreenPageLimit = 2
        val shopCardMargin = viewPager.resources.getDimensionPixelSize(R.dimen.shop_card_margin)
        viewPager.setPageTransformer { page, position ->
            val offset = position * shopCardMargin * 2
            page.translationX = -offset
        }

        viewModel.shopList.observe(this) { newList ->
            var handled = false
            if (viewPagerAdapter.itemCount == 0) { // 初回に限り
                alwaysShopUuid?.let { uuid -> // お気に入りのお店があれば
                    val newPage = newList.indexOfFirst { shop -> shop.uuid == uuid }
                    if (newPage >= 0) {
                        // submit後にそのページにフォーカスを合わせる
                        viewPagerAdapter.submitList(newList) {
                            viewPager.setCurrentItem(newPage, false)
                        }
                        handled = true
                    }
                }
            }

            if (!handled) viewPagerAdapter.submitList(newList)
        }
        viewModel.focusedShop.observe(this) { focusedShop ->
            focusedShop?.let {
                viewPager.currentItem = viewModel.shopList.value!!.indexOfFirst { shop -> shop.uuid == focusedShop.uuid }
            }
        }
        viewPager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback(){
            override fun onPageSelected(position: Int) {
                viewModel.onFocusedShopChanged(viewModel.shopList.value!![position])
            }
        })
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(view: View, slideOffset: Float) { }

            override fun onStateChanged(view: View, newState: Int) {
                updateBottomSheetState(newState)
            }
        })
        updateBottomSheetState(bottomSheet.state)
        viewPager.adapter = viewPagerAdapter
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // 初期位置を決めないと、アフリカの海が表示されるので、適当に初期位置を設定しておく。
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(33.2343214,131.6082574),15.0f))

        viewModel.focusedShop.observe(this) { focusedShop ->
            val defaultMarker = createBitmapDescriptorFromVector(R.drawable.ic_place_default_24dp, getColor(R.color.markerDefault))
            markers.forEach { marker ->
                (marker.tag as? Shop)?.let { shop ->
                    if (shop.uuid == focusedShop?.uuid) {
                        marker.setIcon(createBitmapDescriptorFromVector(R.drawable.ic_local_cafe_24dp, getColor(R.color.markerSelected)))
                        marker.showInfoWindow()
                    } else {
                        marker.setIcon(defaultMarker)
                        if (marker.isInfoWindowShown) {
                            marker.hideInfoWindow()
                        }
                    }
                }
            }

            focusedShop?.let {
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(LatLng(it.lat, it.lng)))
                onShopFocused()
            } ?: run {
                onShopUnfocused()
            }
        }
        viewModel.shopList.observe(this) { shopList ->
            markers.clear()
            val defaultMarker = createBitmapDescriptorFromVector(R.drawable.ic_place_default_24dp, getColor(R.color.markerDefault))
            shopList.forEach { shop ->
                val title =
                    if (shop.roughLocationDescription.isNullOrBlank())
                        shop.name
                    else
                        "${shop.roughLocationDescription} - ${shop.name}"

                val markerOptions = MarkerOptions()
                    .icon(defaultMarker)
                    .position(LatLng(shop.lat, shop.lng))
                    .title(title)
                val marker = googleMap.addMarker(markerOptions)
                marker.tag = shop
                markers.add(marker)
            }
        }
        viewModel.defaultCameraUpdate.observeOnce(this) { defaultCameraUpdate ->
            if (!viewModel.hasFocusedShop) {
                googleMap.moveCamera(defaultCameraUpdate)
            }
        }

        googleMap.setOnMarkerClickListener { marker ->
            (marker.tag as? Shop)?.let { shop ->
                viewModel.onFocusedShopChanged(shop)
            }
            false
        }
        googleMap.setOnMapClickListener {
            if (!markers.any { it.isInfoWindowShown }) {
                viewModel.onFocusedShopChanged(null)
            }
        }
        viewModel.viewModelScope.launch {
            googleMap.cameraPositionAsFlow().debounce(300).collect { cameraPosition ->
                viewModel.onLatLngChanged(cameraPosition.target)
                viewModel.onZoomLevelChanged(cameraPosition.zoom)
            }
        }
    }

    private fun updateBottomSheetState(newState: Int) {
        val newViewPagerHeight =
            if (newState == BottomSheetBehavior.STATE_EXPANDED)
                ViewGroup.LayoutParams.MATCH_PARENT
            else
                bottomSheet.peekHeight

        if (viewPager.layoutParams.height != newViewPagerHeight) {
            viewPager.layoutParams = viewPager.layoutParams.apply { height = newViewPagerHeight }
        }

        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
            viewModel.onFocusedShopChanged(null) // ボトムシートを手で引っ込めた場合には、キャンセル扱いにする
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.and(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv())
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.or(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    private fun onShopFocused() {
        if (bottomSheet.state == BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun onShopUnfocused() {
        if (bottomSheet.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    fun createBitmapDescriptorFromVector(@DrawableRes vectorResourceId: Int, @ColorInt tintColor: Int): BitmapDescriptor {
        val vectorDrawable = ResourcesCompat.getDrawable(resources, vectorResourceId, null)
            ?: return BitmapDescriptorFactory.defaultMarker()

        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight())
        DrawableCompat.setTint(vectorDrawable, tintColor)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    override fun onBackPressed() {
        if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
        } else if (viewModel.hasFocusedShop) {
            viewModel.onFocusedShopChanged(null)
        } else {
            super.onBackPressed()
        }
    }
}

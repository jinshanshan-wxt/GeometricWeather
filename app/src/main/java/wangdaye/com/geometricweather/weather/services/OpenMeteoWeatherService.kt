package wangdaye.com.geometricweather.weather.services

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import com.google.gson.JsonObject
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import wangdaye.com.geometricweather.common.basic.models.Location
import wangdaye.com.geometricweather.common.basic.models.options.provider.WeatherSource
import wangdaye.com.geometricweather.common.rxjava.BaseObserver
import wangdaye.com.geometricweather.common.rxjava.ObserverContainer
import wangdaye.com.geometricweather.common.rxjava.SchedulerTransformer
import wangdaye.com.geometricweather.settings.SettingsManager
import wangdaye.com.geometricweather.weather.apis.OpenMeteoApi
import wangdaye.com.geometricweather.weather.converters.OpenMeteoResultConverter
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class OpenMeteoWeatherService @Inject constructor(
    private val api: OpenMeteoApi
) : WeatherService() {

    private val requests = CompositeDisposable()

    override fun requestWeather(context: Context, location: Location, callback: RequestWeatherCallback) {
        if (!location.isUsable || !validCoordinates(location.latitude, location.longitude)) {
            callback.requestWeatherFailed(location)
            return
        }

        Observable.zip(
            api.forecast(location.latitude, location.longitude),
            api.airQuality(location.latitude, location.longitude)
                .subscribeOn(Schedulers.io())
                .timeout(6, TimeUnit.SECONDS)
                .onErrorReturnItem(JsonObject())
        ) { forecast, airQuality ->
            OpenMeteoResultConverter.convert(context, location, forecast, airQuality)
        }
            .compose(SchedulerTransformer.create())
            .subscribe(ObserverContainer(requests, object : BaseObserver<Location>() {
                override fun onSucceed(result: Location) = callback.requestWeatherSuccess(result)
                override fun onFailed() = callback.requestWeatherFailed(location)
            }))
    }

    override fun requestLocation(context: Context, query: String): List<Location> {
        val language = SettingsManager.getInstance(context).language.locale.language
        val response = try {
            api.search(query.trim(), language).execute().body()
        } catch (_: IOException) {
            null
        } ?: return emptyList()

        return response.get("results")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { entry ->
            val result = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = result.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return@mapNotNull null
            val latitude = result.get("latitude")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat ?: return@mapNotNull null
            val longitude = result.get("longitude")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat ?: return@mapNotNull null
            if (!validCoordinates(latitude, longitude)) return@mapNotNull null
            val name = result.text("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val countryCode = result.text("country_code")
            Location(
                cityId = "geonames:$id", latitude = latitude, longitude = longitude,
                timeZone = result.text("timezone").takeIf { it.isNotEmpty() }
                    ?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault(),
                country = result.text("country").ifEmpty { countryCode },
                province = result.text("admin1"), city = name, district = "",
                weatherSource = WeatherSource.OPEN_METEO,
                isCurrentPosition = false, isResidentPosition = false, isChina = countryCode == "CN"
            )
        }.orEmpty()
    }

    @Suppress("DEPRECATION")
    override fun requestLocation(context: Context, location: Location, callback: RequestLocationCallback) {
        val query = "${location.latitude},${location.longitude}"
        if (!validCoordinates(location.latitude, location.longitude)) {
            callback.requestLocationFailed(query)
            return
        }

        Observable.fromCallable<List<Address>> {
            try {
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                // The system geocoder can send the coordinates to a separate network service.
                if (manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true &&
                    Geocoder.isPresent()
                ) {
                    Geocoder(context).getFromLocation(
                        location.latitude.toDouble(), location.longitude.toDouble(), 1
                    ).orEmpty()
                } else {
                    emptyList()
                }
            } catch (error: Exception) {
                Log.d("GeoWeatherLocation", "Reverse geocoder returned no address", error)
                emptyList()
            }
        }
            .subscribeOn(Schedulers.io())
            .timeout(3, TimeUnit.SECONDS)
            .onErrorReturn { error ->
                Log.d("GeoWeatherLocation", "Reverse geocoding unavailable", error)
                emptyList()
            }
            .map { addresses ->
                val address = addresses.firstOrNull()
                val unchangedPosition = location.isUsable && location.weatherSource == WeatherSource.OPEN_METEO &&
                    location.cityId == "gps:$query"
                Location(
                    cityId = "gps:$query", latitude = location.latitude, longitude = location.longitude,
                    timeZone = location.timeZone,
                    country = address?.countryName ?: if (unchangedPosition) location.country else "",
                    province = address?.adminArea ?: if (unchangedPosition) location.province else "",
                    city = address?.locality ?: address?.subAdminArea ?: if (unchangedPosition) location.city else "",
                    district = address?.subLocality ?: if (unchangedPosition) location.district else "",
                    weatherSource = WeatherSource.OPEN_METEO,
                    isCurrentPosition = location.isCurrentPosition, isResidentPosition = location.isResidentPosition,
                    isChina = address?.countryCode?.let { it == "CN" } ?: (unchangedPosition && location.isChina)
                )
            }
            .compose(SchedulerTransformer.create())
            .subscribe(ObserverContainer(requests, object : BaseObserver<Location>() {
                override fun onSucceed(result: Location) = callback.requestLocationSuccess(query, listOf(result))
                override fun onFailed() = callback.requestLocationFailed(query)
            }))
    }

    override fun cancel() = requests.clear()

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun validCoordinates(latitude: Float, longitude: Float): Boolean =
        latitude in -90f..90f && longitude in -180f..180f
}

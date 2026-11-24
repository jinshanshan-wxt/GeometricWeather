package wangdaye.com.geometricweather.location.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit

private const val FIX_TIMEOUT_MILLIS = 20_000L
private val MAX_CACHED_AGE_NANOS = TimeUnit.HOURS.toNanos(48)

@SuppressLint("MissingPermission")
open class AndroidLocationService : LocationService() {

    private val handler = Handler(Looper.getMainLooper())
    private var activeRequest: FixRequest? = null

    override fun requestLocation(context: Context, callback: LocationCallback) = onMain {
        cancelActiveRequest()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasPermissions(context)) {
            callback.onCompleted(null)
            return@onMain
        }

        val providers = try {
            val usable = listOf(
                LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER
            )
            manager.getProviders(true).filter { it in usable }
        } catch (e: SecurityException) {
            Log.w("GeoWeatherLocation", "Cannot enumerate location providers", e)
            emptyList()
        }

        if (providers.isEmpty()) {
            callback.onCompleted(null)
            return@onMain
        }

        val request = FixRequest(manager, callback)
        activeRequest = request
        val registered = providers.map { provider ->
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, request, Looper.getMainLooper())
                true
            } catch (e: SecurityException) {
                Log.d("GeoWeatherLocation", "Location permission unavailable for " + provider, e)
                false
            } catch (e: IllegalArgumentException) {
                Log.d("GeoWeatherLocation", "Location provider unavailable: " + provider, e)
                false
            }
        }.any { it }

        if (registered) {
            val timeout = Runnable { request.complete(lastKnownLocation(manager, providers)) }
            request.timeout = timeout
            handler.postDelayed(timeout, FIX_TIMEOUT_MILLIS)
        } else {
            request.complete(lastKnownLocation(manager, providers))
        }
    }

    private fun lastKnownLocation(manager: LocationManager, providers: List<String>): Location? {
        val now = SystemClock.elapsedRealtimeNanos()
        val latest = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.filter { now - it.elapsedRealtimeNanos in 0..MAX_CACHED_AGE_NANOS }
            .maxByOrNull { it.elapsedRealtimeNanos }

        if (latest == null) {
            Log.d("GeoWeatherLocation", "No fresh fix or usable cached position")
        } else {
            val ageMinutes = TimeUnit.NANOSECONDS.toMinutes(now - latest.elapsedRealtimeNanos)
            Log.d("GeoWeatherLocation", "Using cached position from " + latest.provider + ", age=" + ageMinutes + " min")
        }
        return latest
    }

    override fun cancel() = onMain { cancelActiveRequest() }

    override val permissions: Array<String>
        get() = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun cancelActiveRequest() {
        val request = activeRequest ?: return
        activeRequest = null
        request.timeout?.let { handler.removeCallbacks(it) }
        try {
            request.manager.removeUpdates(request)
        } catch (e: SecurityException) {
            Log.d("GeoWeatherLocation", "Location permission changed before cancellation", e)
        }
    }

    private inner class FixRequest(
        val manager: LocationManager,
        private val callback: LocationCallback
    ) : LocationListener {
        var timeout: Runnable? = null

        fun complete(location: Location?) {
            if (activeRequest !== this) return
            cancelActiveRequest()
            callback.onCompleted(location?.let { Result(it.latitude.toFloat(), it.longitude.toFloat()) })
        }

        override fun onLocationChanged(location: Location) = complete(location)
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
}

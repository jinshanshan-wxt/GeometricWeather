package wangdaye.com.geometricweather.weather.converters

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import wangdaye.com.geometricweather.R
import wangdaye.com.geometricweather.common.basic.models.Location
import wangdaye.com.geometricweather.common.basic.models.weather.AirQuality
import wangdaye.com.geometricweather.common.basic.models.weather.Astro
import wangdaye.com.geometricweather.common.basic.models.weather.Base
import wangdaye.com.geometricweather.common.basic.models.weather.Current
import wangdaye.com.geometricweather.common.basic.models.weather.Daily
import wangdaye.com.geometricweather.common.basic.models.weather.HalfDay
import wangdaye.com.geometricweather.common.basic.models.weather.Hourly
import wangdaye.com.geometricweather.common.basic.models.weather.MoonPhase
import wangdaye.com.geometricweather.common.basic.models.weather.Pollen
import wangdaye.com.geometricweather.common.basic.models.weather.Precipitation
import wangdaye.com.geometricweather.common.basic.models.weather.PrecipitationDuration
import wangdaye.com.geometricweather.common.basic.models.weather.PrecipitationProbability
import wangdaye.com.geometricweather.common.basic.models.weather.Temperature
import wangdaye.com.geometricweather.common.basic.models.weather.UV
import wangdaye.com.geometricweather.common.basic.models.weather.Weather
import wangdaye.com.geometricweather.common.basic.models.weather.WeatherCode
import wangdaye.com.geometricweather.common.basic.models.weather.Wind
import wangdaye.com.geometricweather.common.basic.models.weather.WindDegree
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

object OpenMeteoResultConverter {

    fun convert(context: Context, location: Location, forecast: JsonObject, airQuality: JsonObject): Location {
        val zone = forecast.get("timezone")?.takeUnless { it.isJsonNull }?.asString
            ?.let { TimeZone.getTimeZone(it) } ?: location.timeZone
        val current = requireNotNull(forecast.getAsJsonObject("current"))
        val hourly = requireNotNull(forecast.getAsJsonObject("hourly"))
        val daily = requireNotNull(forecast.getAsJsonObject("daily"))
        val publishedAt = requireNotNull(current.number("time")).toLong() * 1000
        val fetchedAt = System.currentTimeMillis()
        val hours = requireNotNull(hourly.array("time")).mapIndexedNotNull { i, time ->
            val temperature = hourly.number("temperature_2m", i) ?: return@mapIndexedNotNull null
            val code = hourly.number("weather_code", i)?.toInt()
            val condition = condition(context, code)
            val stamp = time.asLong * 1000
            ForecastHour(code, Hourly(
                Date(stamp), stamp, hourly.number("is_day", i)?.toInt() == 1,
                condition.text, condition.code,
                temperature(temperature, hourly.number("apparent_temperature", i)),
                precipitation(hourly.number("precipitation", i)),
                probability(hourly.number("precipitation_probability", i)),
                wind(context, hourly.number("wind_speed_10m", i), hourly.number("wind_direction_10m", i)),
                uv(hourly.number("uv_index", i))
            ))
        }

        val days = requireNotNull(daily.array("time")).mapIndexedNotNull { i, time ->
            val maximum = daily.number("temperature_2m_max", i) ?: return@mapIndexedNotNull null
            val minimum = daily.number("temperature_2m_min", i) ?: return@mapIndexedNotNull null
            val start = time.asLong * 1000
            val sunrise = daily.timestamp("sunrise", i)
            val sunset = daily.timestamp("sunset", i)
            val nextSunrise = daily.timestamp("sunrise", i + 1)
            val nextDay = daily.timestamp("time", i + 1) ?: (start + 86_400_000)
            val daytime = hours.filter { it.value.time in start until nextDay && it.value.isDaylight }
            val night = if (sunset != null) {
                hours.filter { it.value.time in sunset until (nextSunrise ?: nextDay) && !it.value.isDaylight }
            } else {
                hours.filter { it.value.time in start until nextDay && !it.value.isDaylight }
            }
            val dailyCondition = condition(context, daily.number("weather_code", i)?.toInt())

            Daily(
                Date(start), start,
                halfDay(context, daytime, dailyCondition, maximum, daily.number("apparent_temperature_max", i)),
                halfDay(context, night, dailyCondition, minimum, daily.number("apparent_temperature_min", i)),
                Astro(sunrise?.let { Date(it) }, sunset?.let { Date(it) }), Astro(null, null),
                MoonPhase(null, null), airQuality(context, null), Pollen(
                    null, null, null, null, null, null, null, null, null, null, null, null
                ),
                uv(daily.number("uv_index_max", i)),
                (daily.number("sunshine_duration", i)?.toFloat() ?: 0f) / 3600f
            )
        }

        val currentHour = hours.lastOrNull { it.value.time <= publishedAt }?.value
        val currentHourIndex = requireNotNull(hourly.array("time")).indexOfLast { it.asLong * 1000 <= publishedAt }
        val upcoming = hours.map { it.value }.filter { it.time >= publishedAt }
        require(days.isNotEmpty() && upcoming.isNotEmpty()) { "Incomplete Open-Meteo forecast" }
        val condition = condition(context, current.number("weather_code")?.toInt())

        val weather = Weather(
            Base(location.cityId, fetchedAt, Date(publishedAt), publishedAt, Date(fetchedAt), fetchedAt),
            Current(
                condition.text, condition.code,
                temperature(requireNotNull(current.number("temperature_2m")), current.number("apparent_temperature")),
                precipitation(current.number("precipitation")),
                currentHour?.precipitationProbability ?: probability(null),
                wind(context, current.number("wind_speed_10m"), current.number("wind_direction_10m")),
                currentHour?.getUV() ?: uv(null), airQuality(context, airQuality.getAsJsonObject("current")),
                current.number("relative_humidity_2m")?.toFloat(),
                current.number("surface_pressure")?.toFloat(),
                hourly.number("visibility", currentHourIndex)?.toFloat()?.div(1000f),
                hourly.number("dew_point_2m", currentHourIndex)?.toDouble()?.roundToInt(),
                current.number("cloud_cover")?.toInt(), null, null, null
            ),
            null, days, upcoming, emptyList(), emptyList()
        )
        return location.copy(timeZone = zone, weather = weather)
    }

    private data class ForecastHour(val weatherCode: Int?, val value: Hourly)
    private data class Condition(val text: String, val code: WeatherCode)

    private fun halfDay(
        context: Context, hours: List<ForecastHour>, fallback: Condition, temp: Number, apparent: Number?
    ): HalfDay {
        val code = hours.mapNotNull { it.weatherCode }.maxOrNull()
        val condition = code?.let { condition(context, it) } ?: fallback
        val precipitation = hours.mapNotNull { it.value.precipitation.total }
        return HalfDay(
            condition.text, condition.text, condition.code, temperature(temp, apparent),
            precipitation(precipitation.takeIf { it.isNotEmpty() }?.sum()),
            probability(hours.mapNotNull { it.value.precipitationProbability.total }.maxOrNull()),
            PrecipitationDuration(null, null, null, null, null),
            hours.maxByOrNull { it.value.wind.speed ?: -1f }?.value?.wind ?: wind(context, null, null),
            null
        )
    }

    private fun condition(context: Context, code: Int?): Condition {
        val (label, icon) = when (code) {
            0 -> R.string.open_meteo_clear to WeatherCode.CLEAR
            1, 2 -> R.string.open_meteo_partly_cloudy to WeatherCode.PARTLY_CLOUDY
            3 -> R.string.open_meteo_overcast to WeatherCode.CLOUDY
            45, 48 -> R.string.open_meteo_fog to WeatherCode.FOG
            51, 53, 55 -> R.string.precipitation_light to WeatherCode.RAIN
            56, 57, 66, 67 -> R.string.open_meteo_freezing_rain to WeatherCode.SLEET
            61, 63, 65, 80, 81, 82 -> R.string.rain to WeatherCode.RAIN
            71, 73, 75, 77, 85, 86 -> R.string.snow to WeatherCode.SNOW
            95, 96, 99 -> R.string.thunderstorm to WeatherCode.THUNDERSTORM
            else -> R.string.open_meteo_unknown to WeatherCode.CLOUDY
        }
        return Condition(context.getString(label), icon)
    }

    private fun temperature(value: Number, apparent: Number?) = Temperature(
        value.toDouble().roundToInt(), null, null, apparent?.toDouble()?.roundToInt(), null, null, null
    )

    private fun precipitation(total: Number?) = Precipitation(total?.toFloat(), null, null, null, null)

    private fun probability(total: Number?) = PrecipitationProbability(total?.toFloat(), null, null, null, null)

    private fun uv(value: Number?) = UV(value?.toDouble()?.roundToInt(), null, null)

    private val compassPoints = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    private fun wind(context: Context, speed: Number?, direction: Number?): Wind {
        val degrees = direction?.toFloat()
        val label = degrees?.let { compassPoints[(it / 45f).roundToInt().mod(compassPoints.size)] }.orEmpty()
        return Wind(
            label, WindDegree(degrees ?: 0f, degrees == null), speed?.toFloat(),
            speed?.let { CommonConverter.getWindLevel(context, it.toDouble()) }.orEmpty()
        )
    }

    private fun airQuality(context: Context, data: JsonObject?): AirQuality {
        val aqi = data?.number("us_aqi")?.toDouble()?.roundToInt()
        return AirQuality(
            aqi?.let { CommonConverter.getAqiQuality(context, it) }, aqi,
            data?.number("pm2_5")?.toFloat(), data?.number("pm10")?.toFloat(),
            data?.number("sulphur_dioxide")?.toFloat(), data?.number("nitrogen_dioxide")?.toFloat(),
            data?.number("ozone")?.toFloat(), data?.number("carbon_monoxide")?.toFloat()
        )
    }

    private fun JsonObject.array(name: String): JsonArray? = getAsJsonArray(name)

    private fun JsonObject.number(name: String, index: Int? = null): Number? {
        val element: JsonElement? = if (index == null) get(name) else {
            array(name)?.let { if (index in 0 until it.size()) it[index] else null }
        }
        return element?.takeUnless { it.isJsonNull }?.asNumber
    }

    private fun JsonObject.timestamp(name: String, index: Int): Long? = number(name, index)?.toLong()?.times(1000)
}

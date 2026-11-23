package wangdaye.com.geometricweather.weather.apis;

import com.google.gson.JsonObject;

import io.reactivex.Observable;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface OpenMeteoApi {

    @GET("v1/forecast?timezone=auto&timeformat=unixtime&forecast_days=7"
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,"
            + "weather_code,cloud_cover,surface_pressure,wind_speed_10m,wind_direction_10m"
            + "&hourly=temperature_2m,apparent_temperature,weather_code,is_day,precipitation,"
            + "precipitation_probability,wind_speed_10m,wind_direction_10m,uv_index,dew_point_2m,visibility"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,"
            + "apparent_temperature_min,sunrise,sunset,sunshine_duration,uv_index_max")
    Observable<JsonObject> forecast(@Query("latitude") float latitude, @Query("longitude") float longitude);

    @GET("https://air-quality-api.open-meteo.com/v1/air-quality"
            + "?current=us_aqi,pm2_5,pm10,sulphur_dioxide,nitrogen_dioxide,ozone,carbon_monoxide")
    Observable<JsonObject> airQuality(@Query("latitude") float latitude, @Query("longitude") float longitude);

    @GET("https://geocoding-api.open-meteo.com/v1/search?count=15")
    Call<JsonObject> search(@Query("name") String name, @Query("language") String language);
}

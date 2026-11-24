package wangdaye.com.geometricweather.remoteviews;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import wangdaye.com.geometricweather.R;
import wangdaye.com.geometricweather.remoteviews.trend.TrendLinearLayout;

public class TrendWidgetLayoutTest {

    @Test
    public void hourlyTrendInflatesFromPlatformTheme() {
        assertTrendInflates(R.layout.widget_trend_hourly);
    }

    @Test
    public void dailyTrendInflatesFromPlatformTheme() {
        assertTrendInflates(R.layout.widget_trend_daily);
    }

    private void assertTrendInflates(int layout) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    instrumentation.getTargetContext().getApplicationContext(),
                    android.R.style.Theme_DeviceDefault_Light_DarkActionBar
            );
            View view = LayoutInflater.from(context).inflate(layout, null, false);
            assertTrue(view instanceof TrendLinearLayout);
            assertEquals(5, ((TrendLinearLayout) view).getChildCount());
        });
    }
}

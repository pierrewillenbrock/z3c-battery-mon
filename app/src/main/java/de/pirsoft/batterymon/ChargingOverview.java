package de.pirsoft.batterymon;

import android.app.Activity;
import android.os.Bundle;
import android.view.Display;
import android.view.Surface;

public class ChargingOverview extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Display display = getWindowManager().getDefaultDisplay();
		switch(display.getRotation()) {
			case Surface.ROTATION_0:
				setContentView(R.layout.activity_charging_overview_r0);
				break;
			case Surface.ROTATION_90:
				setContentView(R.layout.activity_charging_overview_r90);
				break;
			case Surface.ROTATION_180:
				setContentView(R.layout.activity_charging_overview_r180);
				break;
			case Surface.ROTATION_270:
				setContentView(R.layout.activity_charging_overview_r270);
				break;
		}

	}
}

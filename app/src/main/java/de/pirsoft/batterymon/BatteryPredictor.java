package de.pirsoft.batterymon;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.text.DecimalFormat;

import de.pirsoft.batterymon.ChargingModel.State;
import de.pirsoft.batterymon.DataLogger.DataEntry;
import de.pirsoft.batterymon.DataLogger.Status;

public class BatteryPredictor extends Service {
	static final String TAG = "Batterymon.BatteryPred";
	static final int CHARGING_NOTIFICATIONID = 1;
	static final int FULL_NOTIFICATIONID = 2;
	boolean full_notification_shown = false;

	public BatteryPredictor() {
	}

	private class MyAdvancer implements Runnable
	{
		void sched()
		{
			myHandler.postDelayed(this, 5000);
		}

		public void run()
		{
			/* this is not using threading, just Handler + postDelayed */
			if (this != advancer) { return; }

			try {
				checkBattery();
			}
			catch (Exception e) {
				// should not happen.

				AlertDialog.Builder b = new AlertDialog.Builder(BatteryPredictor.this);
				b.setTitle("Error");
				b.setMessage(e.toString());
				b.show();
				Log.e(TAG, "Exception during data collections", e);
			}
			sched();
		}
	}

	Handler myHandler = new Handler();
	MyAdvancer advancer = null;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		//handleCommand(intent);
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.

		if (advancer == null) {
			advancer = new MyAdvancer();
			advancer.sched();
		}

		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private static DecimalFormat formatter2Digit = new DecimalFormat("00");

	@NonNull
	private String formatSecondsRemaining(int seconds) {
		int hours = seconds / 3600;
		seconds -= hours * 3600;
		int minutes = seconds / 60;
		seconds -= minutes * 60;
		StringBuilder sb = new StringBuilder();
		if (hours != 0 || minutes != 0) {
			sb.append(hours);
			sb.append(":");
			sb.append(formatter2Digit.format(minutes));
			if (seconds != 0) {
				sb.append(":");
				sb.append(formatter2Digit.format(seconds));
				sb.append("s");
			}
		} else {
			sb.append(seconds);
			sb.append("s");
		}
		return sb.toString();
	}

	private void checkBattery() {
		State state = DataLogger.getModel().getState();
		NotificationManager mNotificationManager =
			(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (state != State.NotCharging) {
			Float capacity = (Float)DataLogger.getStoredValue(DataEntry.BatteryCapacity);
			StringBuilder text = new StringBuilder();
			if (capacity != null) {
				text.append((int)(capacity * 100));
				text.append(getString(R.string.percent_charged));
			}
			switch(state) {
				case NoSource:
					text.append(getString(R.string.state_no_supply));
					break;
				case Exception:
					text.append(getString(R.string.state_exception));
					break;
				case InputVoltageLimit:
					text.append(getString(R.string.state_input_voltage_limit));
					break;
				case InputCurrentLimit:
					text.append(getString(R.string.state_input_current_limit));
					break;
				case InternalResistanceLimit:
					text.append(getString(R.string.state_internal_resistance_limit));
					break;
				case BatteryVoltageLimit:
					text.append(getString(R.string.state_battery_voltage_limit));
					break;
			}
			String title = getString(R.string.charging);
			int seconds = DataLogger.getModel().getSecondsRemaining();
			int uncertainty = DataLogger.getModel().getSecondsRemainingUncertainty();
			if (seconds > 0 && uncertainty >= 0) {
				int granularity;
				if (uncertainty < 10) {
					granularity = 1;
				} else if (uncertainty < 30) {
					granularity = 5;
				} else if (uncertainty < 60) {
					granularity = 15;
				} else if (uncertainty < 10 * 60) {
					//noinspection PointlessArithmeticExpression
					granularity = 1 * 60;
				} else if (uncertainty < 30 * 60) {
					granularity = 5 * 60;
				} else if (uncertainty < 60 * 60) {
					granularity = 15 * 60;
				} else if (uncertainty < 10 * 3600) {
					//noinspection PointlessArithmeticExpression
					granularity = 1 * 3600;
				} else {
					granularity = 5 * 3600;
				}
				seconds = ((seconds + granularity / 2) / granularity) * granularity;
				title = getString(R.string.charging_for) + " " +
					formatSecondsRemaining(seconds) + " " +
					getString(R.string.remaining);
			}

			//put up notification
			NotificationCompat.Builder mBuilder =
                                new NotificationCompat.Builder(this)
	                                .setSmallIcon(R.drawable.notification_icon)
	                                .setContentTitle(title)
	                                .setContentText(text)
					.setLocalOnly(false) //todo: make sure this is wanted behaviour and interacts well(i.E. does not popup just because the text changed) currently, this is not getting picked up.
					.setOngoing(true)
					.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
			mNotificationManager.notify(CHARGING_NOTIFICATIONID, mBuilder.build());
		} else {
			//remove notification
			mNotificationManager.cancel(CHARGING_NOTIFICATIONID);
		}
		Status status = (Status)DataLogger.getStoredValue(DataEntry.Status);
		if (status == Status.Full) {
			if (!full_notification_shown) {
				full_notification_shown = true;
				NotificationCompat.Builder mBuilder =
					new NotificationCompat.Builder(this)
						.setSmallIcon(R.drawable.notification_icon)
						.setContentTitle("Battery Full")
						.setLocalOnly(false)
						.setOngoing(false)
						.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
				mNotificationManager.notify(FULL_NOTIFICATIONID, mBuilder.build());
			}
		} else {
			mNotificationManager.cancel(FULL_NOTIFICATIONID);
			full_notification_shown = false;
		}
	}
}

package de.pirsoft.batterymon;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

public class DataLogger extends Service {
	static final String TAG = "Batterymon.DataLogger";

	private boolean external_power = true;
	private Set<Activity> displayingActivities = new HashSet<>();
	private RandomAccessFile logfileraf;
	private	StringBuilder linesb = new StringBuilder();

	public DataLogger() {
	}

	private class MyAdvancer implements Runnable
	{
		int counter = 0;
		void sched()
		{
			if (external_power || !displayingActivities.isEmpty())
				myHandler.postDelayed(this, 1000);
			else
				myHandler.postDelayed(this, 5000);
		}

		public void run()
		{
			/* this is not using threading, just Handler + postDelayed */
			if (this != advancer) { return; }

			try {
				if (counter >= 12 || external_power ||
					!displayingActivities.isEmpty()) {
					collectData(false);
					counter = 0;
				} else {
					collectData(true);
					counter++;
				}
			}
			catch (Exception e) {
				// should not happen.
				//cannot display an alert here.(only system alerts, which needs permission)
				Log.e(TAG, "Exception during data collections", e);
			}
			sched();
		}
	}

	private Handler myHandler = new Handler();
	private MyAdvancer advancer = null;
	static private DataLogger currentInstance;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
        	//handleCommand(intent);
        	// We want this service to continue running until it is explicitly
		// stopped, so return sticky.

		if (advancer == null) {
			advancer = new MyAdvancer();
			advancer.sched();
		}

		currentInstance = this;

        	return START_STICKY;
	}

	public static void addDisplayingActivity(Activity a) {
		if (currentInstance == null)
			return;
		if (currentInstance.displayingActivities.contains(a))
			return;
		currentInstance.displayingActivities.add(a);
	}

	public static void removeDisplayingActivity(Activity a) {
		if (currentInstance == null)
			return;
		currentInstance.displayingActivities.remove(a);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	enum Status { Charging, Discharing, Full, Unknown }

	private static String readProcLine(RandomAccessFile raf, byte[] buf) throws IOException {
		raf.seek(0);
		int len = raf.read(buf);
		//find the line end
		int pos = 0;
		while(pos < len && buf[pos] != '\n') pos++;
		return new String(buf, 0, pos);
	}

	static class Entry {
		String lastValue;
		
		Entry() {
		}

		public String updateData(boolean useLastValue) {
			if (useLastValue) return lastValue;
			return updateData();
		}
		public String updateData() {
			String line = "n/a";
			lastValue = line;
			return line;
		}

		@Nullable
		public Object getValue() {
			return lastValue;
		}

		public void setValue(Object v) {}

		public boolean isCollected() {
			return false;
		}
	}

	static class SysEntry extends Entry {
		String path;
		RandomAccessFile raf;
		private byte buf[] = new byte[20];

		SysEntry(String path) {
			this.path = path;
			try {
				raf = new RandomAccessFile(path,"r");
			}
			catch (Exception ignored) {
			}
		}

		public String updateData(boolean useLastValue) {
			if (useLastValue) return lastValue;
			return updateData();
		}
		public String updateData() {
			String line = "n/a";
			try {
				line = readProcLine(raf, buf);
			}
			catch (Exception ignored) {
			}
			lastValue = line;
			return line;
		}

		public boolean isCollected() {
			return true;
		}
	}

	static private class SysEntryStatus extends SysEntry {
		SysEntryStatus(String path) {
			super(path);
		}

		@Override
		@Nullable
		public Object getValue() {
			Status s = Status.Unknown;
			if ("Charging".equals(lastValue))
				s = Status.Charging;
			if ("Discharging".equals(lastValue))
				s = Status.Discharing;
			if ("Full".equals(lastValue))
				s = Status.Full;
			return s;
		}
	}

	static private class SysEntryBoolean extends SysEntry {
		SysEntryBoolean(String path) {
			super(path);
		}

		@Override
		@Nullable
		public Object getValue() {
			return "1".equals(lastValue);
		}
	}

	static class SysEntryNumeric extends SysEntry {
		private float scaling;

		SysEntryNumeric(String path, float scaling) {
			super(path);
			this.scaling = scaling;
		}

		@Override
		@Nullable
		public Object getValue() {
			if (lastValue != null) {
				return Float.parseFloat(lastValue) * scaling;
			} else {
				return null;
			}
		}
	}

	static private class SysEntryNumericAverage extends SysEntry {
		private int average_count;
		private float scaling;
		private boolean valid = false;
		private float lastValue;
		private byte buf[] = new byte[20];

		SysEntryNumericAverage(String path, float scaling) {
			super(path);
			this.average_count = 5;
			this.scaling = scaling;
		}

		@Override
		public String updateData() {
			String line = "n/a";
			try {
				float val = 0;
				for(int i = 0; i < average_count; i++) {
					line = readProcLine(raf, buf);
					val += Integer.parseInt(line);
				}
				val /= average_count;
				lastValue = val;
				valid = true;
				line = Float.toString(val);
			}
			catch (Exception e) {
				valid = false;
			}
			return line;
		}

		@Override
		@Nullable
		public Object getValue() {
			if (!valid)
				return null;
			else
				return lastValue * scaling;
		}
	}

	static private class CalculatedNumericEntry extends Entry {
		private boolean valid = false;
		private float lastValue;

		@Override
		@Nullable
		public Object getValue() {
			if (!valid)
				return null;
			else
				return lastValue;
		}

		@Override
		public void setValue(Object v) {
			lastValue = (Float)v;
			valid = true;
		}
	}

	static private class SysEntryNumericUncollected extends SysEntryNumeric {
		SysEntryNumericUncollected(String path, float scaling) {
			super(path, scaling);
		}

		public boolean isCollected() {
			return false;
		}
	}


	enum DataEntry {
		Status(0), BatteryCurrent(1), BatteryVoltage(2),
		BMSChargeCounter(3), BMSResistance(4),
		QPNP_DC_Voltage(5),QPNP_DC_Present(6), QPNP_DC_CurrentMax(7),
		USBVoltage(8), USBPresent(9), USBCurrentMax(10),
		Temperature(11),
		BatteryCapacity(12),
		InternalResistance(13),
		ExternalResistance(14),
		ActualBatteryVoltage(15);

		private int numVal;
		DataEntry(int numVal) { this.numVal = numVal; }
		int getNumVal() { return numVal; }
	}

	static final SysEntryStatus sysEntStatus = new SysEntryStatus("/sys/class/power_supply/battery/status");
	
	static final Entry entries[] = {
		sysEntStatus,
		new SysEntryNumericAverage("/sys/class/power_supply/battery/current_now",1e-6f),
		new SysEntryNumericAverage("/sys/class/power_supply/battery/voltage_now",1e-6f),
		new SysEntryNumeric("/sys/class/power_supply/bms/charge_counter_shadow",1e-6f),//Ah
		new SysEntryNumeric("/sys/class/power_supply/bms/resistance",1e-6f),
		new SysEntryNumericAverage("/sys/class/power_supply/qpnp-dc/voltage_now",1e-6f),
		new SysEntryBoolean("/sys/class/power_supply/qpnp-dc/present"),
		new SysEntryNumeric("/sys/class/power_supply/qpnp-dc/current_max",1e-6f),
		new SysEntryNumericAverage("/sys/class/power_supply/usb/voltage_now",1e-6f),
		new SysEntryBoolean("/sys/class/power_supply/usb/present"),
		new SysEntryNumeric("/sys/class/power_supply/usb/current_max",1e-6f),
		new SysEntryNumeric("/sys/class/power_supply/battery/temp",1e-1f),
		new SysEntryNumericUncollected("/sys/class/power_supply/battery/capacity",1e-2f),
		new CalculatedNumericEntry(),
		new CalculatedNumericEntry(),
		new CalculatedNumericEntry()
	};

	static ChargingModel model = new ChargingModel();

	private void collectData(boolean minimal) {
		external_power = !sysEntStatus.updateData().contains("Discharging");
		if (minimal)
			return;
		if (logfileraf == null) {
			File logfile = new File(getExternalFilesDir(null), "data2.log");
			Log.i(TAG, "Starting logging to "+logfile.toString());
			try {
				logfileraf = new RandomAccessFile(logfile, "rws");
				logfileraf.seek(logfile.length());
			} catch (Exception ignored) {
			}
		}
		linesb.setLength(0);
		linesb.append(System.currentTimeMillis());
		//noinspection ForLoopReplaceableByForEach
		for (int i = 0; i < entries.length; i++) {
			Entry entry = entries[i];
			boolean alreadyUpdated = entry == sysEntStatus;
			if (entry.isCollected()) {
				linesb.append(" ");
				linesb.append(entry.updateData(alreadyUpdated));
			} else {
				if (!alreadyUpdated)
					entry.updateData();
			}
		}
		linesb.append('\n');
		try {
			logfileraf.writeBytes(linesb.toString());
		}
		catch (Exception e) {
			Log.e(TAG, "Error writing to log file", e);
		}
		model.updateModel();
	}

	@Nullable
	static public Object getStoredValue(DataEntry e) {
		int num = e.getNumVal();
		if (num < 0 || num > entries.length)
			return null;
		return entries[num].getValue();
	}

	static public void setStoredValue(DataEntry e, Object value) {
		int num = e.getNumVal();
		if (num < 0 || num > entries.length)
			return;
		entries[num].setValue(value);
	}

	static ChargingModel getModel() {
		return model;
	}


}

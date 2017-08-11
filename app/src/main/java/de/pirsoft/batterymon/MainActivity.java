package de.pirsoft.batterymon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.RandomAccessFile;
import java.text.DecimalFormat;

import de.pirsoft.batterymon.DataLogger.DataEntry;


public class MainActivity extends Activity {

	static class SysEntry {
		String name;
		SysEntry(String name) {
			this.name = name;
		}
		public String getData() {
			return "n/a";
		}
	}

	static class SysEntryFile extends SysEntry {
		RandomAccessFile raf;
		SysEntryFile(String name, String path) {
			super(name);
			this.name = name;
			try {
				raf = new RandomAccessFile(path,"r");
			}
			catch (Exception ignored) {
			}
		}
		public String getData() {
			String line = "n/a";
			try {
				raf.seek(0);
				line = raf.readLine();
			}
			catch (Exception e) {
				Log.e(TAG,"Error: ",e);
			}
			return line;
		}
	}

	static private class SysEntryNumeric extends SysEntryFile {
		private float factor;
		private DecimalFormat formatter;
		private int average_count;

		SysEntryNumeric(String name, String path, String unit, float factor) {
			super(name, path);
			this.factor = factor;
			this.average_count = 5;
			formatter = new DecimalFormat("##0.000");
			formatter.setPositiveSuffix(" " + unit);
			formatter.setNegativeSuffix(" "+unit);
		}

		@Override
		public String getData() {
			String line = "n/a";
			try {
				float val = 0;
				for(int i = 0; i < average_count; i++) {
					raf.seek(0);
					line = raf.readLine();
					val += Integer.parseInt(line);
				}
				val /= average_count;
				val *= factor;
				line = formatter.format(val);
			}
			catch (Exception ignored) {
			}
			return line;
		}
	}

	static private class SysEntryDataLoggerNumeric extends SysEntry {
		DataEntry dataentry;
		private float factor;
		private DecimalFormat formatter;
		SysEntryDataLoggerNumeric(String name, DataEntry dataentry, String unit) {
			super(name);
			this.dataentry = dataentry;
			this.factor = 1.0f;
			formatter = new DecimalFormat("##0.000");
			formatter.setPositiveSuffix(" " + unit);
			formatter.setNegativeSuffix(" "+unit);
		}
		SysEntryDataLoggerNumeric(String name, DataEntry dataentry, String unit, float factor) {
			super(name);
			this.dataentry = dataentry;
			this.factor = factor;
			formatter = new DecimalFormat("##0.000");
			formatter.setPositiveSuffix(" " + unit);
			formatter.setNegativeSuffix(" "+unit);
		}
		@Override
		public String getData() {
			String line = "n/a";
			Float val = (Float)DataLogger.getStoredValue(dataentry);
			if (val != null) {
				line = formatter.format(val*factor);
			}
			return line;
		}
	}

	static private class SysEntryDataLoggerBoolean extends SysEntry {
		DataEntry dataentry;
		SysEntryDataLoggerBoolean(String name, DataEntry dataentry) {
			super(name);
			this.dataentry = dataentry;
		}
		@Override
		public String getData() {
			String line = "n/a";
			Boolean val = (Boolean)DataLogger.getStoredValue(dataentry);
			if (val != null) {
				if (val)
					line = "yes";
				else
					line = "no";
			}
			return line;
		}
	}

	static private class SysEntryDataLoggerStatus extends SysEntry {
		DataEntry dataentry;
		SysEntryDataLoggerStatus(String name, DataEntry dataentry) {
			super(name);
			this.dataentry = dataentry;
		}
		@Override
		public String getData() {
			String line = "n/a";
			DataLogger.Status val = (DataLogger.Status)DataLogger.getStoredValue(dataentry);
			if (val != null) {
				switch(val) {
					case Charging:
						line = "Charging";
						break;
					case Discharing:
						line = "Discharging";
						break;
					case Full:
						line = "Full";
						break;
					case Unknown:
						line = "Unknown";
						break;
				}
			}
			return line;
		}
	}

	static final SysEntry sysentries[] = {
		new SysEntryNumeric("battery/charge_full","/sys/class/power_supply/battery/charge_full","mAh",1e3f*1e-6f),
		new SysEntryDataLoggerNumeric("battery/current_now",DataEntry.BatteryCurrent,"mA",1e3f),
		new SysEntryDataLoggerNumeric("battery/voltage_now",DataEntry.BatteryVoltage,"V"),
		new SysEntryDataLoggerStatus("battery/status",DataEntry.Status),
		new SysEntryDataLoggerNumeric("battery/capacity",DataEntry.BatteryCapacity,"%",1e2f),
		new SysEntryDataLoggerNumeric("battery/temp",DataEntry.Temperature,"°C"),
		//new SysEntryNumeric("bms/charge_full","/sys/class/power_supply/bms/charge_full","mAh",1e-3),
		//new SysEntryNumeric("bms/current_now","/sys/class/power_supply/bms/current_now","mA",1e-3),
		//new SysEntryNumeric("bms/charge_counter","/sys/class/power_supply/bms/charge_counter","mAh",1e-3),
		new SysEntryDataLoggerNumeric("bms/charge_counter_shadow",DataEntry.BMSChargeCounter,"mAh",1e3f),
		//new SysEntry("bms/status","/sys/class/power_supply/bms/status"),
		//new SysEntryNumeric("bms/capacity","/sys/class/power_supply/bms/capacity","%"),
		new SysEntryDataLoggerNumeric("bms/resistance",DataEntry.BMSResistance,"mΩ",1e3f),
		//new SysEntry("ext-vbus/present","/sys/class/power_supply/ext-vbus/present"),
		new SysEntryDataLoggerNumeric("qpnp-dc/voltage_now",DataEntry.QPNP_DC_Voltage,"V"),
		new SysEntryDataLoggerBoolean("qpnp-dc/present",DataEntry.QPNP_DC_Present),
		new SysEntryDataLoggerNumeric("qpnp-dc/current_max",DataEntry.QPNP_DC_CurrentMax,"mA",1e3f),
		new SysEntryDataLoggerNumeric("usb/voltage_now",DataEntry.USBVoltage,"V"),
		new SysEntryDataLoggerBoolean("usb/present",DataEntry.USBPresent),
		new SysEntryDataLoggerNumeric("usb/current_max",DataEntry.USBCurrentMax,"mA",1e3f),
		new SysEntryDataLoggerNumeric("internal resistance",DataEntry.InternalResistance,"mΩ",1e3f),
		new SysEntryDataLoggerNumeric("external resistance",DataEntry.ExternalResistance,"mΩ",1e3f),
		new SysEntryDataLoggerNumeric("actual battery voltage",DataEntry.ActualBatteryVoltage,"V"),
	};

	static final String TAG = "Batterymon.MainActivity";

	private class MyAdvancer implements Runnable
	{
		void sched()
		{
			myHandler.postDelayed(this, 1000);
		}

		public void run()
		{
			/* this is not using threading, just Handler + postDelayed */
			if (this != advancer) { return; }

			try {
				updateLabels();
			}
			catch (Exception e) {
				// should not happen.
				AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
				b.setTitle("Error");
				b.setMessage(e.toString());
				b.show();
				Log.e(TAG, "Exception during label update",e);
			}
			sched();
		}
	}

	Handler myHandler = new Handler();
	MyAdvancer advancer = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		ListView listview = (ListView)findViewById(R.id.listView);
		listview.setAdapter(new BaseAdapter() {
			@Override
			public int getCount() {
				return sysentries.length;
			}

			@Override
			public Object getItem(int position) {
				return sysentries[position];
			}

			@Override
			public long getItemId(int position) {
				return (long) position;
			}

			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				View v;
				if (convertView == null) {
					LayoutInflater inflater = getLayoutInflater();
					v = inflater.inflate(R.layout.sys_layout, parent, false);
				} else {
					v = convertView;
				}
				TextView label = (TextView) v.findViewById(R.id.sys_label);
				TextView data = (TextView) v.findViewById(R.id.sys_data);
				label.setText(sysentries[position].name);
				data.setText(sysentries[position].getData());
				return v;
			}

			@Override
			public boolean hasStableIds() {
				return true;
			}
		});

		Log.i(TAG, "Starting logging service");
		Intent serviceIntent = new Intent(this, DataLogger.class);
		startService(serviceIntent);

		Log.i(TAG, "Starting notification service");
		Intent notificationServiceIntent = new Intent(this, BatteryPredictor.class);
		startService(notificationServiceIntent);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		if (id == R.id.action_settings) {
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onPause()
	{
		DataLogger.removeDisplayingActivity(this);
		super.onPause();

		advancer = null;
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		DataLogger.addDisplayingActivity(this);

		advancer = new MyAdvancer();
		advancer.sched();
	}

	private void updateLabels() throws java.io.IOException
	{
		ListView listview = (ListView)findViewById(R.id.listView);
		((BaseAdapter)listview.getAdapter()).notifyDataSetChanged();
	}
}

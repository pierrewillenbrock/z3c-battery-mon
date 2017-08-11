package de.pirsoft.batterymon;

import de.pirsoft.batterymon.DataLogger.DataEntry;
import de.pirsoft.batterymon.DataLogger.Status;

/**
 * Created by pierre on 06.04.16.
 */
@SuppressWarnings("DefaultFileTemplate")
class ChargingModel {

	private class AvgHistory {
		static final int history_length = 20;
		float history[] = new float[history_length];
		int pos = 0;
		int valid = 0;
		float sum = 0;
		float average = 0;
		void integrate(float value) {
			if (valid == history.length)
				sum -= history[pos];
			else
				valid++;
			sum += value;
			history[pos] = value;
			pos++;
			if (pos >= history.length)
				pos = 0;
			average = sum / valid;
		}
	}

	private abstract class Input {
		float voltageMin;
		float currentMax;
		AvgHistory voltageNow = new AvgHistory();
		boolean present;
		abstract public void update();
	}

	private class InputUSB extends Input {
		@SuppressWarnings("ConstantConditions")
		@Override
		public void update() {
			voltageNow.integrate((Float)DataLogger.getStoredValue(DataLogger.DataEntry.USBVoltage));
			present = (Boolean)DataLogger.getStoredValue(DataLogger.DataEntry.USBPresent);
			currentMax = (Float)DataLogger.getStoredValue(DataLogger.DataEntry.USBCurrentMax);
			if (currentMax > 0.5)
				voltageMin = 4.5f;
			else
				voltageMin = 4.9f;
		}
	}

	private class InputQPNP_DC extends Input {
		@SuppressWarnings("ConstantConditions")
		@Override
		public void update() {
			voltageNow.integrate((Float)DataLogger.getStoredValue(DataLogger.DataEntry.QPNP_DC_Voltage));
			present = (Boolean)DataLogger.getStoredValue(DataLogger.DataEntry.QPNP_DC_Present);
			currentMax = (Float)DataLogger.getStoredValue(DataLogger.DataEntry.QPNP_DC_CurrentMax);
			voltageMin = 4.35f;
		}
	}

	private class UBatLimitParams {
		float a = -0.1306617894347005f;
		float b = 0.0008832210812982429f;
		float c = 3154.701435969582f;
		float d = -0.6222642285814441f;
		float e = 0.01873177112731897f;
		float sa = -0.1184127445856138f;
		float sb = 0.03418389946467427f;
		float sc = 188.0148041614768f;
	}

	private class CurveParams {
		float A = 2.757999217707216f;
		float K1 = 1.530360471158158f;
		float Q1 = 0.8463802381765331f;
		float B1 = -26.37423386088393f;
		float v1 = 80.151191309598f;
		float C1 = 26.72967605233235f;
		float K2 = -2.000037807436383e-05f;
		float Q2 = 109.4126705439222f;
		float B2 = 5.504432933925919f;
		float v2 = 1.064738856093674f;
		float C2 = 3.049771481255989e-06f;
		float K3 = 0.01191318206002477f;
		float Q3 = 0.6711461584708354f;
		float B3 = 3.515529492653092f;
		float v3 = 2.535084711091006f;
		float C3 = 0.0003672617713806172f;
	}

	enum State {
		NotCharging, NoSource, Exception,
		InputVoltageLimit, InputCurrentLimit, InternalResistanceLimit, BatteryVoltageLimit
	}

	private State state = State.NotCharging;
	private int secondsRemaining = -1;
	private int secondsRemainingUncertainty = -1;
	private Input inputs[] = { new InputQPNP_DC(), new InputUSB() };
	private UBatLimitParams uBatLimitParams = new UBatLimitParams();
	private CurveParams curveParams = new CurveParams();
	private AvgHistory current = new AvgHistory();
	private AvgHistory voltage = new AvgHistory();
	private float minimumExternalResistance = 1000;

	/**
	 * @param I_acc Accumulated discharge current in As
	 * @return Battery Voltage
	 */
	private float U_bat(CurveParams cp, float I_acc) {
		I_acc /= 3600;
		return (float)(cp.A
			+ cp.K1 / Math.pow(cp.C1 + cp.Q1 * Math.exp(-cp.B1 * I_acc), 1/cp.v1)
			+ cp.K2 / Math.pow(cp.C2 + cp.Q2 * Math.exp(-cp.B2 * I_acc), 1/cp.v2)
			+ cp.K3 / Math.pow(cp.C3 + cp.Q3 * Math.exp(-cp.B3 * I_acc), 1/cp.v3));
	}

	private float U_bat_inv(CurveParams cp, float vol) {
		float I_acc = 0;
		if (vol < 2)
			return 20*3600;
		if (vol > U_bat(cp,0))
			return 0;
		float lo = 0;
		float hi = 20*3600;
		int loops = 20;
		while(hi-lo > 1e-1 && loops > 0) {
			loops--;
			I_acc = (hi+lo)/2;
			float m = U_bat(cp, I_acc);
			if (m > vol)
				lo = I_acc;
			if (m < vol)
				hi = I_acc;
		}
		return I_acc;
	}

	private float map(float in, float in1, float in2, float out1, float out2) {
		return (out1*(in-in2)-out2*(in-in1))/(in1-in2);
	}

	void updateModel() {
		//noinspection unused
		final float batteryVoltageThreshold = 4.2f;
		final float maximumOutputVoltage = 4.25f;
		final float internalResistance = 0.33f;
		final float internalResistanceThreshold = internalResistance * 1.5f;

		try {
			//noinspection ConstantConditions
			current.integrate((Float)DataLogger.getStoredValue(DataEntry.BatteryCurrent));
			//noinspection ConstantConditions
			voltage.integrate((Float)DataLogger.getStoredValue(DataEntry.BatteryVoltage));
		} catch (Exception e) {
			state = State.Exception;
			secondsRemaining = -1;
			secondsRemainingUncertainty = -1;
			return;
		}

		float current = this.current.average;
		float outputVoltage = this.voltage.average;
		float resistance = (Float)DataLogger.getStoredValue(DataEntry.BMSResistance);
		float batteryVoltage = outputVoltage - current * resistance;
		DataLogger.setStoredValue(DataEntry.ActualBatteryVoltage, batteryVoltage);
		if (current < 0) {
			state = State.NotCharging;
			secondsRemaining = -1;
			secondsRemainingUncertainty = -1;
			return;
		}

		Status status = (Status) DataLogger.getStoredValue(DataEntry.Status);
		if (status != Status.Charging) {
			state = State.NotCharging;
			secondsRemaining = -1;
			secondsRemainingUncertainty = -1;
			return;
		}

		Input activeInput = null;
		for (Input input : inputs) {
			try {
				input.update();
			} catch (Exception e) {
				state = State.Exception;
				secondsRemaining = -1;
				secondsRemainingUncertainty = -1;
				return;
			}
			if (input.present) {
				activeInput = input;
				break;
			}
		}

		if (activeInput == null) {
			state = State.NoSource;
			secondsRemaining = -1;
			secondsRemainingUncertainty = -1;
			return;
		}

		float inputVoltage = activeInput.voltageNow.average;
		float accumCurrent_0 = U_bat_inv(curveParams, batteryVoltage);
		float realInternalResistance = (inputVoltage - outputVoltage)/current;

		DataLogger.setStoredValue(DataEntry.InternalResistance, realInternalResistance);

		//map inputVoltage between activeInput.voltageMin*1.05 to activeInput.voltageMin*1.00 to 0 to 1
		float input_voltage_limit_p = map(inputVoltage, activeInput.voltageMin+0.5f, activeInput.voltageMin, 0, 1);
		float input_current_limit_p = map(current, activeInput.currentMax*0.95f, activeInput.currentMax*1.00f, 0, 1);
		float internal_resistance_limit_p = map (realInternalResistance, internalResistanceThreshold, internalResistance, 0, 1);
		float output_voltage_limit_p = map(outputVoltage, maximumOutputVoltage*0.95f, maximumOutputVoltage, 0, 1);

		if (input_voltage_limit_p >= input_current_limit_p &&
			input_voltage_limit_p > internal_resistance_limit_p &&
			input_voltage_limit_p > output_voltage_limit_p &&
			input_voltage_limit_p > 0)
			state = State.InputVoltageLimit;
		else if (input_current_limit_p > internal_resistance_limit_p &&
			input_current_limit_p > output_voltage_limit_p &&
			input_current_limit_p > 0)
			state = State.InputCurrentLimit;
		else if (internal_resistance_limit_p > output_voltage_limit_p &&
			internal_resistance_limit_p > 0)
			state = State.InternalResistanceLimit;
		else if (output_voltage_limit_p > 0)
			state = State.BatteryVoltageLimit;

		//now calculate time remaining
		int seconds = 0;
		int uncertainty = 0;

		if (state == State.InputCurrentLimit || state == State.InputVoltageLimit) {
			//this is a constant current/voltage phase until the resistance reaches
			//it's minimum. we need to do a long-term estimation of the internal
			//resistance.
			if(state == State.InputVoltageLimit) {
				inputVoltage = activeInput.voltageMin;
			} else {
				current = activeInput.currentMax;
			}
			float accumCurrent_t = U_bat_inv(curveParams, inputVoltage - internalResistance *current
				-current * resistance);
			//float outputVoltage_t = inputVoltage - internalResistance *current;

			if (accumCurrent_0 > accumCurrent_t) {
			/* if accumCurrent_t > accumCurrent_0, we actually are already
			   internal resistance limited. */
				float delta_t = (accumCurrent_0 - accumCurrent_t) / current;
				seconds += delta_t;
				uncertainty += delta_t * 0.1; /* todo: find something more reasonable? */
				//outputVoltage = outputVoltage_t;
				accumCurrent_0 = accumCurrent_t;
			}
		}
		if (state == State.InputCurrentLimit || state == State.InputVoltageLimit ||
			state == State.InternalResistanceLimit) {
			//this phase is characterized by a constant resistance across the whole
			//system from the regulator on the input side(inside the supplying device)
			//to the battery. The resistance is the sum of the internal
			//resistance(the current measurement shunt) and external
			//resistance(cable, connectors etc.)
			//we can begin estimation of the external resistance at once, but
			//we cannot determine the supply voltage until the current has changed
			//somewhat.
			float voltageSupply = 5.1f;

			float realExternalResistance = (voltageSupply-inputVoltage)/current;
			DataLogger.setStoredValue(DataEntry.ExternalResistance, realExternalResistance);
			if (realExternalResistance < minimumExternalResistance)
				minimumExternalResistance = realExternalResistance;
			else
				minimumExternalResistance = minimumExternalResistance * 0.95f
					+ realExternalResistance * 0.05f;

			float current_t = (voltageSupply - maximumOutputVoltage) / (realInternalResistance + minimumExternalResistance);
			float batteryVoltage_t = maximumOutputVoltage - current_t * resistance;
			float currentAcc_t = U_bat_inv(curveParams, batteryVoltage_t);


			float delta_t = (accumCurrent_0 - currentAcc_t)/(current_t+current)*2;
			seconds += delta_t;
			uncertainty += delta_t * 0.1; /* todo: find something more reasonable? */

			current = (voltageSupply - maximumOutputVoltage) / (realInternalResistance + minimumExternalResistance);
			//outputVoltage = maximumOutputVoltage;

			//accumCurrent_0 = currentAcc_t;
			//inputVoltage = outputVoltage + realInternalResistance * current;
		}
		if (state == State.InputCurrentLimit || state == State.InputVoltageLimit ||
			state == State.InternalResistanceLimit || state == State.BatteryVoltageLimit) {
			//in this phase, the current is regulated to keep the battery voltage
			//constant. We use a fitted logarithmic curve to estimate the time remaining.
			seconds += Math.log(
					Math.pow(current,uBatLimitParams.d)*uBatLimitParams.e +
					current +
					uBatLimitParams.a)/
				uBatLimitParams.b+uBatLimitParams.c;
			uncertainty += Math.log(
					current +
					uBatLimitParams.sa)/
				uBatLimitParams.sb+uBatLimitParams.sc;
		}
		secondsRemaining = seconds;
		secondsRemainingUncertainty = uncertainty;


		/*
			So, what is this about?

			we need a mapping from
			* input voltage
			* output voltage
			* minimum input voltage(3 discrete values)
			* minimum input current(few possible values)
			* charging current
			to charging time remaining.
			Since there is no single equation governing the whole process, we need
			to calculate it piecewise. We could try to use machine learning on the 5
			input variables above.
			we may have to add some kind of current history to the input variables,
			like the current of 10s or 60s ago.
		 */
	}

	State getState() {
		return state;
	}
	
	int getSecondsRemaining() {
		return secondsRemaining;
	}

	int getSecondsRemainingUncertainty() {
		return secondsRemainingUncertainty;
	}
}

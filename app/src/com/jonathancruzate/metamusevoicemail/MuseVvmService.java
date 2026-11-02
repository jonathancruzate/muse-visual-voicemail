package com.jonathancruzate.metamusevoicemail;

import android.os.Bundle;
import android.os.PersistableBundle;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.telephony.VisualVoicemailService;
import android.telephony.VisualVoicemailSms;
import android.telephony.VisualVoicemailSmsFilterSettings;

/**
 * VisualVoicemailService — bound by telephony ONLY while this app is the
 * default dialer. On cell-service connect it installs the VVM SMS filter
 * using the carrier-config values actually on the SIM (OMTP "//VVM" vs
 * Verizon VVM3 "//VZWVVM" prefix family), and records every VVM SMS event
 * (prefix + field KEY names only — STATUS fields can carry IMAP
 * credentials, which are never logged or stored).
 *
 * Tier 2a scope: receive + record + (in MainActivity) read the shared
 * provider. Tier 2b (own VVM3 STATUS -> IMAP sync inserting rows as a
 * source) is a separate milestone once on-device diagnostics show what
 * the STATUS SMS actually carries.
 */
public class MuseVvmService extends VisualVoicemailService {

    @Override public void onCellServiceConnected(VisualVoicemailTask task, PhoneAccountHandle handle) {
        try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            String type = null, dest = null;
            CarrierConfigManager ccm = getSystemService(CarrierConfigManager.class);
            if (ccm != null) {
                PersistableBundle cfg = ccm.getConfigForSubId(
                        getSubscriptionId(handle));
                if (cfg == null) cfg = ccm.getConfig();
                if (cfg != null) {
                    type = cfg.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING);
                    dest = cfg.getString(CarrierConfigManager.KEY_VVM_DESTINATION_NUMBER_STRING);
                }
            }
            VmLog.log(this, "onCellServiceConnected type=" + type + " dest=" + dest);

            // Verizon's VVM3 uses the //VZWVVM client prefix; OMTP uses //VVM.
            String prefix = (type != null && type.toLowerCase().contains("vvm3"))
                    || (dest != null && dest.startsWith("90008")) ? "//VZWVVM" : "//VVM";
            if (tm != null) {
                VisualVoicemailSmsFilterSettings settings =
                        new VisualVoicemailSmsFilterSettings.Builder()
                                .setClientPrefix(prefix)
                                .setDestinationPort(VisualVoicemailSmsFilterSettings.DESTINATION_PORT_DATA_SMS)
                                .build();
                tm.setVisualVoicemailSmsFilterSettings(settings);
                VmLog.log(this, "SMS filter set, clientPrefix=" + prefix);
            }
        } catch (Exception e) {
            VmLog.log(this, "onCellServiceConnected error: " + e);
        } finally {
            task.finish();
        }
    }

    private int getSubscriptionId(PhoneAccountHandle handle) {
        try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            if (tm != null && handle != null) {
                // PhoneAccount id for the default outgoing account is the sub id
                // on most builds; fall back to the default SMS subscription.
                return android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
            }
        } catch (Exception ignored) {}
        return android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
    }

    @Override public void onSmsReceived(VisualVoicemailTask task, VisualVoicemailSms sms) {
        try {
            StringBuilder keys = new StringBuilder();
            Bundle fields = sms.getFields();
            if (fields != null) {
                for (String k : fields.keySet()) {
                    if (keys.length() > 0) keys.append(',');
                    keys.append(k);
                }
            }
            // Keys + prefix only. Values may contain server credentials.
            VmLog.log(this, "VVM SMS prefix=" + sms.getPrefix() + " fieldKeys=[" + keys + "]");
        } catch (Exception e) {
            VmLog.log(this, "onSmsReceived error: " + e);
        } finally {
            task.finish();
        }
    }

    @Override public void onSimRemoved(VisualVoicemailTask task, PhoneAccountHandle handle) {
        VmLog.log(this, "onSimRemoved");
        task.finish();
    }

    @Override public void onStopped(VisualVoicemailTask task) {
        task.finish();
    }
}

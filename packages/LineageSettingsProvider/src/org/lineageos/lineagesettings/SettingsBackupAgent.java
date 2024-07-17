/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.settings;

import android.annotation.UserIdInt;
import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.settings.backup.DeviceSpecificSettings;
import android.provider.settings.backup.GlobalSettings;
import android.provider.settings.backup.LargeScreenSettings;
import android.provider.settings.backup.SecureSettings;
import android.provider.settings.backup.SystemSettings;
import android.provider.settings.validators.GlobalSettingsValidators;
import android.provider.settings.validators.SecureSettingsValidators;
import android.provider.settings.validators.SystemSettingsValidators;
import android.provider.settings.validators.Validator;
import android.telephony.SubscriptionManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.BackupUtils;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.settingslib.display.DisplayDensityConfiguration;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.DateTimeException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Performs backup and restore of the System and Secure settings.
 * List of settings that are backed up are stored in the Settings.java file
 */
public class SettingsBackupAgent extends BackupAgentHelper {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_BACKUP = DEBUG || false;

    private static final byte[] NULL_VALUE = new byte[0];
    private static final int NULL_SIZE = -1;

    private static final String KEY_SYSTEM = "system";
    private static final String KEY_SECURE = "secure";
    private static final String KEY_GLOBAL = "global";
    private static final String KEY_DEVICE_SPECIFIC_CONFIG = "device_specific_config";
    private static final String KEY_SIM_SPECIFIC_SETTINGS = "sim_specific_settings";

    // Versioning of the state file.  Increment this version
    // number any time the set of state items is altered.
    private static final int STATE_VERSION = 9;

    // Versioning of the Network Policies backup payload.
    private static final int NETWORK_POLICIES_BACKUP_VERSION = 1;


    // Slots in the checksum array.  Never insert new items in the middle
    // of this array; new slots must be appended.
    private static final int STATE_SYSTEM                = 0;
    private static final int STATE_SECURE                = 1;
    private static final int STATE_GLOBAL                = 5;
    private static final int STATE_DEVICE_CONFIG         = 10;
    private static final int STATE_SIM_SPECIFIC_SETTINGS = 11;

    private static final int STATE_SIZE                  = 12; // The current number of state items

    // Number of entries in the checksum array at various version numbers
    private static final int STATE_SIZES[] = {
            0,
            4,              // version 1
            6,              // version 3 added STATE_GLOBAL
            11,             // version 8 added STATE_DEVICE_CONFIG
            STATE_SIZE      // version 9 added STATE_SIM_SPECIFIC_SETTINGS
    };

    private static final int FULL_BACKUP_ADDED_GLOBAL = 2;  // added the "global" entry
    private static final int FULL_BACKUP_ADDED_DEVICE_SPECIFIC = 7; // added "device specific" entry
    // Versioning of the 'full backup' format
    // Increment this version any time a new item is added
    private static final int FULL_BACKUP_VERSION = FULL_BACKUP_ADDED_DEVICE_SPECIFIC;

    private static final int INTEGER_BYTE_COUNT = Integer.SIZE / Byte.SIZE;

    private static final byte[] EMPTY_DATA = new byte[0];

    private static final String TAG = "SettingsBackupAgent";

    @VisibleForTesting
    static final String[] PROJECTION = {
            Settings.NameValueTable.NAME,
            Settings.NameValueTable.VALUE
    };

    // Versioning of the 'device specific' section of a backup
    // Increment this any time the format is changed or data added.
    @VisibleForTesting
    static final int DEVICE_SPECIFIC_VERSION = 1;

    // Name of the temporary file we use during full backup/restore.  This is
    // stored in the full-backup tarfile as well, so should not be changed.
    private static final String STAGE_FILE = "flattened-data";

    // List of keys that support restore to lower version of the SDK, introduced in Android P
    private static final ArraySet<String> RESTORE_FROM_HIGHER_SDK_INT_SUPPORTED_KEYS =
            new ArraySet<String>(Arrays.asList(new String[] {
                KEY_SYSTEM,
                KEY_SECURE,
                KEY_GLOBAL,
            }));

    @VisibleForTesting
    SettingsHelper mSettingsHelper;

    private WifiManager mWifiManager;

    // Version of the SDK that com.android.providers.settings package has been restored from.
    // Populated in onRestore().
    private int mRestoredFromSdkInt;

    @Override
    public void onCreate() {
        if (DEBUG_BACKUP) Log.d(TAG, "onCreate() invoked");

        mSettingsHelper = new SettingsHelper(this);
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        super.onCreate();
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        byte[] systemSettingsData = getSystemSettings();
        byte[] secureSettingsData = getSecureSettings();
        byte[] globalSettingsData = getGlobalSettings();
        byte[] deviceSpecificInformation = getDeviceSpecificConfiguration();
        byte[] simSpecificSettingsData = getSimSpecificSettingsData();

        long[] stateChecksums = readOldChecksums(oldState);

        stateChecksums[STATE_SYSTEM] =
                writeIfChanged(stateChecksums[STATE_SYSTEM], KEY_SYSTEM, systemSettingsData, data);
        stateChecksums[STATE_SECURE] =
                writeIfChanged(stateChecksums[STATE_SECURE], KEY_SECURE, secureSettingsData, data);
        stateChecksums[STATE_GLOBAL] =
                writeIfChanged(stateChecksums[STATE_GLOBAL], KEY_GLOBAL, globalSettingsData, data);
        stateChecksums[STATE_DEVICE_CONFIG] =
                writeIfChanged(stateChecksums[STATE_DEVICE_CONFIG], KEY_DEVICE_SPECIFIC_CONFIG,
                        deviceSpecificInformation, data);
        stateChecksums[STATE_SIM_SPECIFIC_SETTINGS] =
                writeIfChanged(stateChecksums[STATE_SIM_SPECIFIC_SETTINGS],
                        KEY_SIM_SPECIFIC_SETTINGS_2, simSpecificSettingsData, data);

        writeNewChecksums(stateChecksums, newState);
    }

    private boolean isWatch() {
        return getBaseContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState) {
        throw new RuntimeException("SettingsBackupAgent has been migrated to use key exclusion");
    }

    @Override
    public void onRestore(BackupDataInput data, long appVersionCode,
            ParcelFileDescriptor newState, Set<String> dynamicBlockList) throws IOException {

        if (DEBUG) {
            Log.d(TAG, "onRestore(): appVersionCode: " + appVersionCode
                    + "; Build.VERSION.SDK_INT: " + Build.VERSION.SDK_INT);
        }

        boolean overrideRestoreAnyVersion = Settings.Global.getInt(getContentResolver(),
                Settings.Global.OVERRIDE_SETTINGS_PROVIDER_RESTORE_ANY_VERSION, 0) == 1;
        if ((appVersionCode > Build.VERSION.SDK_INT) && overrideRestoreAnyVersion) {
            Log.w(TAG, "Ignoring restore from API" + appVersionCode + " to API"
                    + Build.VERSION.SDK_INT + " due to settings flag override.");
            return;
        }

        // versionCode of com.android.providers.settings corresponds to SDK_INT
        mRestoredFromSdkInt = (int) appVersionCode;

        Set<String> movedToGlobal = getMovedToGlobalSettings();
        Set<String> movedToSecure = getMovedToSecureSettings();
        Set<String> movedToSystem = getMovedToSystemSettings();

        Set<String> preservedGlobalSettings = getSettingsToPreserveInRestore(
                Settings.Global.CONTENT_URI);
        Set<String> preservedSecureSettings = getSettingsToPreserveInRestore(
                Settings.Secure.CONTENT_URI);
        Set<String> preservedSystemSettings = getSettingsToPreserveInRestore(
                Settings.System.CONTENT_URI);
        Set<String> preservedSettings = new HashSet<>(preservedGlobalSettings);
        preservedSettings.addAll(preservedSecureSettings);
        preservedSettings.addAll(preservedSystemSettings);

        byte[] restoredWifiSupplicantData = null;
        byte[] restoredWifiIpConfigData = null;

        while (data.readNextHeader()) {
            final String key = data.getKey();
            final int size = data.getDataSize();

            // bail out of restoring from higher SDK_INT version for unsupported keys
            if (appVersionCode > Build.VERSION.SDK_INT
                    && !RESTORE_FROM_HIGHER_SDK_INT_SUPPORTED_KEYS.contains(key)) {
                Log.w(TAG, "Not restoring unrecognized key '"
                        + key + "' from future version " + appVersionCode);
                data.skipEntityData();
                continue;
            }

            switch (key) {
                case KEY_SYSTEM :
                    restoreSettings(data, Settings.System.CONTENT_URI, movedToGlobal,
                            movedToSecure, /* movedToSystem= */ null,
                            R.array.restore_blocked_system_settings, dynamicBlockList,
                            preservedSystemSettings);
                    mSettingsHelper.applyAudioSettings();
                    break;

                case KEY_SECURE :
                    restoreSettings(data, Settings.Secure.CONTENT_URI, movedToGlobal,
                            /* movedToSecure= */ null, movedToSystem,
                            R.array.restore_blocked_secure_settings, dynamicBlockList,
                            preservedSecureSettings);
                    break;

                case KEY_GLOBAL :
                    restoreSettings(data, Settings.Global.CONTENT_URI, /* movedToGlobal= */ null,
                            movedToSecure, movedToSystem, R.array.restore_blocked_global_settings,
                            dynamicBlockList, preservedGlobalSettings);
                    break;

                case KEY_DEVICE_SPECIFIC_CONFIG:
                    byte[] restoredDeviceSpecificConfig = new byte[size];
                    data.readEntityData(restoredDeviceSpecificConfig, 0, size);
                    restoreDeviceSpecificConfig(
                            restoredDeviceSpecificConfig,
                            R.array.restore_blocked_device_specific_settings,
                            dynamicBlockList,
                            preservedSettings);
                    break;

                case KEY_SIM_SPECIFIC_SETTINGS:
                    // Intentional fall through so that sim-specific backups from Android 12 will
                    // also be restored on newer Android versions.
                case KEY_SIM_SPECIFIC_SETTINGS_2:
                    byte[] restoredSimSpecificSettings = new byte[size];
                    data.readEntityData(restoredSimSpecificSettings, 0, size);
                    restoreSimSpecificSettings(restoredSimSpecificSettings);
                    break;

                default :
                    data.skipEntityData();

            }
        }

        // Do this at the end so that we also pull in the ipconfig data.
        if (restoredWifiSupplicantData != null && !isWatch()) {
            restoreSupplicantWifiConfigData(
                    restoredWifiSupplicantData, restoredWifiIpConfigData);
        }
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data)  throws IOException {
        // Full backup of SettingsBackupAgent support was removed in Android P. If you want to adb
        // backup com.android.providers.settings package use \"-keyvalue\" flag.
        // Full restore of SettingsBackupAgent is still available for backwards compatibility.
    }

    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String relpath, long mode, long mtime)
            throws IOException {
        if (DEBUG_BACKUP) Log.d(TAG, "onRestoreFile() invoked");
        // Our data is actually a blob of flattened settings data identical to that
        // produced during incremental backups.  Just unpack and apply it all in
        // turn.
        FileInputStream instream = new FileInputStream(data.getFileDescriptor());
        DataInputStream in = new DataInputStream(instream);

        int version = in.readInt();
        if (DEBUG_BACKUP) Log.d(TAG, "Flattened data version " + version);
        if (version <= FULL_BACKUP_VERSION) {
            // Generate the moved-to-global lookup table
            Set<String> movedToGlobal = getMovedToGlobalSettings();
            Set<String> movedToSecure = getMovedToSecureSettings();
            Set<String> movedToSystem = getMovedToSystemSettings();

            // system settings data first
            int nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of settings data");
            byte[] buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            restoreSettings(buffer, nBytes, Settings.System.CONTENT_URI, movedToGlobal,
                    movedToSecure, /* movedToSystem= */ null,
                    R.array.restore_blocked_system_settings, Collections.emptySet(),
                    Collections.emptySet());

            // secure settings
            nBytes = in.readInt();
            if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of secure settings data");
            if (nBytes > buffer.length) buffer = new byte[nBytes];
            in.readFully(buffer, 0, nBytes);
            restoreSettings(buffer, nBytes, Settings.Secure.CONTENT_URI, movedToGlobal,
                    /* movedToSecure= */ null, movedToSystem,
                    R.array.restore_blocked_secure_settings, Collections.emptySet(),
                    Collections.emptySet());

            // Global only if sufficiently new
            if (version >= FULL_BACKUP_ADDED_GLOBAL) {
                nBytes = in.readInt();
                if (DEBUG_BACKUP) Log.d(TAG, nBytes + " bytes of global settings data");
                if (nBytes > buffer.length) buffer = new byte[nBytes];
                in.readFully(buffer, 0, nBytes);
                restoreSettings(buffer, nBytes, Settings.Global.CONTENT_URI,
                        /* movedToGlobal= */ null, movedToSecure, movedToSystem,
                        R.array.restore_blocked_global_settings, Collections.emptySet(),
                        Collections.emptySet());
            }

            if (DEBUG_BACKUP) Log.d(TAG, "Full restore complete.");
        } else {
            data.close();
            throw new IOException("Invalid file schema");
        }
    }

    private long[] readOldChecksums(ParcelFileDescriptor oldState) throws IOException {
        long[] stateChecksums = new long[STATE_SIZE];

        DataInputStream dataInput = new DataInputStream(
                new FileInputStream(oldState.getFileDescriptor()));

        try {
            int stateVersion = dataInput.readInt();
            if (stateVersion > STATE_VERSION) {
                // Constrain the maximum state version this backup agent
                // can handle in case a newer or corrupt backup set existed
                stateVersion = STATE_VERSION;
            }
            for (int i = 0; i < STATE_SIZES[stateVersion]; i++) {
                stateChecksums[i] = dataInput.readLong();
            }
        } catch (EOFException eof) {
            // With the default 0 checksum we'll wind up forcing a backup of
            // any unhandled data sets, which is appropriate.
        }
        dataInput.close();
        return stateChecksums;
    }

    private void writeNewChecksums(long[] checksums, ParcelFileDescriptor newState)
            throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(newState.getFileDescriptor())));

        dataOutput.writeInt(STATE_VERSION);
        for (int i = 0; i < STATE_SIZE; i++) {
            dataOutput.writeLong(checksums[i]);
        }
        dataOutput.close();
    }

    private long writeIfChanged(long oldChecksum, String key, byte[] data,
            BackupDataOutput output) {
        CRC32 checkSummer = new CRC32();
        checkSummer.update(data);
        long newChecksum = checkSummer.getValue();
        if (oldChecksum == newChecksum) {
            return oldChecksum;
        }
        try {
            if (DEBUG_BACKUP) {
                Log.v(TAG, "Writing entity " + key + " of size " + data.length);
            }
            output.writeEntityHeader(key, data.length);
            output.writeEntityData(data, data.length);
        } catch (IOException ioe) {
            // Bail
        }
        return newChecksum;
    }

    private byte[] getSystemSettings() {
        Cursor cursor = getContentResolver().query(Settings.System.CONTENT_URI, PROJECTION, null,
                null, null);
        try {
            return extractRelevantValues(cursor, SystemSettings.SETTINGS_TO_BACKUP);
        } finally {
            cursor.close();
        }
    }

    private byte[] getSecureSettings() {
        Cursor cursor = getContentResolver().query(Settings.Secure.CONTENT_URI, PROJECTION, null,
                null, null);
        try {
            return extractRelevantValues(cursor, SecureSettings.SETTINGS_TO_BACKUP);
        } finally {
            cursor.close();
        }
    }

    private byte[] getGlobalSettings() {
        Cursor cursor = getContentResolver().query(Settings.Global.CONTENT_URI, PROJECTION, null,
                null, null);
        try {
            return extractRelevantValues(cursor, GlobalSettings.SETTINGS_TO_BACKUP);
        } finally {
            cursor.close();
        }
    }

    /**
     * Get names of the settings for which the current value should be preserved during restore.
     */
    private Set<String> getSettingsToPreserveInRestore(Uri settingsUri) {
        if (!FeatureFlagUtils.isEnabled(getBaseContext(),
                FeatureFlagUtils.SETTINGS_DO_NOT_RESTORE_PRESERVED)) {
            return Collections.emptySet();
        }

        try (Cursor cursor = getContentResolver().query(settingsUri, new String[]{
                        Settings.NameValueTable.NAME,
                        Settings.NameValueTable.IS_PRESERVED_IN_RESTORE},
                /* selection */ null, /* selectionArgs */ null, /* sortOrder */ null)) {

            if (!cursor.moveToFirst()) {
                Slog.i(TAG, "No settings to be preserved in restore");
                return Collections.emptySet();
            }

            int nameIndex = cursor.getColumnIndex(Settings.NameValueTable.NAME);
            int isPreservedIndex = cursor.getColumnIndex(
                    Settings.NameValueTable.IS_PRESERVED_IN_RESTORE);

            Set<String> preservedSettings = new HashSet<>();
            while (!cursor.isAfterLast()) {
                if (Boolean.parseBoolean(cursor.getString(isPreservedIndex))) {
                    preservedSettings.add(getQualifiedKeyForSetting(cursor.getString(nameIndex),
                            settingsUri));
                }
                cursor.moveToNext();
            }

            return preservedSettings;
        }
    }

    private void restoreSettings(
            BackupDataInput data,
            Uri contentUri,
            Set<String> movedToGlobal,
            Set<String> movedToSecure,
            Set<String> movedToSystem,
            int blockedSettingsArrayId,
            Set<String> dynamicBlockList,
            Set<String> settingsToPreserve) {
        byte[] settings = new byte[data.getDataSize()];
        try {
            data.readEntityData(settings, 0, settings.length);
        } catch (IOException ioe) {
            Log.e(TAG, "Couldn't read entity data");
            return;
        }
        restoreSettings(
                settings,
                settings.length,
                contentUri,
                movedToGlobal,
                movedToSecure,
                movedToSystem,
                blockedSettingsArrayId,
                dynamicBlockList,
                settingsToPreserve);
    }

    private void restoreSettings(
            byte[] settings,
            int bytes,
            Uri contentUri,
            Set<String> movedToGlobal,
            Set<String> movedToSecure,
            Set<String> movedToSystem,
            int blockedSettingsArrayId,
            Set<String> dynamicBlockList,
            Set<String> settingsToPreserve) {
        restoreSettings(
                settings,
                0,
                bytes,
                contentUri,
                movedToGlobal,
                movedToSecure,
                movedToSystem,
                blockedSettingsArrayId,
                dynamicBlockList,
                settingsToPreserve);
    }

    @VisibleForTesting
    void restoreSettings(
            byte[] settings,
            int pos,
            int bytes,
            Uri contentUri,
            Set<String> movedToGlobal,
            Set<String> movedToSecure,
            Set<String> movedToSystem,
            int blockedSettingsArrayId,
            Set<String> dynamicBlockList,
            Set<String> settingsToPreserve) {
        if (DEBUG) {
            Log.i(TAG, "restoreSettings: " + contentUri);
        }

        SettingsBackupWhitelist whitelist = getBackupWhitelist(contentUri);

        // Restore only the white list data.
        final ArrayMap<String, String> cachedEntries = new ArrayMap<>();
        ContentValues contentValues = new ContentValues(2);
        SettingsHelper settingsHelper = mSettingsHelper;
        ContentResolver cr = getContentResolver();

        Set<String> blockedSettings = getBlockedSettings(blockedSettingsArrayId);

        for (String key : whitelist.mSettingsWhitelist) {
            boolean isBlockedBySystem = blockedSettings != null && blockedSettings.contains(key);
            if (isBlockedBySystem || isBlockedByDynamicList(dynamicBlockList, contentUri,  key)) {
                Log.i(
                        TAG,
                        "Key "
                                + key
                                + " removed from restore by "
                                + (isBlockedBySystem ? "system" : "dynamic")
                                + " block list");
                continue;
            }

            // Filter out Settings.Secure.NAVIGATION_MODE from modified preserve settings.
            // Let it take part in restore process. See also b/244532342.
            boolean isSettingPreserved = settingsToPreserve.contains(
                    getQualifiedKeyForSetting(key, contentUri));
            if (isSettingPreserved && !Settings.Secure.NAVIGATION_MODE.equals(key)) {
                Log.i(TAG, "Skipping restore for setting " + key + " as it is marked as "
                        + "preserved");
                continue;
            }

            if (LargeScreenSettings.doNotRestoreIfLargeScreenSetting(key, getBaseContext())) {
                Log.i(TAG, "Skipping restore for setting " + key + " as the target device "
                        + "is a large screen (i.e tablet or foldable in unfolded state)");
                continue;
            }

            String value = null;
            boolean hasValueToRestore = false;
            if (cachedEntries.indexOfKey(key) >= 0) {
                value = cachedEntries.remove(key);
                hasValueToRestore = true;
            } else {
                // If the value not cached, let us look it up.
                while (pos < bytes) {
                    int length = readInt(settings, pos);
                    pos += INTEGER_BYTE_COUNT;
                    String dataKey = length >= 0 ? new String(settings, pos, length) : null;
                    pos += length;
                    length = readInt(settings, pos);
                    pos += INTEGER_BYTE_COUNT;
                    String dataValue = null;
                    if (length >= 0) {
                        dataValue = new String(settings, pos, length);
                        pos += length;
                    }
                    if (key.equals(dataKey)) {
                        value = dataValue;
                        hasValueToRestore = true;
                        break;
                    }
                    cachedEntries.put(dataKey, dataValue);
                }
            }

            if (!hasValueToRestore) {
                continue;
            }

            // only restore the settings that have valid values
            if (!isValidSettingValue(key, value, whitelist.mSettingsValidators)) {
                Log.w(TAG, "Attempted restore of " + key + " setting, but its value didn't pass"
                        + " validation, value: " + value);
                continue;
            }

            final Uri destination;
            if (movedToGlobal != null && movedToGlobal.contains(key)) {
                destination = Settings.Global.CONTENT_URI;
            } else if (movedToSecure != null && movedToSecure.contains(key)) {
                destination = Settings.Secure.CONTENT_URI;
            } else if (movedToSystem != null && movedToSystem.contains(key)) {
                destination = Settings.System.CONTENT_URI;
            } else {
                destination = contentUri;
            }

            // Value is written to NAVIGATION_MODE_RESTORE to mark navigation mode
            // has been set before on source device.
            // See also: b/244532342.
            if (Settings.Secure.NAVIGATION_MODE.equals(key)) {
                contentValues.clear();
                contentValues.put(Settings.NameValueTable.NAME,
                        Settings.Secure.NAVIGATION_MODE_RESTORE);
                contentValues.put(Settings.NameValueTable.VALUE, value);
                cr.insert(destination, contentValues);
                // Avoid restore original setting if it has been preserved.
                if (isSettingPreserved) {
                    Log.i(TAG, "Skipping restore for setting navigation_mode "
                        + "as it is marked as preserved");
                    continue;
                }
            }
            settingsHelper.restoreValue(this, cr, contentValues, destination, key, value,
                    mRestoredFromSdkInt);

            Log.d(TAG, "Restored setting: " + destination + " : " + key + "=" + value);
        }
    }

    @VisibleForTesting
    SettingsBackupWhitelist getBackupWhitelist(Uri contentUri) {
        // Figure out the white list and redirects to the global table.  We restore anything
        // in either the backup allowlist or the legacy-restore allowlist for this table.
        String[] whitelist;
        Map<String, Validator> validators = null;
        if (contentUri.equals(Settings.Secure.CONTENT_URI)) {
            whitelist = ArrayUtils.concat(String.class, SecureSettings.SETTINGS_TO_BACKUP,
                    Settings.Secure.LEGACY_RESTORE_SETTINGS,
                    DeviceSpecificSettings.DEVICE_SPECIFIC_SETTINGS_TO_BACKUP);
            validators = SecureSettingsValidators.VALIDATORS;
        } else if (contentUri.equals(Settings.System.CONTENT_URI)) {
            whitelist = ArrayUtils.concat(String.class, SystemSettings.SETTINGS_TO_BACKUP,
                    Settings.System.LEGACY_RESTORE_SETTINGS);
            validators = SystemSettingsValidators.VALIDATORS;
        } else if (contentUri.equals(Settings.Global.CONTENT_URI)) {
            whitelist = ArrayUtils.concat(String.class, GlobalSettings.SETTINGS_TO_BACKUP,
                    Settings.Global.LEGACY_RESTORE_SETTINGS);
            validators = GlobalSettingsValidators.VALIDATORS;
        } else {
            throw new IllegalArgumentException("Unknown URI: " + contentUri);
        }

        return new SettingsBackupWhitelist(whitelist, validators);
    }

    private boolean isBlockedByDynamicList(Set<String> dynamicBlockList, Uri areaUri, String key) {
        String contentKey = Uri.withAppendedPath(areaUri, key).toString();
        return dynamicBlockList.contains(contentKey);
    }

    @VisibleForTesting
    static String getQualifiedKeyForSetting(String settingName, Uri settingUri) {
        return Uri.withAppendedPath(settingUri, settingName).toString();
    }

    // There may be other sources of blocked settings, so I'm separating out this
    // code to make it easy to modify in the future.
    @VisibleForTesting
    protected Set<String> getBlockedSettings(int blockedSettingsArrayId) {
        String[] blockedSettings = getResources().getStringArray(blockedSettingsArrayId);
        return new HashSet<>(Arrays.asList(blockedSettings));
    }

    private boolean isValidSettingValue(String key, String value,
            Map<String, Validator> validators) {
        if (key == null || validators == null) {
            return false;
        }
        Validator validator = validators.get(key);
        return (validator != null) && validator.validate(value);
    }

    /**
     * Given a cursor and a set of keys, extract the required keys and
     * values and write them to a byte array.
     *
     * @param cursor A cursor with settings data.
     * @param settings The settings to extract.
     * @return The byte array of extracted values.
     */
    private byte[] extractRelevantValues(Cursor cursor, String[] settings) {
        if (!cursor.moveToFirst()) {
            Log.e(TAG, "Couldn't read from the cursor");
            return new byte[0];
        }

        final int nameColumnIndex = cursor.getColumnIndex(Settings.NameValueTable.NAME);
        final int valueColumnIndex = cursor.getColumnIndex(Settings.NameValueTable.VALUE);

        // Obtain the relevant data in a temporary array.
        int totalSize = 0;
        int backedUpSettingIndex = 0;
        final int settingsCount = settings.length;
        final byte[][] values = new byte[settingsCount * 2][]; // keys and values
        final ArrayMap<String, String> cachedEntries = new ArrayMap<>();
        for (int i = 0; i < settingsCount; i++) {
            final String key = settings[i];

            // If the value not cached, let us look it up.
            String value = null;
            boolean hasValueToBackup = false;
            if (cachedEntries.indexOfKey(key) >= 0) {
                value = cachedEntries.remove(key);
                hasValueToBackup = true;
            } else {
                while (!cursor.isAfterLast()) {
                    final String cursorKey = cursor.getString(nameColumnIndex);
                    final String cursorValue = cursor.getString(valueColumnIndex);
                    cursor.moveToNext();
                    if (key.equals(cursorKey)) {
                        value = cursorValue;
                        hasValueToBackup = true;
                        break;
                    }
                    cachedEntries.put(cursorKey, cursorValue);
                }
            }

            if (!hasValueToBackup) {
                continue;
            }

            // Intercept the keys and see if they need special handling
            value = mSettingsHelper.onBackupValue(key, value);

            // Write the key and value in the intermediary array.
            final byte[] keyBytes = key.getBytes();
            totalSize += INTEGER_BYTE_COUNT + keyBytes.length;
            values[backedUpSettingIndex * 2] = keyBytes;

            final byte[] valueBytes = (value != null) ? value.getBytes() : NULL_VALUE;
            totalSize += INTEGER_BYTE_COUNT + valueBytes.length;
            values[backedUpSettingIndex * 2 + 1] = valueBytes;

            backedUpSettingIndex++;

            if (DEBUG) {
                Log.d(TAG, "Backed up setting: " + key + "=" + value);
            }
        }

        // Aggregate the result.
        byte[] result = new byte[totalSize];
        int pos = 0;
        final int keyValuePairCount = backedUpSettingIndex * 2;
        for (int i = 0; i < keyValuePairCount; i++) {
            final byte[] value = values[i];
            if (value != NULL_VALUE) {
                pos = writeInt(result, pos, value.length);
                pos = writeBytes(result, pos, value);
            } else {
                pos = writeInt(result, pos, NULL_SIZE);
            }
        }
        return result;
    }

    private boolean isNeedToNotifyUserConfigurationHasChanged(SoftApConfiguration configInCloud,
            SoftApConfiguration storedConfig) {
        // Check if the cloud configuration was modified when restored to the device.
        // All elements of the configuration are compared except:
        // 1. Persistent randomized MAC address (which is per device)
        // 2. The flag indicating whether the configuration is "user modified"
        return !(Objects.equals(configInCloud.getWifiSsid(), storedConfig.getWifiSsid())
                && Objects.equals(configInCloud.getBssid(), storedConfig.getBssid())
                && Objects.equals(configInCloud.getPassphrase(), storedConfig.getPassphrase())
                && configInCloud.isHiddenSsid() == storedConfig.isHiddenSsid()
                && configInCloud.getChannels().toString().equals(
                        storedConfig.getChannels().toString())
                && configInCloud.getSecurityType() == storedConfig.getSecurityType()
                && configInCloud.getMaxNumberOfClients() == storedConfig.getMaxNumberOfClients()
                && configInCloud.isAutoShutdownEnabled() == storedConfig.isAutoShutdownEnabled()
                && configInCloud.getShutdownTimeoutMillis()
                        == storedConfig.getShutdownTimeoutMillis()
                && configInCloud.isClientControlByUserEnabled()
                        == storedConfig.isClientControlByUserEnabled()
                && Objects.equals(configInCloud.getBlockedClientList(),
                        storedConfig.getBlockedClientList())
                && Objects.equals(configInCloud.getAllowedClientList(),
                        storedConfig.getAllowedClientList())
                && configInCloud.getMacRandomizationSetting()
                        == storedConfig.getMacRandomizationSetting()
                && configInCloud.isBridgedModeOpportunisticShutdownEnabled()
                        == storedConfig.isBridgedModeOpportunisticShutdownEnabled()
                && configInCloud.isIeee80211axEnabled() == storedConfig.isIeee80211axEnabled()
                && configInCloud.isIeee80211beEnabled() == storedConfig.isIeee80211beEnabled()
                && configInCloud.getBridgedModeOpportunisticShutdownTimeoutMillis()
                        == storedConfig.getBridgedModeOpportunisticShutdownTimeoutMillis()
                && Objects.equals(configInCloud.getVendorElements(),
                        storedConfig.getVendorElements())
                && Arrays.equals(configInCloud.getAllowedAcsChannels(
                        SoftApConfiguration.BAND_2GHZ),
                        storedConfig.getAllowedAcsChannels(SoftApConfiguration.BAND_2GHZ))
                && Arrays.equals(configInCloud.getAllowedAcsChannels(
                        SoftApConfiguration.BAND_5GHZ),
                        storedConfig.getAllowedAcsChannels(SoftApConfiguration.BAND_5GHZ))
                && Arrays.equals(configInCloud.getAllowedAcsChannels(
                        SoftApConfiguration.BAND_6GHZ),
                        storedConfig.getAllowedAcsChannels(SoftApConfiguration.BAND_6GHZ))
                && configInCloud.getMaxChannelBandwidth() == storedConfig.getMaxChannelBandwidth()
                        );
    }

    @VisibleForTesting
    byte[] getDeviceSpecificConfiguration() throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            writeHeader(os);
            os.write(getDeviceSpecificSettings());
            return os.toByteArray();
        }
    }

    @VisibleForTesting
    void writeHeader(OutputStream os) throws IOException {
        os.write(toByteArray(DEVICE_SPECIFIC_VERSION));
        os.write(toByteArray(Build.MANUFACTURER));
        os.write(toByteArray(Build.PRODUCT));
    }

    private byte[] getDeviceSpecificSettings() {
        try (Cursor cursor =
                     getContentResolver()
                             .query(Settings.Secure.CONTENT_URI, PROJECTION, null, null, null)) {
            return extractRelevantValues(
                    cursor, DeviceSpecificSettings.DEVICE_SPECIFIC_SETTINGS_TO_BACKUP);
        }
    }

    /**
     * Restore the device specific settings.
     *
     * @param data The byte array holding a backed up version of another devices settings.
     * @param blockedSettingsArrayId The string array resource holding the settings not to restore.
     * @param dynamicBlocklist The dynamic list of settings not to restore fed into this agent.
     * @return true if the restore succeeded, false if it was stopped.
     */
    @VisibleForTesting
    boolean restoreDeviceSpecificConfig(byte[] data, int blockedSettingsArrayId,
            Set<String> dynamicBlocklist, Set<String> preservedSettings) {
        // We're using an AtomicInteger to wrap the position int and allow called methods to
        // modify it.
        AtomicInteger pos = new AtomicInteger(0);
        if (!isSourceAcceptable(data, pos)) {
            return false;
        }

        Integer originalDensity = getPreviousDensity();

        int dataStart = pos.get();
        restoreSettings(
                data,
                dataStart,
                data.length,
                Settings.Secure.CONTENT_URI,
                null,
                null,
                null,
                blockedSettingsArrayId,
                dynamicBlocklist,
                preservedSettings);

        updateWindowManagerIfNeeded(originalDensity);

        return true;
    }

    private byte[] getSimSpecificSettingsData() {
        byte[] simSpecificData = new byte[0];
        PackageManager packageManager = getBaseContext().getPackageManager();
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            SubscriptionManager subManager = SubscriptionManager.from(getBaseContext());
            simSpecificData = subManager.getAllSimSpecificSettingsForBackup();
            Log.i(TAG, "sim specific data of length + " + simSpecificData.length
                + " successfully retrieved");
        }

        return simSpecificData;
    }

    private void restoreSimSpecificSettings(byte[] data) {
        PackageManager packageManager = getBaseContext().getPackageManager();
        boolean hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        if (hasTelephony) {
            SubscriptionManager subManager = SubscriptionManager.from(getBaseContext());
            subManager.restoreAllSimSpecificSettingsFromBackup(data);
        }
    }

    private void updateWindowManagerIfNeeded(Integer previousDensity) {
        int newDensity;
        try {
            newDensity = getForcedDensity();
        } catch (Settings.SettingNotFoundException e) {
            // If there's not density setting we can't perform a change.
            return;
        }

        if (previousDensity == null || previousDensity != newDensity) {
            // From nothing to something is a change.
            DisplayDensityConfiguration.setForcedDisplayDensity(
                    Display.DEFAULT_DISPLAY, newDensity);
        }
    }

    @VisibleForTesting
    boolean isSourceAcceptable(byte[] data, AtomicInteger pos) {
        int version = readInt(data, pos);
        if (version > DEVICE_SPECIFIC_VERSION) {
            Slog.w(TAG, "Unable to restore device specific information; Backup is too new");
            return false;
        }

        String sourceManufacturer = readString(data, pos);
        if (!Objects.equals(Build.MANUFACTURER, sourceManufacturer)) {
            Log.w(
                    TAG,
                    "Unable to restore device specific information; Manufacturer mismatch "
                            + "(\'"
                            + Build.MANUFACTURER
                            + "\' and \'"
                            + sourceManufacturer
                            + "\')");
            return false;
        }

        String sourceProduct = readString(data, pos);
        if (!Objects.equals(Build.PRODUCT, sourceProduct)) {
            Log.w(
                    TAG,
                    "Unable to restore device specific information; Product mismatch (\'"
                            + Build.PRODUCT
                            + "\' and \'"
                            + sourceProduct
                            + "\')");
            return false;
        }

        return true;
    }

    @VisibleForTesting
    static byte[] toByteArray(String value) {
        if (value == null) {
            return toByteArray(NULL_SIZE);
        }

        byte[] stringBytes = value.getBytes();
        byte[] sizeAndString = new byte[stringBytes.length + INTEGER_BYTE_COUNT];
        writeInt(sizeAndString, 0, stringBytes.length);
        writeBytes(sizeAndString, INTEGER_BYTE_COUNT, stringBytes);
        return sizeAndString;
    }

    @VisibleForTesting
    static byte[] toByteArray(int value) {
        byte[] result = new byte[INTEGER_BYTE_COUNT];
        writeInt(result, 0, value);
        return result;
    }

    private String readString(byte[] data, AtomicInteger pos) {
        int byteCount = readInt(data, pos);
        if (byteCount == NULL_SIZE) {
            return null;
        }

        int stringStart = pos.getAndAdd(byteCount);
        return new String(data, stringStart, byteCount);
    }

    /**
     * Write an int in BigEndian into the byte array.
     * @param out byte array
     * @param pos current pos in array
     * @param value integer to write
     * @return the index after adding the size of an int (4) in bytes.
     */
    private static int writeInt(byte[] out, int pos, int value) {
        out[pos + 0] = (byte) ((value >> 24) & 0xFF);
        out[pos + 1] = (byte) ((value >> 16) & 0xFF);
        out[pos + 2] = (byte) ((value >>  8) & 0xFF);
        out[pos + 3] = (byte) ((value >>  0) & 0xFF);
        return pos + INTEGER_BYTE_COUNT;
    }

    private static int writeBytes(byte[] out, int pos, byte[] value) {
        System.arraycopy(value, 0, out, pos, value.length);
        return pos + value.length;
    }

    private int readInt(byte[] in, AtomicInteger pos) {
        return readInt(in, pos.getAndAdd(INTEGER_BYTE_COUNT));
    }

    private int readInt(byte[] in, int pos) {
        int result = ((in[pos] & 0xFF) << 24)
                | ((in[pos + 1] & 0xFF) << 16)
                | ((in[pos + 2] & 0xFF) <<  8)
                | ((in[pos + 3] & 0xFF) <<  0);
        return result;
    }

    /**
     * Store the allowlist of settings to be backed up and validators for them.
     */
    @VisibleForTesting
    static class SettingsBackupWhitelist {
        final String[] mSettingsWhitelist;
        final Map<String, Validator> mSettingsValidators;


        SettingsBackupWhitelist(String[] settingsWhitelist,
                Map<String, Validator> settingsValidators) {
            mSettingsWhitelist = settingsWhitelist;
            mSettingsValidators = settingsValidators;
        }
    }
}

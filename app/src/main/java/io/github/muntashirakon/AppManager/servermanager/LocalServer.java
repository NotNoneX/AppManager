/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.servermanager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.io.IOException;
import java.net.SocketTimeoutException;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.misc.Users;
import io.github.muntashirakon.AppManager.server.common.Caller;
import io.github.muntashirakon.AppManager.server.common.CallerResult;

public class LocalServer {

    @SuppressLint("StaticFieldLeak")
    private static LocalServer INSTANCE;

    public static LocalServer getInstance() {
        if (INSTANCE == null) {
            synchronized (LocalServer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new LocalServer();
                }
            }
        }
        return INSTANCE;
    }

    private final LocalServerManager mLocalServerManager;
    private final Context mContext;

    private LocalServer() {
        mContext = AppManager.getContext();
        Config config = new Config();
        config.context = mContext;
        updateConfig(config);
        int userHandleId = Users.getCurrentUserHandle();
        ServerConfig.init(config.context, userHandleId);
        mLocalServerManager = LocalServerManager.getInstance(config);
        // Check if am.jar is in the right place
        checkFile();
        // Start server if not already
        try {
            checkConnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    Config getConfig() {
        return mLocalServerManager.getConfig();
    }

    Context getContext() {
        return mContext;
    }

    private final Object connectLock = new Object();
    private boolean connectStarted = false;

    @GuardedBy("connectLock")
    @WorkerThread
    public void checkConnect() throws IOException {
        synchronized (connectLock) {
            if (connectStarted) {
                try {
                    connectLock.wait();
                } catch (InterruptedException e) {
                    return;
                }
            }
            connectStarted = true;
            try {
                mLocalServerManager.start();
            } catch (IOException e) {
                connectStarted = false;
                connectLock.notify();
                throw new IOException(e);
            }
            connectStarted = false;
            connectLock.notify();
        }
    }

    @WorkerThread
    public CallerResult exec(Caller caller) throws Exception {
        try {
            checkConnect();
            return mLocalServerManager.execNew(caller);
        } catch (SocketTimeoutException e) {
            e.printStackTrace();
            closeBgServer();
            // Retry
            checkConnect();
            return mLocalServerManager.execNew(caller);
        }
    }

    public boolean isRunning() {
        return mLocalServerManager != null && mLocalServerManager.isRunning();
    }

    public void destroy() {
        if (mLocalServerManager != null) {
            mLocalServerManager.stop();
        }
    }

    public void closeBgServer() {
        if (mLocalServerManager != null) {
            mLocalServerManager.closeBgServer();
            mLocalServerManager.stop();
        }
    }

    private void checkFile() {
        AssetsUtils.copyFile(mContext, ServerConfig.JAR_NAME, ServerConfig.getDestJarFile(), BuildConfig.DEBUG);
        AssetsUtils.writeScript(getConfig());
    }

    public static void updateConfig() {
        if (INSTANCE != null) {
            Config config = INSTANCE.getConfig();
            if (config != null) {
                updateConfig(config);
            }
        }
    }

    private static void updateConfig(@NonNull Config config) {
        // FIXME: Use AppPref instead of SharedPreferences
        // FIXME(10/9/20): These prefs are not saved anywhere
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(config.context);
        config.allowBgRunning = sp.getBoolean("allow_bg_running", true);
        config.adbPort = sp.getInt("adb_port", 5555);
        if (INSTANCE != null) INSTANCE.mLocalServerManager.updateConfig(config);
    }

    public static class Config {
        public boolean allowBgRunning = false;
        public boolean printLog = false;
        public String adbHost = "127.0.0.1";
        public int adbPort = 5555;
        Context context;
    }
}
